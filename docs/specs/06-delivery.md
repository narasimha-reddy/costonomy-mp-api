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

Automatic (D-197 addendum). When the provider, not Mandi, lets go of a consignment before pickup, the delivery is handed
to the next courier without anyone pressing reassign:
- the rider cancelling (`DRIVER_CANCELLED`), a failed pickup (`PICKUP_FAILED`), or the provider cancelling the whole
  order before pickup (read as `DRIVER_CANCELLED`, not as terminal `CANCELLED`, because that courier will not carry it);
- the old consignment is cancelled at the provider so two couriers cannot arrive, and couriers already tried are skipped;
- it happens only if the order is still `READY_FOR_PICKUP` and the delivery is a Costonomy delivery;
- a cancellation Mandi made itself (supplier or restaurant cancelling) books nothing, and nothing is booked after pickup;
- if no other courier can take it, the delivery moves to `PROVIDER_UNAVAILABLE` and the no-partner retry applies;
- a sweep finds any delivery still sitting in these states, so a crash between recording and booking cannot strand it.
Switch: `costonomy.mp.delivery.auto-reassign.enabled`.

### Pickup failure

Record failure reason and retry according to policy. Handled as driver cancellation above.

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

### Event-Driven Waterfall Fallback & Assignment Sweeps
- Strict SLA: 3-minute assignment deadline for two-wheelers (`TWO_WHEELER`), 12-minute assignment deadline for three-wheelers and trucks (`THREE_WHEELER`, `FOUR_WHEELER_TRUCK`), recorded in `assignment_deadline`.
- Resilient Enforcement: Scheduled one-shot timers (`DeliveryWaterfallService.onDeliveryBooked`) are backed by a persistent database sweep job (`DeliveryAssignmentSweepJob`, ShedLock `delivery-assignment-sweep` running every 30s) so node restarts never strand unassigned consignments.
- Event-Driven Reassignment: When a deadline expires or carrier signals unserviceability, `DeliveryWaterfallService` cascades consignment to the next best carrier without polling loops.
- Automatic Driver Stand-Down Reassignment: When a driver cancels (`DRIVER_CANCELLED`) or pickup fails (`PICKUP_FAILED`), `DeliveryEventService` publishes a `DeliveryProviderStoodDownEvent`. `DeliveryReassignListener` automatically stands down the previous provider and immediately books the next best carrier without manual intervention.
- Stale Carrier Exclusion: Couriers already attempted for the delivery are excluded from candidate re-quoting to prevent endless fallback loops.
- Durable timeout: the deadline is also read from the database every 30 seconds (`DeliveryAssignmentSweepJob`, after a
  30 second grace), so a restart that loses the in-memory timer cannot leave a booking without a driver. Switch:
  `costonomy.mp.delivery.assignment-sweep.enabled`.

## 15. Central Delivery Ledger

Append-only financial audit table `delivery_ledger` tracks:
- Carrier quoted vs booked amounts.
- Margin accounting and drawdowns.
- Auditable by operations via `GET /api/v1/admin/deliveries/{id}/ledger` (`DELIVERY_INSPECT` permission).
- Manual waterfall override via `POST /api/v1/admin/deliveries/{id}/force-waterfall` (`DELIVERY_OPERATE` permission).

## 16. Food delivery (restaurant to customer)

> **Status: in progress. The booking side is built; the courier-event side and the API are not.**
> **Built and tested:** schema (V88, V91), domain and repositories, creating a delivery (validation, idempotency), quoting
> every food-capable courier, holding the fee, booking with fallback in the order of §17, the 5-minute retry window and
> ending `FAILED` (NO_COURIER) with a full refund, cancelling before pickup with a full refund, settling the unused part
> of the hold after pickup, and handing a booking that never got a rider (or whose rider let go) to the next courier.
> **Not built:** ingesting courier events (webhook and polling for food consignments, which is what moves a delivery
> past `PROVIDER_SELECTED` and calls settle and the stand-down rebook), the restaurant API, the public tracking
> endpoint, the handover code, and restaurant and customer notifications. Everything under "Public tracking",
> "Handover" and "API" is the design, not a behaviour.
> **Assumption pending product:** the fee model below (a maximum held up front) is the recommended design and is built
> behind a few wallet methods; if product chooses another it is a contained change.

### Scope
- **Created by** the restaurant's own system (POS or website) through the API, or by outlet staff in the Mandi app.
  Mandi has no customer ordering flow; the customer appears only as a drop-off contact.
- **Fee** is paid by the restaurant from its wallet. **No cash on delivery**: the courier carries food, not money.
- **Not in scope**: customer accounts, menus, food orders, payments from the customer.

### Model
One `food_delivery` per request, for its whole life (the §7 rule applies unchanged: a failed courier appends an
attempt and an event, never a second row). Tables `food_delivery`, `_quote`, `_attempt`, `_event`, `_location`
mirror §5's. The supplier `delivery` tables are not changed.

### Providers and fallback
Only providers with `delivery_provider.supports_food_delivery = 1` are asked. No single provider is required: the
quote is gathered from all eligible ones, the best meeting the ETA ceiling is booked, and if none takes it within the
3-minute assignment deadline (§14) the waterfall moves to the next, excluding the one that failed. If every eligible
provider fails the delivery ends `PROVIDER_UNAVAILABLE`, the fee is not taken, and the restaurant is told at once.

### When no courier will take it
Food does not wait the way raw material does. If every eligible provider refuses or times out, the delivery is
retried against all of them for **5 minutes** (not the 30 minutes and "deliver it yourself" offer of §7, which suit
a supplier's pallet and not a hot meal). If nobody has taken it by then it ends `FAILED` with `failure_code =
NO_COURIER`, the fee hold is refunded in full, and the restaurant is notified so it can call the customer or book
another way. A timed-out booking is found by a database sweep on `assignment_deadline`, not by an in-memory timer, so
a restart cannot strand it.

### Food rules
- Vehicle is `TWO_WHEELER` unless the weight says otherwise; cold-chain does not apply.
- `max_delivery_minutes` bounds the quote: a provider whose ETA exceeds it is not eligible.
- The fee: the restaurant agrees a maximum (or defaults to 1.5 x the cheapest courier, rounded up to a rupee). That
  amount is **held from its wallet before any courier is asked**, and only couriers quoting at or below it are asked. It
  stays held until pickup, so a dearer replacement courier needs no second debit; at pickup the unused part (maximum less
  the courier's price) is returned. Cancelling before pickup, or no courier within the retry window, returns all of it.
  Every refund is idempotent on its wallet reference. A request whose maximum is below the cheapest courier, or whose
  wallet cannot hold it, is refused with the reason and nothing is created or debited.
- Cancelling before pickup refunds the fee; after pickup it does not.

### Status
`REQUESTED → QUOTED → PROVIDER_SELECTED → DRIVER_ASSIGNED → AT_PICKUP → PICKED_UP → OUT_FOR_DELIVERY → ARRIVED →
DELIVERED`, with `QUOTE_FAILED`, `PROVIDER_UNAVAILABLE`, `CANCELLED`, `FAILED`, `RETURNED`. Only the courier's
events move a delivery (as §2 for Costonomy delivery).

### Public tracking
`GET /api/v1/public/food-deliveries/{trackingToken}` needs no login. The token is 32 random characters, expires 24
hours after delivery, and the endpoint is rate-limited. It returns status, ETA, rider first name and position, and
the restaurant outlet name. It never returns the fee, the provider, or the customer's phone number, and it never
accepts a numeric id.

### Handover
A 4-digit code is sent to the customer; the rider enters it at handover. Only its hash is stored, and after 5 wrong
tries it is locked and the restaurant is told.

### API (restaurant)
`POST /food-deliveries/quote`, `POST /food-deliveries` (requires `Idempotency-Key`), `GET /food-deliveries/{id}`,
`GET /food-deliveries` (outlet-scoped), `POST /food-deliveries/{id}/cancel`.

## 17. Courier reliability and the decision record

D-197 addendum. When a courier does not accept or cannot fulfil, the next one asked should be a reliable one, and a
courier that is failing should stop being asked. Applied to food delivery first; supplier delivery still asks couriers in
price order and only records the decision (below).

### What reliability is
A score from 0 to 1 per courier, a weighted blend of five rates, each smoothed toward a prior so a new courier is
neither trusted fully nor written off on two orders (`(successes + prior x k) / (total + k)`):

| Part | Weight | Measures | Window |
|---|---|---|---|
| Assignment | 0.40 | Booked attempts that ended with a rider who stayed | recent attempts |
| Acceptance | 0.20 | Bookings the courier did not refuse | recent attempts |
| Quote answer | 0.10 | Quotes that did not fail (an "unserviceable" answer is not a failure) | recent quotes |
| After assignment | 0.15 | Bookings without a rider cancellation or failed pickup | nightly stats, 7 days |
| Delivery | 0.15 | Deliveries completed and on time | nightly stats, 7 days |

"Recent" is the last two hours, at most 30 attempts per courier, across supplier and food delivery together. An attempt
counts only once it is old enough (5 minutes) that a missing rider is the courier's fault. The first three parts come
from attempts and quotes directly, so they see the failures the nightly stats cannot: the stats begin at
`DRIVER_ASSIGNED`, and these failures happen before it.

### Circuit breaker
Stateless, read off recent attempts each time. **Open** when at least 5 attempts have resolved and fewer than half ended
with a rider who stayed: the courier is skipped. After 10 quiet minutes it is **half open** and one attempt is let
through, asked last so a failed probe costs least. If everyone is open, everyone is asked anyway, best first: a late
delivery beats none.

### Order of asking (`ReliabilityRanking`)
- First attempt: the cheapest courier that meets the delivery's deadline, skipping open breakers.
- After a failure: couriers priced within 1.5 x the cheapest remaining are ranked by reliability, the rest follow by price.
  Nothing above the fee the restaurant agreed to is ever asked. A courier already tried is never asked again.
- `FoodCourierSelector` applies this to a food delivery's quotes and returns, for each courier, what to record.

### The decision record
Written on every attempt and quote (V89), both flows, going forward only; NULL means "not recorded", never "fine":
`ended_cause` (PROVIDER_REFUSED, PROVIDER_TIMEOUT, ASSIGNMENT_TIMEOUT, RIDER_CANCELLED, PROVIDER_CANCELLED,
PICKUP_FAILED, DELIVERY_FAILED, CUSTOMER_CANCEL, OPS_REASSIGN, SWITCHED_TO_OWN, COMPLETED), `retryable`, `assigned_at`,
`call_ms`, `rank_position`, `selection_reason` (CHEAPEST, RELIABILITY, PROBE, ONLY_OPTION), `reliability_score`,
`breaker_state`, and `decision` (every courier considered and every one skipped). Quotes record `call_ms`,
`error_class` (TIMEOUT, TRANSIENT, REFUSED, UNSERVICEABLE) and `retryable`; food quotes also record the vehicle.

Supplier delivery fills the cause, timing, rank, reason and candidate list, but not the score or breaker state, because
it does not compute them yet.

Configuration: `costonomy.mp.delivery.reliability.*`.
