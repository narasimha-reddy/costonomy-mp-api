# Architecture Sequence Diagrams: Delivery & Logistics Module

This document details the exact runtime request/response sequence diagrams for all flows in the **Delivery & Logistics** module, as implemented in the code at 2026-10-25 (multi-carrier waterfall, persistent assignment sweeps, D-201 food delivery).

> **Status.** These sequences follow the code; they say nothing about what is deployed (no deployment evidence exists in the repo). Money and failure paths (no-partner retry, takeover, courier payable, gateway refunds, ops resolution) are **not** drawn here — see [`docs/delivery/DIAGRAMS.md`](../../delivery/DIAGRAMS.md) and [`docs/delivery/CURRENT_STATE.md`](../../delivery/CURRENT_STATE.md). Only the Pidge webhook route exists; other providers are polled. Database is MySQL.

---

## 1. Automated Dispatch & Multi-Carrier Waterfall Quoting/Booking
**Trigger**: A warehouse or kitchen event (`SupplierOrderReady`) is published via the transactional outbox relay, or an authorized actor calls `POST /api/v1/supplier-orders/{orderId}/delivery`.

```mermaid
sequenceDiagram
    autonumber
    participant OB as Transactional Outbox Relay
    participant DDL as DeliveryDispatchListener
    participant DS as DeliveryService
    participant QS as DeliveryQuotingService
    participant Gate as ColdChainCarrierGate
    participant BS as DeliveryBookingService
    participant WF as DeliveryWaterfallService
    participant DP as 3PL Carrier (Pidge / Delhivery / Porter)
    participant DB as MySQL (deliveries, quotes, attempts, ledger)

    OB->>DDL: SupplierOrderReady Event
    DDL->>DS: autoDispatch(supplierOrderId) [REQUIRES_NEW]
    DS->>DB: SELECT * FROM delivery WHERE supplier_order_id = ?
    alt Delivery Already Exists
        DS-->>DDL: Return existing delivery (Idempotency guarantee)
    else New Consignment
        DS->>DB: INSERT INTO delivery (status=DELIVERY_REQUESTED, vehicle_type, addresses)
        alt DeliveryMode == SUPPLIER_OWN
            DS->>DB: UPDATE delivery SET status=DRIVER_ASSIGNED, fee=orderFee
            DS-->>DDL: Handled by supplier's own fleet
        else DeliveryMode == COSTONOMY
            DS->>QS: gather(delivery, orderValue, weight, requiredEta, excludedProviders)
            QS->>Gate: Filter carriers verified for temperature control (if coldChain)
            QS->>DP: Parallel Carrier Quote Requests
            DP-->>QS: Fare, ETA, Serviceability
            QS->>DB: INSERT INTO delivery_quote (all answers, including declines)
            QS->>QS: DeliverySelection.select(lowest cost meeting ETA)
            QS-->>DS: Outcome(selectedQuote, allQuotes)

            DS->>BS: book(delivery, excludedProviders, "BOOKING")
            BS->>DP: adapter.book(BookingRequest)
            DP-->>BS: BookingResponse (providerDeliveryId, trackingUrl, fare)
            BS->>DB: UPDATE delivery SET status=PROVIDER_SELECTED, provider_delivery_id=?, assignment_deadline=?
            BS->>DB: INSERT INTO delivery_provider_attempt (status=BOOKED)
            BS->>DB: INSERT INTO delivery_ledger (entry_type=BOOKED, amount=fare)
            BS->>WF: EventListener onDeliveryBooked -> schedule one-shot assignment timer (Bike: 3m, Truck: 12m)
            DS-->>DDL: Delivery Booked
        end
    end
```

---

## 2. Assignment SLA Enforcement: In-Memory Timer & Persistent Database Sweep
**Trigger**: Carrier fails to assign a driver within the SLA timeout window (3m for two-wheelers, 12m for trucks/three-wheelers). Handled via both an in-memory timer and a 30-second persistent database sweep ([`DeliveryAssignmentSweepJob`](../../../src/main/java/com/costonomy/mp/delivery/service/DeliveryAssignmentSweepJob.java)) to ensure resilience against server restarts.

```mermaid
sequenceDiagram
    autonumber
    participant Timer as In-Memory TaskScheduler / DB Sweep Job
    participant WF as DeliveryWaterfallService
    participant DS as DeliveryService
    participant QS as DeliveryQuotingService
    participant BS as DeliveryBookingService
    participant OldCarrier as Failed 3PL Carrier
    participant NextCarrier as Next Best 3PL Carrier
    participant DB as MySQL

    alt Path A: In-Memory Timer Fires
        Timer->>WF: onDeliveryBooked timer expires (assignmentDeadline)
    else Path B: Persistent DB Sweep (Every 30s via ShedLock)
        Timer->>WF: findByStatusAndAssignmentDeadlineBefore(PROVIDER_SELECTED, NOW())
    end

    WF->>DB: SELECT * FROM delivery WHERE id = ? FOR UPDATE
    WF->>WF: Verify status is still PROVIDER_SELECTED & deadline elapsed
    WF->>DS: reassign(null, deliveryId, "Unassigned driver timeout (waterfall cascade)")
    
    DS->>OldCarrier: Stand down / cancel previous consignment
    DS->>DB: Record attempt outcome=CANCELLED in delivery_provider_attempt
    
    DS->>QS: usableQuotes(deliveryId, excludedProviders=[OldCarrier])
    alt Usable Quote Exists from Initial Waterfall
        DS->>BS: book(delivery, excludedProviders, "REASSIGNMENT")
    else Quotes Expired or None Left
        DS->>QS: gather(delivery, exclude OldCarrier)
        DS->>BS: book(delivery, excludedProviders, "REASSIGNMENT")
    end

    BS->>NextCarrier: Book consignment
    NextCarrier-->>BS: 200 OK (new providerDeliveryId)
    BS->>DB: UPDATE delivery SET attempt_count = attempt_count + 1, provider_delivery_id = ?
    Note over BS,DB: Single Delivery Identity! Delivery ID never changes.
```

---

## 3. Real-Time Telemetry & Monotonic Webhook Ingestion
**Trigger**: Incoming webhook from carrier (e.g., Pidge, Borzo) with driver updates, pickup, in-transit telemetry, or delivery confirmation.

```mermaid
sequenceDiagram
    autonumber
    participant Carrier as Carrier Webhook (Pidge / Borzo)
    participant DWC as DeliveryWebhookController
    participant DES as DeliveryEventService
    participant DOB as DeliveryOrderBridge
    participant DB as MySQL (delivery, delivery_event, delivery_location)
    participant WS as WebSocket Broker (Restaurant & Supplier)

    Carrier->>DWC: POST /api/v1/webhooks/delivery/pidge (X-Pidge-Signature - the only delivery webhook route)
    DWC->>DWC: Verify HMAC-SHA256 signature
    DWC->>DES: apply(delivery, providerEvent)
    
    DES->>DB: INSERT INTO delivery_event (provider_event_id, occurred_at)
    alt Duplicate provider_event_id (Unique Constraint Violation)
        DES-->>DWC: Return DUPLICATE (Replay Protection -> HTTP 200)
    else New Event
        DES->>DES: Check Monotonicity: Is event moving backwards in status rank?
        alt Backwards Event (e.g. DRIVER_ASSIGNED after PICKED_UP)
            DES->>DB: UPDATE delivery_event SET disposition = 'OUT_OF_ORDER'
            DES-->>DWC: Return OUT_OF_ORDER (Safely stored, UI not regressed)
        else Valid Progress Transition
            DES->>DB: UPDATE delivery SET status = newStatus, driver details
            alt Event Contains Driver Lat/Lng Location
                DES->>DB: INSERT INTO delivery_location (latitude, longitude, bearing)
            end
            
            opt Status == PICKED_UP
                DES->>DOB: onDeliveryStatus(delivery, PICKED_UP)
                DOB->>DB: UPDATE supplier_order SET status = 'OUT_FOR_DELIVERY'
            end
            
            opt Status == DELIVERED
                DES->>DOB: onDeliveryStatus(delivery, DELIVERED)
                DOB->>DB: UPDATE supplier_order SET status = 'DELIVERED'
            end
            
            DES->>WS: Broadcast live tracking update to mobile apps
            DES-->>DWC: Return APPLIED (HTTP 200)
        end
    end
```

---

## 4. Automatic Driver Stand-Down & Reassignment
**Trigger**: Driver cancels after assignment (`DRIVER_CANCELLED`) or pickup fails (`PICKUP_FAILED`). Handled event-driven via [`DeliveryReassignListener`](../../../src/main/java/com/costonomy/mp/delivery/service/DeliveryReassignListener.java).

```mermaid
sequenceDiagram
    autonumber
    participant Carrier as Carrier Webhook
    participant DES as DeliveryEventService
    participant EventPub as ApplicationEventPublisher
    participant DRL as DeliveryReassignListener
    participant DS as DeliveryService
    participant BS as DeliveryBookingService
    participant DB as MySQL

    Carrier->>DES: Webhook: Driver Cancelled / Pickup Failed
    DES->>DB: UPDATE delivery SET status = DRIVER_CANCELLED / PICKUP_FAILED
    DES->>EventPub: publishEvent(DeliveryProviderStoodDownEvent)
    
    Note over DRL: Asynchronous execution after transaction commit
    EventPub->>DRL: onProviderStoodDown(event)
    DRL->>DS: reassignAfterProviderFailure(deliveryId, reason)
    DS->>DB: SELECT * FROM delivery WHERE id = ? FOR UPDATE
    DS->>Carrier: Stand down / release failed carrier
    DS->>BS: book(delivery, excludedProviders=[FailedCarrier], "AUTO_REASSIGN")
    BS->>DB: Record new attempt & update delivery row
```

---

## 5. "No Partner Found" 30-Minute Recovery & Switch to Supplier Own (D-185)
**Trigger**: All carriers fail quoting or decline booking.

```mermaid
sequenceDiagram
    autonumber
    participant DS as DeliveryService
    participant RetryJob as DeliveryRetryJobs (Runs every 5 mins)
    participant Supplier as Supplier App
    participant DB as MySQL

    DS->>DB: UPDATE delivery SET status=QUOTE_FAILED, no_partner_since=NOW()
    DS->>Supplier: Push Notification: "No delivery partner found. You can deliver it yourself."

    loop For 30 Minutes
        RetryJob->>DS: retryNoPartner(deliveryId)
        DS->>DS: Attempt quoteAndBook() against all carriers
        opt Partner Found
            DS->>DB: UPDATE delivery SET status=PROVIDER_SELECTED
            Note over DS: Resumes normal 3PL fulfillment
        end
    end

    alt Supplier Accepts Self-Delivery Option
        Supplier->>DS: POST /api/v1/deliveries/{id}/switch-to-own
        DS->>DB: UPDATE delivery SET mode = 'SUPPLIER_OWN', status = 'DRIVER_ASSIGNED'
        DS->>DB: UPDATE supplier_order SET delivery_mode = 'SUPPLIER_DELIVERY'
        Note over DS: Buyer fee is strictly preserved (No silent repricing, §23A.16)
    end
```

---

## 6. Supplier Own Delivery Milestone Reporting
**Trigger**: Supplier reports milestone on an order where `DeliveryMode == SUPPLIER_OWN`. Guardrail §23A.38 forbids suppliers from calling this endpoint for `COSTONOMY` deliveries.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier App
    participant DC as DeliveryController
    participant DS as DeliveryService
    participant DOB as DeliveryOrderBridge
    participant DB as MySQL

    Supplier->>DC: POST /api/v1/deliveries/{id}/dispatched, then /delivered (SUPPLIER_OWN only)
    DC->>DS: supplierReports(actorId, deliveryId, target)
    DS->>DB: SELECT * FROM delivery WHERE id = ?
    alt delivery.mode != SUPPLIER_OWN
        DS-->>DC: 403 Forbidden ("A delivery partner is carrying this order, progress comes from them")
    else delivery.mode == SUPPLIER_OWN
        DS->>DB: UPDATE delivery SET status = target, timestamp = NOW()
        DS->>DOB: onDeliveryStatus(delivery, target)
        DOB->>DB: UPDATE supplier_order SET status = (PICKED_UP ? 'OUT_FOR_DELIVERY' : 'DELIVERED')
        DS-->>DC: DeliveryResponseDto
        DC-->>Supplier: 200 OK
    end
```

---

## 7. Kitchen Radar Urgency Classification
**Endpoint**: `GET /api/v1/outlets/{outletId}/deliveries/radar`

```mermaid
sequenceDiagram
    autonumber
    actor Kitchen as Restaurant Kitchen App
    participant DC as DeliveryController
    participant ODR as OutletDeliveryRadarService
    participant DB as MySQL

    Kitchen->>DC: GET /api/v1/outlets/{outletId}/deliveries/radar
    DC->>ODR: buildRadar(outletId, actorId)
    ODR->>DB: SELECT * FROM delivery d JOIN supplier_order o ON d.supplier_order_id = o.id WHERE o.outlet_id = ? AND d.status NOT IN ('DELIVERED', 'CANCELLED')
    loop For Each Active Delivery
        ODR->>ODR: Check location freshness (isStale = age > 60s)
        ODR->>ODR: Calculate minutesOverdue against estimatedArrivalAt
        ODR->>ODR: Categorize ScheduleStatus (ON_SCHEDULE / RUNNING_LATE / CRITICALLY_DELAYED)
        ODR->>ODR: Assign KitchenAction (e.g. MEET_DRIVER / CALL_DRIVER / ESCALATE, see OutletDeliveryRadarService)
    end
    ODR-->>DC: RadarSummaryResponse (summaryCounts, activeItems)
    DC-->>Kitchen: 200 OK
```

---

## 8. Food Delivery (B2C) Parallel Flow (D-201, D-204)
**Endpoints**: `POST /api/v1/outlets/{outletId}/food-deliveries`, `POST .../{id}/pay`, `POST .../{id}/confirm`, `POST .../{id}/cancel`, `GET .../{id}`, `GET .../{id}/timeline`, `GET .../{id}/financials`, and unauthenticated `GET /api/v1/public/food-deliveries/{trackingToken}`.
**Not built**: the 4-digit handover OTP check (hash fields exist on `FoodDelivery`; no verify method) and push/realtime events for food (deferred by decision D-205). **Operator exit**: `POST /api/v1/admin/food-deliveries/{id}/resolve` (cancel and refund, mark delivered, clear the flag; `DELIVERY_OPERATE`).

### 8a. Create, hold the fee, book (wallet path)

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant App/API
    participant FDC as FoodDeliveryController
    participant FBS as FoodDeliveryBookingService
    participant W as WalletService
    participant DP as Courier adapter - Mock by default, real ones only if enabled
    participant DB as MySQL (food_delivery and children)

    Restaurant->>FDC: POST /outlets/{outletId}/food-deliveries (Idempotency-Key, paymentMethod=WALLET)
    FDC->>FBS: create(...)
    FBS->>DB: insert REQUESTED (own transaction)
    FBS->>DP: quote couriers (no transaction open)
    alt no priced quote, or max fee below cheapest, or wallet short
        FBS->>DB: delete the row and its children
        FBS-->>Restaurant: 4xx DELIVERY_UNAVAILABLE / VALIDATION_ERROR (nothing debited)
    else
        FBS->>W: debit max fee (food-delivery-{id}) and move to QUOTED, one transaction
        loop cheapest meeting ETA first
            FBS->>DB: reserve PENDING attempt (own transaction)
            FBS->>DP: book (no transaction open)
            FBS->>DB: record answer: PROVIDER_SELECTED, fee = booked price
        end
        FBS-->>Restaurant: 201 (status, trackingToken)
    end
```

### 8b. Gateway-paid fee

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant
    participant FBS as FoodDeliveryBookingService
    participant PP as PaymentProvider - Razorpay if PAYMENT_PROVIDER=RAZORPAY, else Mock
    participant WH as POST /webhooks/razorpay
    participant SW as payment sweep (60 s)
    participant GR as GatewayRefundService
    participant DB as MySQL

    Restaurant->>FBS: create (paymentMethod=GATEWAY)
    FBS->>DB: insert REQUESTED
    FBS->>PP: createAuthorization(max fee, autoCapture) (no transaction)
    FBS->>DB: save provider order id
    FBS-->>Restaurant: checkout details (no courier asked yet)
    par three ways the payment is noticed
        Restaurant->>FBS: POST .../confirm (checkout signature, fetchPayment CAPTURED)
    and
        WH->>FBS: reconcileWebhookPayment (no order Payment row matches)
    and
        SW->>FBS: sweepStaleGatewayPayments (checkout older than 30 s)
    end
    FBS->>DB: settleGatewayPayment: lock row, paidAt null, REQUESTED, amount == max fee, move to QUOTED, book
    alt cancelled, expired, duplicate or wrong amount
        FBS->>DB: record gateway_refund UNUSABLE_PAYMENT
        FBS->>GR: send after commit (claim, lease, provider refund, lookup if unanswered)
    end
    SW->>FBS: unpaid for 30 min: cancel
```

### 8c. Courier events, public tracking, pickup settlement, cancel

```mermaid
sequenceDiagram
    autonumber
    participant DP as Courier
    participant PW as PidgeWebhookService
    participant UH as FoodDeliveryUnclaimedEventHandler
    participant POLL as FoodDeliveryReconciliationJob (1 min)
    participant FES as FoodDeliveryEventService
    participant FBS as FoodDeliveryBookingService
    participant DB as MySQL
    actor Customer as End customer (browser)

    DP->>PW: POST /webhooks/delivery/pidge
    PW->>UH: no supplier delivery matches, offer to food handler
    UH->>FES: apply(event)
    POLL->>DP: status and location (claimed, bounded batch)
    POLL->>FES: apply(synthetic event id, deduplicated)
    FES->>DB: store event (unique provider+event id): APPLIED / DUPLICATE / OUT_OF_ORDER / IGNORED
    FES->>DB: advance status under row lock (REQUIRES_NEW)
    alt DRIVER_CANCELLED or PICKUP_FAILED before pickup
        FES->>FBS: rebook (outside the lock), old consignment cancelled, failure schedules a recovery task
    else PICKED_UP or later
        FES->>FBS: settleFee: return max fee minus fee to wallet, or partial refund to gateway
    end
    Customer->>DB: GET /public/food-deliveries/{token} (status, ETA, rider first name, position - no fee, phone or provider)
```

Cancel before pickup refunds the whole held fee (wallet credit `food-delivery-{id}-refund`, or a gateway refund when paid at the gateway); cancel after pickup is refused; a failed delivery after pickup keeps the courier fee, and so does a provider `CANCELLED` after pickup (the delivery becomes `FAILED`, is flagged for an operator, and the courier stays owed). A provider `CANCELLED` or `DELIVERY_FAILED` before pickup stands the courier down and rebooks.
