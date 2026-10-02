# Architecture Sequence Diagrams: Delivery & Logistics Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Delivery & Logistics** module.

---

## 1. `POST /api/v1/supplier-orders/{orderId}/delivery`
**Description**: Initiates a 3PL delivery dispatch request (e.g., Pidge, Borzo) or self-delivery booking for a supplier order.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Client
    participant DC as DeliveryController
    participant DS as DeliveryService
    participant DP as DeliveryProvider (Pidge/Borzo)
    participant DB as PostgreSQL (deliveries, delivery_provider_attempts)
    participant EXT as External Carrier API

    Supplier->>DC: POST /api/v1/supplier-orders/{orderId}/delivery (vehicleType, notes)
    DC->>DS: requestDelivery(orderId, requestDto, principal)
    DS->>DB: SELECT * FROM supplier_order WHERE id = orderId
    DS->>DB: Check existing active delivery
    alt Active delivery already exists
        DS-->>DC: 409 Conflict (Delivery already in progress)
        DC-->>Supplier: Error response
    else Valid for booking
        DS->>DB: INSERT INTO delivery (status=PENDING, vehicle_type, pickup_addr, drop_addr)
        DS->>DP: createDeliveryOrder(quoteId, pickup, drop, contactInfo)
        DP->>EXT: POST /orders (Carrier API)
        alt Carrier Booking Success
            EXT-->>DP: 200 OK (carrier_trip_id, tracking_url)
            DP-->>DS: DeliveryBookingResult(tripId, trackingUrl)
            DS->>DB: UPDATE delivery SET status = DRIVER_ASSIGNED, tracking_url = ...
            DS->>DB: INSERT INTO delivery_event (kind=BOOKED)
            DS-->>DC: DeliveryResponseDto
            DC-->>Supplier: 201 Created (Delivery Details)
        else Carrier Booking Failure (Waterfall Fallback)
            EXT-->>DP: 4xx/5xx / Timeout
            DP-->>DS: CarrierFailureException
            DS->>DB: Record attempt failure in delivery_provider_attempt
            DS->>DS: triggerCarrierWaterfall(nextCarrier)
            DS-->>DC: 202 Accepted (Queued for alternative carrier)
            DC-->>Supplier: 202 Accepted
        end
    end
```

---

## 2. `GET /api/v1/deliveries/{id}` & `GET /api/v1/deliveries/{id}/events`
**Description**: Fetches live tracking status, driver location, and audit timeline for a delivery.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant / Supplier Client
    participant DC as DeliveryController
    participant DS as DeliveryService
    participant DES as DeliveryEventService
    participant DB as PostgreSQL

    Client->>DC: GET /api/v1/deliveries/{id}
    DC->>DS: getDelivery(id, principal)
    DS->>DB: SELECT * FROM delivery WHERE id = id
    DS-->>DC: DeliveryDetailDto (status, driverName, eta, trackingUrl)
    DC-->>Client: 200 OK

    Client->>DC: GET /api/v1/deliveries/{id}/events
    DC->>DES: getEventsForDelivery(id)
    DES->>DB: SELECT * FROM delivery_event WHERE delivery_id = id ORDER BY created_at ASC
    DES-->>DC: List of DeliveryEventDto
    DC-->>Client: 200 OK
```

---

## 3. `POST /api/v1/deliveries/{id}/dispatched` & `POST /api/v1/deliveries/{id}/delivered`
**Description**: Lifecycle status transitions triggered by driver or supplier when goods leave the dock or are delivered.

```mermaid
sequenceDiagram
    autonumber
    actor User as Supplier / Driver
    participant DC as DeliveryController
    participant DS as DeliveryService
    participant ORS as OrderReleaseService
    participant PJ as PaymentJobs
    participant DB as PostgreSQL

    User->>DC: POST /api/v1/deliveries/{id}/dispatched
    DC->>DS: markDispatched(id, principal)
    DS->>DB: UPDATE delivery SET status = OUT_FOR_DELIVERY
    DS->>ORS: onOrderDispatched(orderId)
    ORS->>DB: UPDATE supplier_order SET status = OUT_FOR_DELIVERY
    Note over ORS,PJ: Capture payment hold upon dispatch
    ORS->>PJ: triggerDeferredCapture(orderId)
    DS-->>DC: Updated DeliveryDto
    DC-->>User: 200 OK

    User->>DC: POST /api/v1/deliveries/{id}/delivered
    DC->>DS: markDelivered(id, principal)
    DS->>DB: UPDATE delivery SET status = DELIVERED, delivered_at = NOW()
    DS->>DB: UPDATE supplier_order SET status = DELIVERED
    DS-->>DC: Updated DeliveryDto
    DC-->>User: 200 OK
```

---

## 4. `POST /api/v1/webhooks/delivery/pidge`
**Description**: Ingests live tracking updates and status webhooks from Pidge carrier with HMAC signature verification.

```mermaid
sequenceDiagram
    autonumber
    participant Pidge as Pidge Logistics Webhook
    participant DWC as DeliveryWebhookController
    participant PWS as PidgeWebhookService
    participant DS as DeliveryService
    participant DB as PostgreSQL

    Pidge->>DWC: POST /api/v1/webhooks/delivery/pidge (Headers: X-Pidge-Signature)
    DWC->>PWS: processWebhook(payload, signature)
    PWS->>PWS: verifyHmacSha256(payload, signature, secret)
    alt Invalid Signature
        PWS-->>DWC: InvalidSignatureException
        DWC-->>Pidge: 401 Unauthorized
    else Valid Signature
        DWC-->>Pidge: 200 OK (Quick ACK)
        PWS->>DS: applyProviderStatusUpdate(tripId, pidgeStatus, driverInfo, location)
        DS->>DB: UPDATE delivery SET status = mappedStatus, driver_name = ..., driver_phone = ...
        DS->>DB: INSERT INTO delivery_event (kind=STATUS_UPDATE, details=...)
    end
```

---

## 5. `GET /api/v1/outlets/{outletId}/deliveries/radar`
**Description**: Real-time kitchen radar endpoint ranking incoming deliveries by arrival urgency and identifying late deliveries.

```mermaid
sequenceDiagram
    autonumber
    actor Kitchen as Restaurant Kitchen Dashboard
    participant DC as DeliveryController
    participant ODR as OutletDeliveryRadarService
    participant DB as PostgreSQL (deliveries, supplier_order)

    Kitchen->>DC: GET /api/v1/outlets/{outletId}/deliveries/radar
    DC->>ODR: buildRadar(outletId, principal)
    ODR->>DB: SELECT * FROM delivery d JOIN supplier_order o ON d.order_id = o.id WHERE o.outlet_id = outletId AND d.status NOT IN ('DELIVERED', 'CANCELLED')
    ODR->>ODR: Compute minutesOverdue, ScheduleStatus (ON_SCHEDULE / RUNNING_LATE / CRITICALLY_DELAYED)
    ODR->>ODR: Classify KitchenAction (AT_DOOR, MEET_DRIVER, CALL_DRIVER, ESCALATE)
    ODR->>ODR: Aggregate RadarSummaryResponse (atDoorCount, delayedCount, activeCount)
    ODR-->>DC: RadarResponseDto
    DC-->>Kitchen: 200 OK
```

---

## 6. `GET /api/v1/admin/deliveries/late` & `POST /api/v1/admin/deliveries/{id}/force-waterfall`
**Description**: Operations console monitoring for late deliveries and manual carrier escalation trigger.

```mermaid
sequenceDiagram
    autonumber
    actor Ops as Operations Admin
    participant ADC as AdminDeliveryController
    participant ADS as AdminDeliveryService
    participant DS as DeliveryService
    participant DB as PostgreSQL

    Ops->>ADC: GET /api/v1/admin/deliveries/late?page=0&size=20
    ADC->>ADS: findLateDeliveries(pageable)
    ADS->>DB: SELECT * FROM delivery WHERE estimated_arrival_at < NOW() AND status IN ('PENDING', 'DRIVER_ASSIGNED', 'OUT_FOR_DELIVERY') ORDER BY (NOW() - estimated_arrival_at) DESC
    ADS-->>ADC: PagedResponse of LateDeliveryDto
    ADC-->>Ops: 200 OK

    Ops->>ADC: POST /api/v1/admin/deliveries/{id}/force-waterfall (reason)
    ADC->>DS: forceCarrierWaterfall(deliveryId, reason)
    DS->>DB: UPDATE delivery SET carrier_attempt_count = carrier_attempt_count + 1
    DS->>DS: cancelCurrentCarrierAttempt()
    DS->>DS: dispatchToNextWaterfallCarrier()
    DS-->>ADC: WaterfallTriggeredResult
    ADC-->>Ops: 200 OK
```
