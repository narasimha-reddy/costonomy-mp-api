# Costonomy Delivery Module — Product Team Blueprint & Visual Guide

This document visualizes the end-to-end architecture, lifecycle state machine, carrier waterfall orchestration, and operational guardrails of the **Costonomy Delivery Module**.

---

## 1. Executive Summary & Core Delivery Modes

The delivery engine is designed to be **provider-agnostic**, ensuring restaurants and suppliers experience a uniform Mandi order journey regardless of who physically transports the goods.

```mermaid
flowchart TD
    subgraph OrderCheckout["1. Checkout / Order Intent"]
        Cart["Restaurant Places Order"]
        ModeDecision{"Delivery Mode?"}
        Cart --> ModeDecision
    end

    subgraph SupplierMode["Option A: Supplier Own Delivery"]
        SuppDelivery["Supplier Fleet / Vehicle"]
        SuppFee["Supplier Delivery Fee (often ₹0)"]
        SuppReporting["Supplier Manually Updates Status"]
        NoTracking["No Live GPS Tracking (Platform No Operational Liability)"]
        SuppDelivery --- SuppFee
        SuppDelivery --- SuppReporting
        SuppDelivery --- NoTracking
    end

    subgraph CostonomyMode["Option B: Costonomy Orchestrated Delivery"]
        PlatformFee["Fixed Platform Delivery Fee Quote"]
        Aggregator["Multi-Carrier Waterfall Engine"]
        NineCarriers["9 3PL Providers (Blowhorn, Delhivery, Pidge, etc.)"]
        LiveGPS["Live Telemetry & Driver GPS Tracking (< 60s Freshness)"]
        PlatformFee --- Aggregator
        Aggregator --- NineCarriers
        Aggregator --- LiveGPS
    end

    ModeDecision -->|SUPPLIER_OWN| SuppDelivery
    ModeDecision -->|COSTONOMY| PlatformFee
```

### Key Differences at a Glance

| Feature | Supplier Own (`SUPPLIER_OWN`) | Costonomy Orchestrated (`COSTONOMY`) |
| :--- | :--- | :--- |
| **Who Transports** | Supplier's own staff or private vehicle | 3PL Partner (Blowhorn, Delhivery, Pidge, Porter, etc.) |
| **Pricing to Buyer** | Set by supplier (typically free ₹0) | Quoted platform fee based on weight, distance & vehicle |
| **Carrier Bids** | N/A | Strictly internal (hidden from restaurant & supplier) |
| **State Progression** | Supplier reports pickup & delivery | Driven strictly by courier webhooks/events (§23A.38) |
| **Live Tracking** | Disabled (prevents fake location generation) | Full GPS map tracking, speed, bearing & ETA |
| **SLA Responsibility**| Supplier bears full operational responsibility | Costonomy platform orchestrates retries & reassignment |

---

## 2. End-to-End Delivery Lifecycle & State Machine

The delivery state machine is strictly monotonic. Out-of-order webhook events cannot rewind progress, and non-terminal failures trigger automated cascading rather than failing the order.

```mermaid
stateDiagram-v2
    [*] --> DELIVERY_REQUESTED: Order marked READY_FOR_PICKUP

    DELIVERY_REQUESTED --> QUOTE_RECEIVED: Multi-carrier quotes gathered
    DELIVERY_REQUESTED --> QUOTE_FAILED: No carrier serviceable / out of radius
    DELIVERY_REQUESTED --> CANCELLED: Order cancelled before pickup

    QUOTE_FAILED --> QUOTE_RECEIVED: Background Auto-Retry (30m window)
    QUOTE_FAILED --> DRIVER_ASSIGNED: Supplier switches to Own Delivery (Fallback)

    QUOTE_RECEIVED --> PROVIDER_SELECTED: Carrier booked (cheapest qualifying)
    QUOTE_RECEIVED --> PROVIDER_UNAVAILABLE: All carriers rejected booking

    PROVIDER_UNAVAILABLE --> PROVIDER_SELECTED: Waterfall cascade to alternative carrier
    PROVIDER_UNAVAILABLE --> DRIVER_ASSIGNED: Supplier switches to Own Delivery (Fallback)

    PROVIDER_SELECTED --> DRIVER_ASSIGNED: Driver accepted assignment
    PROVIDER_SELECTED --> PROVIDER_SELECTED: Timeout expired (Bike 3m / Truck 12m) -> Cascade
    PROVIDER_SELECTED --> DRIVER_CANCELLED: Driver rejected assignment -> Cascade

    DRIVER_ASSIGNED --> DRIVER_AT_PICKUP: Courier arrives at supplier warehouse
    DRIVER_ASSIGNED --> PICKUP_FAILED: Consignment not ready / vehicle mismatch

    DRIVER_AT_PICKUP --> PICKED_UP: Consignment collected (Order becomes OUT_FOR_DELIVERY)
    DRIVER_AT_PICKUP --> PICKUP_FAILED: Driver leaves without goods -> Reassign

    PICKED_UP --> IN_TRANSIT: Driver en route to restaurant
    IN_TRANSIT --> ARRIVED_AT_DESTINATION: Driver arrives at restaurant outlet

    ARRIVED_AT_DESTINATION --> DELIVERED: Proof of delivery confirmed (Order DELIVERED)
    IN_TRANSIT --> DELIVERY_FAILED: Outlet closed / recipient unavailable
    DELIVERY_FAILED --> IN_TRANSIT: Re-attempt delivery

    DELIVERED --> [*]
    CANCELLED --> [*]
```

### State Classification

*   **Initial:** `DELIVERY_REQUESTED`
*   **Carrier Quoting & Selection:** `QUOTE_RECEIVED`, `PROVIDER_SELECTED`
*   **Execution & Fulfillment:** `DRIVER_ASSIGNED`, `DRIVER_AT_PICKUP`, `PICKED_UP`, `IN_TRANSIT`, `ARRIVED_AT_DESTINATION`
*   **Recoverable Setbacks (Auto-cascade/reassign):** `QUOTE_FAILED`, `PROVIDER_UNAVAILABLE`, `DRIVER_CANCELLED`, `PICKUP_FAILED`, `DELIVERY_FAILED`
*   **Terminal States (Only 2):** `DELIVERED`, `CANCELLED`

---

## 3. Carrier Selection & Waterfall Cascading Engine

Costonomy integrates 9 logistics providers. When an order is ready, all available providers are queried simultaneously.

```mermaid
sequenceDiagram
    autonumber
    actor Order as Warehouse / Supplier
    participant DS as DeliveryService
    participant QS as QuotingService
    participant Gate as ColdChainCarrierGate
    participant BS as BookingService
    participant WF as WaterfallService
    participant 3PL as 3PL Carrier (Pidge / Delhivery / Porter)
    actor Buyer as Restaurant App

    Order->>DS: Mark Order READY_FOR_PICKUP (autoDispatch)
    DS->>QS: Gather quotes (Weight, Volume, Lat/Lng, ColdChain flag)
    
    QS->>Gate: Filter carriers verified for cold chain (if perishable)
    QS->>3PL: Parallel Quote Requests
    3PL-->>QS: Return fare quotes & ETAs
    
    Note over QS: Algorithmic Selection: Lowest Cost meeting required ETA
    
    QS-->>DS: Winner chosen (e.g. Carrier A)
    DS->>BS: Book Carrier A
    BS->>3PL: Book Consignment
    3PL-->>BS: Booking confirmed + ProviderDeliveryId
    BS->>WF: Start assignment SLA timer (Bike: 3m, Truck: 12m)
    BS->>Buyer: Push "Finding a driver" update

    alt Carrier A Assigns Driver within SLA
        3PL-->>DS: Webhook: DriverAssigned (Name, Phone, GPS)
        DS->>Buyer: Live Map Tracking Active
    else Assignment SLA Timeout (No Driver after 3m)
        WF->>DS: SLA Timeout Expired! Cascade unassigned delivery
        DS->>3PL: Cancel booking with Carrier A (stand down)
        DS->>BS: Book next best quote (Carrier B)
        Note over DS,Buyer: Same Delivery ID! attempt_count increments. Restaurant sees continuous "Finding driver"
    end
```

### Selection Rules

1.  **Strictly Lowest Cost meeting ETA**: Commission or margin plays zero part. Guardrail ensures the platform does not pass higher fees to buyers.
2.  **Fallback to Fastest**: If no courier meets the required ETA, the system selects the fastest available courier, prioritizing arrival over price.
3.  **Vehicle Type Classification**:
    *   `BIKE`: < 20 kg
    *   `THREE_WHEELER`: 20 kg – 500 kg
    *   `FOUR_WHEELER_TRUCK`: > 500 kg
4.  **Intra-City Radius Gate**: Capped at 30 km max radius. Orders beyond 30 km are flagged `UNSERVICEABLE` before external queries.
5.  **Cold Chain Verification**: For temperature-sensitive perishable items, carriers must hold verified cold-chain equipment for the specific vehicle category.

---

## 4. Live Tracking & Webhook Processing Pipeline

To provide a seamless Uber-like tracking experience, incoming webhook events from logistics partners are sanitized and deduplicated:

```mermaid
flowchart TD
    WebhookIn["Incoming Provider Webhook (Pidge, Borzo, etc.)"] --> AuthCheck{"Signature & IP Verified?"}
    AuthCheck -->|No| Reject401["Reject 401 Unauthorized"]
    AuthCheck -->|Yes| IdempotencyCheck{"providerEventId exists?"}
    
    IdempotencyCheck -->|Yes (Duplicate)| Duplicate200["Ignore & Return 200 OK (Replay Protection)"]
    IdempotencyCheck -->|No| OrderCheck{"Is Event Moving Backward in Rank?"}
    
    OrderCheck -->|Yes (Out of order)| StoreOutOfOrder["Store as OUT_OF_ORDER (Do not apply to UI)"]
    OrderCheck -->|No| ApplyEvent["Advance Delivery Status"]

    ApplyEvent --> GPSCheck{"Has Driver Location (Lat/Lng)?"}
    GPSCheck -->|Yes| StoreLocation["Save Lat/Lng, Bearing, Timestamp"]
    GPSCheck -->|No| EventAudit["Record in Delivery Ledger & Timeline"]

    StoreLocation --> FreshnessCheck{"Location Age > 60s?"}
    FreshnessCheck -->|Yes| MarkStale["Mark Tracking as STALE in App"]
    FreshnessCheck -->|No| BroadcastWS["Broadcast Real-time Update via WebSocket to Restaurant"]
```

---

## 5. Failures & "No Partner Found" Fallback (D-185)

When no third-party logistics partner can accept the delivery (e.g., peak demand, severe weather, or remote location), Costonomy does not abandon the restaurant.

```mermaid
flowchart LR
    AllFail["All Carrier Quotes/Bookings Fail"] --> StatusNoPartner["Status: QUOTE_FAILED / PROVIDER_UNAVAILABLE"]
    StatusNoPartner --> Timer30["30-Minute Auto-Retry Window"]
    
    Timer30 --> LoopRetry{"Auto-retry every 5m"}
    LoopRetry -->|Partner Found| SuccessBook["Book Partner & Proceed"]
    
    StatusNoPartner --> OfferSupplier["Push Offer to Supplier: 'Deliver Order Yourself'"]
    OfferSupplier --> SupplierAccepts{"Supplier Accepts?"}
    
    SupplierAccepts -->|Yes| SwitchMode["Switch Mode to SUPPLIER_OWN"]
    SwitchMode --> RetainFee["Buyer fee remains unchanged (No silent repricing)"]
    SwitchMode --> SupplierFleet["Supplier Dispatches Own Vehicle"]
    
    LoopRetry -->|Window Expires| Escalation["Operations Admin Radar Alert"]
```

### Safety & Integrity Rules

*   **Single Delivery Identity (D-026)**: Cascading or switching to supplier-own mode **never** creates a second `delivery` row. The same delivery ID is maintained throughout so order history, invoices, and analytics remain consistent.
*   **No Silent Reprice (§23A.16)**: When switching from Costonomy to supplier-own delivery after a partner failure, the delivery fee paid by the buyer is preserved exactly as quoted at checkout.
*   **Strict Access Control (§23A.38)**: On Costonomy-managed deliveries, suppliers are blocked from manually clicking "Delivered". Only verified 3PL telemetry or delivery OTP can mark fulfillment.

---

## 6. Summary for Product Roadmap & Operations

1.  **Buyer Experience**: Clean ETA countdown, real-time map with driver marker, and automatic indicator if GPS signal becomes stale (> 60s).
2.  **Supplier Experience**: Automated dispatch upon packing; one-tap fallback to own vehicle if 3PL network is congested.
3.  **Operations & Admin**: Full audit ledger showing every carrier quote, decline reason, waterfall cascade, and vehicle allocation.
