# Architecture Sequence Diagrams: Delivery & Logistics Module

This document details the exact runtime request/response sequence diagrams for all flows in the **Delivery & Logistics** module, reflecting the current production implementation (including multi-carrier waterfall, persistent assignment sweeps, and D-201 food delivery).

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
    participant DB as PostgreSQL (deliveries, quotes, attempts, ledger)

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
**Trigger**: Carrier fails to assign a driver within the SLA timeout window (3m for two-wheelers, 12m for trucks/three-wheelers). Handled via both an in-memory timer and a 30-second persistent database sweep ([`DeliveryAssignmentSweepJob`](file:///Users/rac/Documents/costonomy_projects/marketplace_be/costonomy-mp-api/src/main/java/com/costonomy/mp/delivery/service/DeliveryAssignmentSweepJob.java)) to ensure resilience against server restarts.

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
    participant DB as PostgreSQL

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
    participant DB as PostgreSQL (delivery, delivery_event, delivery_location)
    participant WS as WebSocket Broker (Restaurant & Supplier)

    Carrier->>DWC: POST /api/v1/webhooks/delivery/{provider} (Signature Headers)
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
**Trigger**: Driver cancels after assignment (`DRIVER_CANCELLED`) or pickup fails (`PICKUP_FAILED`). Handled event-driven via [`DeliveryReassignListener`](file:///Users/rac/Documents/costonomy_projects/marketplace_be/costonomy-mp-api/src/main/java/com/costonomy/mp/delivery/service/DeliveryReassignListener.java).

```mermaid
sequenceDiagram
    autonumber
    participant Carrier as Carrier Webhook
    participant DES as DeliveryEventService
    participant EventPub as ApplicationEventPublisher
    participant DRL as DeliveryReassignListener
    participant DS as DeliveryService
    participant BS as DeliveryBookingService
    participant DB as PostgreSQL

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
    participant DB as PostgreSQL

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
    participant DB as PostgreSQL

    Supplier->>DC: POST /api/v1/deliveries/{id}/supplier-status (target: PICKED_UP or DELIVERED)
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
    participant DB as PostgreSQL

    Kitchen->>DC: GET /api/v1/outlets/{outletId}/deliveries/radar
    DC->>ODR: buildRadar(outletId, actorId)
    ODR->>DB: SELECT * FROM delivery d JOIN supplier_order o ON d.supplier_order_id = o.id WHERE o.outlet_id = ? AND d.status NOT IN ('DELIVERED', 'CANCELLED')
    loop For Each Active Delivery
        ODR->>ODR: Check location freshness (isStale = age > 60s)
        ODR->>ODR: Calculate minutesOverdue against estimatedArrivalAt
        ODR->>ODR: Categorize ScheduleStatus (ON_SCHEDULE / RUNNING_LATE / CRITICALLY_DELAYED)
        ODR->>ODR: Assign KitchenAction (AT_DOOR / MEET_DRIVER / CALL_DRIVER / ESCALATE)
    end
    ODR-->>DC: RadarSummaryResponse (summaryCounts, activeItems)
    DC-->>Kitchen: 200 OK
```

---

## 8. Food Delivery (B2C) Parallel Flow (D-201)
**Endpoints**: `POST /food-deliveries`, `GET /api/v1/public/food-deliveries/{trackingToken}`, and 4-digit handover OTP.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant POS / App
    actor Customer as End Customer (Browser)
    participant FDC as FoodDeliveryController
    participant FDS as FoodDeliveryService
    participant DP as 3PL Carrier (Pidge / Borzo)
    participant DB as PostgreSQL (food_delivery tables)

    Restaurant->>FDC: POST /api/v1/food-deliveries (dropContact, maxMinutes, Idempotency-Key)
    FDC->>FDS: createFoodDelivery(...)
    FDS->>DB: Verify wallet balance & place fee hold
    FDS->>DP: Book fastest eligible courier with supports_food_delivery=true
    DP-->>FDS: Booking confirmed + tracking URL
    FDS->>DB: INSERT INTO food_delivery (tracking_token=random32, otp_hash=hash(otp))
    FDS-->>Restaurant: 201 Created (foodDeliveryId, trackingToken)

    Customer->>FDC: GET /api/v1/public/food-deliveries/{trackingToken} (No Auth Required)
    FDC->>FDS: getPublicTracking(token)
    FDS->>DB: SELECT status, eta, rider_name, lat, lng FROM food_delivery WHERE tracking_token = ?
    Note over FDS: Zero Leakage: Returns rider first name & location only. No fees, phone numbers, or provider names.
    FDS-->>Customer: 200 OK (Sanitized Public Tracking Data)

    DP->>FDC: Courier delivers meal & provides 4-digit customer OTP
    FDC->>FDS: verifyHandoverOtp(id, otp)
    FDS->>DB: Match hash(otp) with stored hash
    alt Valid OTP
        FDS->>DB: UPDATE food_delivery SET status = 'DELIVERED'
        FDS->>DB: Deduct fee from restaurant wallet
    else 5 Consecutive Invalid Attempts
        FDS->>DB: Lock handover & notify restaurant
    end
```
