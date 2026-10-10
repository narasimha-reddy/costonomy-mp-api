# Delivery Architecture & Provider Specification

## 1. Objective

Delivery must be provider-agnostic so supplier own-delivery, third-party providers and future Costonomy fleet can share the same Mandi order experience.

## 2. Delivery modes

### Supplier Own Delivery

- supplier responsible
- restaurant pays ₹0 by default
- no live tracking initially
- Costonomy does not claim operational responsibility
- no provider SLA measurement initially

### Costonomy Delivery

- restaurant pays platform delivery fee
- Mandi orchestrates provider
- third-party provider may be selected
- future proprietary fleet can implement same interface

## 3. Provider interface

Create a backend interface similar to:

```java
interface DeliveryProvider {
    QuoteResponse quote(DeliveryQuoteRequest request);
    BookingResponse book(DeliveryBookingRequest request);
    ProviderDeliveryStatus getStatus(String providerDeliveryId);
    LocationResponse getLocation(String providerDeliveryId);
    CancellationResponse cancel(String providerDeliveryId);
}
```

Provider-specific DTOs must not leak into domain models.

## 4. Provider selection

Initial selection:

**lowest cost provider meeting required ETA and serviceability**

Future scoring can incorporate reliability.

Internal provider quotes are never shown to restaurant.

## 5. Delivery state

`DELIVERY_REQUESTED`
`QUOTE_RECEIVED`
`PROVIDER_SELECTED`
`DRIVER_ASSIGNED`
`DRIVER_AT_PICKUP`
`PICKED_UP`
`IN_TRANSIT`
`ARRIVED_AT_DESTINATION`
`DELIVERED`

Failures:

`QUOTE_FAILED`
`PROVIDER_UNAVAILABLE`
`DRIVER_CANCELLED`
`PICKUP_FAILED`
`DELIVERY_FAILED`
`CANCELLED`

## 6. Booking flow

1. supplier order becomes READY_FOR_PICKUP
2. delivery requested
3. provider quotes gathered
4. serviceability checked
5. best provider selected
6. booking attempted
7. driver assignment received
8. restaurant receives delivery updates
9. pickup evidence
10. movement
11. delivery evidence
12. order becomes DELIVERED

## 7. Failure handling

### Quote failure

Try alternative provider.

### Provider unavailable

Select another provider.

### Driver cancellation

Reassign without creating a second logical delivery.

### Pickup failure

Record failure reason and retry according to policy.

### Delivery failure

Escalate/retry/reassign according to policy.

All attempts remain auditable.

## 8. Tracking

After driver assignment:

- driver location
- restaurant location
- route
- ETA
- timeline
- stale indicator
- driver identity/contact where supported

Delivery response coordinates (both buyer and supplier): `pickupLocation` (supplier store) and `dropLocation` (buyer outlet), each `{"latitude": 17.4399, "longitude": 78.4983}`, or `null` when either coordinate is not stored. They are fixed points, separate from `location` (the rider's latest fix).

Do not fabricate location updates.

If location timestamp exceeds configured freshness threshold, show stale state.

## 9. Realtime

Preferred:

`WebSocket`

Fallback:

`Push notification + polling`

Events include:

- provider selected
- driver assigned
- driver at pickup
- picked up
- location updated
- ETA changed
- reassigned
- delivered
- failure

## 10. Delivery fee

Restaurant sees only final applicable fee.

If supplier offers free own delivery, fee is zero.

For Costonomy delivery, fee is determined by platform policy/provider economics.

Provider bid details are internal.

## 11. Mock provider

Mandatory local/test provider supporting:

- quote
- booking
- assignment
- GPS updates
- ETA changes
- delay
- driver cancellation
- provider failure
- reassignment
- pickup
- delivery
- completion

Developer simulation endpoints must be protected and unavailable to normal users.

## 12. Delivery reconciliation

Persist:

- provider
- provider delivery ID
- quote
- booking request/response metadata
- event ID
- event timestamp
- provider status
- internal status
- location timestamps

Provider webhook/event processing must be idempotent.

## 13. Delivery API security

Provider callbacks must use:

- signature verification where supported
- allow-listing where appropriate
- replay protection
- idempotent event IDs

Never expose provider credentials to mobile.

## 14. Pidge Delivery Orchestration & Smart Dispatch

Pidge serves as a premier delivery orchestration and multi-carrier smart dispatch layer, aggregating 1PL internal fleets alongside external 3PL courier partners (Shadowfax, Porter, Borzo, LoadShare):

### Vehicle Selection & Weight Sizing
Order parcel weight and volume are aggregated automatically from line items (`requested_quantity × SKU unit weight`):
- **TWO_WHEELER**: Payload $\le 20$ kg
- **THREE_WHEELER**: Payload $> 20$ kg and $\le 100$ kg
- **FOUR_WHEELER_TRUCK**: Payload $> 100$ kg (bulk / heavy freight)

### Resilient Ingress & Webhook Security
- Webhook Ingress: `POST /api/v1/webhooks/delivery/pidge`
- Cryptographic Signature: Constant-time HMAC-SHA256 validation via `X-Pidge-Signature` header.
- Ingestion is idempotent: Out-of-order and duplicate provider events are safely ignored.
- Real-time tracking URLs (`tracking_url`) are persisted and forwarded to notification outbox for WhatsApp/SMS sharing.

### Event-Driven Waterfall Fallback
- Strict SLA: 3-minute assignment deadline enforced upon carrier booking (`assignment_deadline`).
- Event-Driven Reassignment: When a deadline expires or carrier signals unserviceability, `DeliveryWaterfallService` cascades consignment to the next best carrier without polling loops.
- Stale Carrier Exclusion: The failed carrier is excluded from the re-quote candidate pool to prevent loops.

## 15. Central Delivery Ledger

Append-only financial audit table `delivery_ledger` tracks:
- Carrier quoted vs booked amounts.
- Margin accounting and drawdowns.
- Auditable by operations via `GET /api/v1/admin/deliveries/{id}/ledger` (`DELIVERY_INSPECT` permission).
- Manual waterfall override via `POST /api/v1/admin/deliveries/{id}/force-waterfall` (`DELIVERY_OPERATE` permission).

