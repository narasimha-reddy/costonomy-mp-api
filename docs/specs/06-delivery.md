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

Automatic (D-201 addendum). When the provider, not Mandi, lets go of a consignment before pickup, the delivery is handed
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

Before pickup, a failed delivery is treated as a pickup failure: the courier is stood down and the next one is booked automatically. After pickup the goods are with the courier, so nothing is rebooked: the delivery stays `DELIVERY_FAILED`, is flagged `reconciliation_required` (tenants see "under review"), the order stays `OUT_FOR_DELIVERY`, a manual reassign is refused, and the courier stays owed. An operator closes it with `POST /api/v1/admin/deliveries/{id}/resolve` (cancel and refund, or mark delivered). A later courier `DELIVERED` supersedes the failure and clears the flag (D-205, D-206).

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

### Supplier-funded Costonomy delivery (D-203)

A supplier may opt in (`fund_costonomy_delivery`, with a `funded_delivery_cap` and a free-delivery threshold, all required together)
to pay for the rider on orders whose goods, before GST, reach the threshold.

- The supplier pays `min(courier fee, cap)` and the restaurant pays the rest, so a quote above the cap is cheaper by the cap but not free.
  The restaurant sees its own price in the delivery quote (`fee`, with `courierFee` and `supplierFunded` beside it) and in the order preview.
- The share is fixed at checkout (`supplier_order.delivery_funded_amount`). It is charged when a courier reports the goods picked up, in
  transit or delivered, once per delivery, as a `DELIVERY_FUNDING` deduction that the settlement takes as a DEBIT adjustment. A credit order,
  which is never settled, is charged to the store's next settlement.
- Nothing is charged for an order cancelled before pickup or one the supplier carries themselves (the amount is cleared when the order
  becomes `SUPPLIER_DELIVERY`).
- Own delivery: a `SELF` answer with no charge named is free once the goods reach the threshold, and stays free only while the order does.

### Journeys and exits (D-204)

| Mode | Entry | Money in | Normal exit | Failure exits |
|---|---|---|---|---|
| Pickup (restaurant collects) | order confirmed | payment held, captured at ready (D-103) | order completes at pickup | cancel before ready releases the hold |
| Supplier delivery (own) | supplier answers `SELF` | as above, plus any charge the supplier named | supplier marks delivered | order cancelled: refund per D-104 |
| Costonomy courier (supplier order) | quote, then book with fallback | fee held with the order; supplier share charged when the courier has the goods | courier events move the order to `OUT_FOR_DELIVERY` and `DELIVERED`; courier payable becomes `DUE` at pickup | no courier / failure after pickup: supplier takes over, or operations `resolve` (cancel and refund, or mark delivered); duplicate or out-of-order events cannot move a delivery backwards; an unknown booking outcome is reconciled, not rebooked blindly |
| Costonomy courier (food) | create, fee held from wallet or paid at the gateway | fee as booked; unused part returned at pickup | delivered; courier payable `DUE` at pickup | cancel before pickup or no courier: whole fee back to where it came from; failure after pickup: fee kept, courier still owed; flagged, and an operator may close it (`POST /admin/food-deliveries/{id}/resolve`: return the fee, mark delivered, or clear the flag) (D-205) |

Reads never reassign or book (the sweep does), a manual reassign is refused once the goods were collected, and a pickup order nobody collects has an operator exit (`close-pickup`) but no automatic timeout (D-205). No order is complete while money or delivery work is unresolved: `GET /api/v1/admin/deliveries/exceptions` lists the ones that look complete or under way but are not.

### Courier payable and refunds (D-204)

What Costonomy owes a courier is `courier_payable`, one row per booking that reached the provider (so a reassignment makes a second row). It is derived from the delivery and kept in step by an idempotent sync. Funding: the restaurant's fee and the supplier's agreed share are fixed at checkout and never change; the supplier pays `min(agreed, courier cost)`; Costonomy bears any overrun, a cancellation fee, a failed pickup and a failure after pickup, and keeps any underrun. A provider credit reduces what is owed. Payment is leased, retried a bounded number of times, never repeated when the outcome is unknown, and finance can record a bank or invoice payment, retry, void or adjust, with a note and an audit entry. Queues for finance are under `/api/v1/admin/delivery-finance`.

A refund of a payment made at the gateway goes back to the gateway through `gateway_refund`: recorded first, bounded by the amount captured, reconciled when unanswered, retried and then escalated. Wallet payments are credited back to the wallet. A supplier's delivery charge is taken once, by the settlement or by direct payment, and is waived, credited or partly refunded if the courier turns out to cost less or the order is cancelled.

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
- The ledger records what a courier *quoted* and *booked*; what is *owed and paid* to the courier is `courier_payable` (D-204, §10).

## 16. Food delivery (restaurant to customer)

> **Status (audited 2026-10-25): booking, payment, events, API and tracking are built and tested; handover code, notifications and an admin resolve tool are not (see "Handover" and `docs/delivery/CURRENT_STATE.md` §2.2). Deployed and app-visible: unknown / no mobile usage found.**
> **Built and tested:** schema (V88, V91, V94, V95), domain and repositories, creating a delivery (validation, idempotency), quoting
> every food-capable courier, holding the fee, booking with fallback in the order of §17, the 5-minute retry window and
> ending `FAILED` (NO_COURIER) with a full refund, cancelling before pickup with a full refund, settling the unused part
> of the hold after pickup without nested-transaction lock waits, and handing a booking that never got a rider (or whose rider let go) to the next courier.
> Ingesting courier events (webhook deduplication via payload hash fallback, stale consignment rejection, and bounded lease-based polling reconciliation
> for food consignments via `FoodDeliveryReconciliationJob`), the restaurant API (with `OUTLET_EDIT` required for create/cancel and `OUTLET_VIEW` for reads),
> the public tracking endpoint (unguessable 32-char token, 24h expiration after terminal state, PII/dropAddress sanitized, rate-limited at 60/min by IP),
> and admin inspection and operational alerts.
> **Assumption pending product:** the fee model below (a maximum held up front) is the recommended design and is built
> behind a few wallet methods; if product chooses another it is a contained change.

### Scope
- **Created by** the restaurant's own system (POS or website) through the API, or by outlet staff in the Mandi app.
  Mandi has no customer ordering flow; the customer appears only as a drop-off contact.
- **Fee** is paid by the restaurant from its wallet, or at the payment gateway (`paymentMethod=GATEWAY`; the courier is not asked until the payment is captured; an unpaid checkout is cancelled after 30 minutes). **No cash on delivery**: the courier carries food, not money.
- **Not in scope**: customer accounts, menus, food orders, payments from the customer.

### Model
One `food_delivery` per request, for its whole life (the §7 rule applies unchanged: a failed courier appends an
attempt and an event, never a second row). Tables `food_delivery`, `_quote`, `_attempt`, `_event`, `_location`
mirror §5's. The supplier `delivery` tables are not changed.

### Providers and fallback
Only providers with `delivery_provider.supports_food_delivery = 1` are asked. No single provider is required: the
quote is gathered from all eligible ones, the best meeting the ETA ceiling is booked, and if none takes it within the
3-minute assignment deadline (§14) the waterfall moves to the next, excluding the one that failed. If every eligible
provider fails the delivery goes to `PROVIDER_UNAVAILABLE` and the retry window below applies; a request that cannot be quoted at all is refused at creation and nothing is created or debited. (The restaurant is **not** notified by push: food deliveries publish no notification or realtime events.)

### When no courier will take it
Food does not wait the way raw material does. If every eligible provider refuses or times out, the delivery is
retried against all of them for **5 minutes** (not the 30 minutes and "deliver it yourself" offer of §7, which suit
a supplier's pallet and not a hot meal). If nobody has taken it by then it ends `FAILED` with `failure_code =
NO_COURIER`, the fee hold is refunded in full (to the wallet, or to the gateway for a gateway-paid delivery), and the restaurant sees it in `GET …/{id}` and `…/financials`; no notification is sent. A timed-out booking is found by a database sweep on `assignment_deadline`, not by an in-memory timer, so
a restart cannot strand it.

### Food rules
- Vehicle is `TWO_WHEELER` unless the weight says otherwise; cold-chain does not apply.
- `max_delivery_minutes` bounds the quote: a provider whose ETA exceeds it is not eligible.
- The fee: the restaurant agrees a maximum (or defaults to 1.5 x the cheapest courier, rounded up to a rupee). That
  amount is **held from its wallet before any courier is asked**, and only couriers quoting at or below it are asked. It
  stays held until pickup, so a dearer replacement courier needs no second debit; at pickup the unused part (maximum less
  the courier's price) is returned. Cancelling before pickup, or no courier within the retry window, returns all of it.
  Every refund is idempotent on its wallet reference. A request whose maximum is below the cheapest courier, or whose
  wallet cannot hold it, is refused with the reason and nothing is created or debited. Replaying the same idempotency key
  while initial quote gathering is in-flight safely returns the in-progress delivery without error or duplicate debit.
- Cancelling before pickup refunds the fee; after pickup it does not.

### Status
`REQUESTED → QUOTED → PROVIDER_SELECTED → DRIVER_ASSIGNED → AT_PICKUP → PICKED_UP → OUT_FOR_DELIVERY → ARRIVED →
DELIVERED`, with `QUOTE_FAILED`, `PROVIDER_UNAVAILABLE`, `CANCELLED`, `FAILED`, `RETURNED`. Only the courier's
events move a delivery (as §2 for Costonomy delivery).

### Public tracking
`GET /api/v1/public/food-deliveries/{trackingToken}` needs no login. The token is 32 random characters, expires 24
hours after delivery, and the endpoint is rate-limited (60/min per IP). It returns status, ETA, rider first name and position,
and the pickup address. It never returns the fee, the provider, the drop address, or the customer's phone number, and it never
accepts a numeric id.

### Handover
**Not built.** The `FoodDelivery` entity has `deliveryOtpHash`/`deliveryOtpAttempts` columns, but no code generates, sends or verifies a code. The intended design: a 4-digit code is sent to the customer; the rider enters it at handover. Only its hash is stored, and after 5 wrong
tries it is locked and the restaurant is told.

### API (restaurant)
`POST /api/v1/outlets/{outletId}/food-deliveries` (requires `Idempotency-Key` and `OUTLET_EDIT`),
`GET /api/v1/outlets/{outletId}/food-deliveries/{id}` (`OUTLET_VIEW`),
`GET /api/v1/outlets/{outletId}/food-deliveries` (`OUTLET_VIEW`),
`POST /api/v1/outlets/{outletId}/food-deliveries/{id}/cancel` (`OUTLET_EDIT`),
`GET /api/v1/outlets/{outletId}/food-deliveries/{id}/timeline` (`OUTLET_VIEW`),
`POST /api/v1/outlets/{outletId}/food-deliveries/{id}/pay` and `.../confirm` (`OUTLET_EDIT`; gateway-paid fees),
`GET /api/v1/outlets/{outletId}/food-deliveries/{id}/financials` (`OUTLET_VIEW`: held / kept / refunded to wallet or gateway).
Admin: `GET /api/v1/admin/food-deliveries/{id}/inspect` and `GET /api/v1/admin/deliveries/operational-alerts` (`DELIVERY_INSPECT`).

## 17. Courier reliability and the decision record

D-201 addendum. When a courier does not accept or cannot fulfil, the next one asked should be a reliable one, and a
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
Written on every attempt and quote (V92), both flows, going forward only; NULL means "not recorded", never "fine":
`ended_cause` (PROVIDER_REFUSED, PROVIDER_TIMEOUT, ASSIGNMENT_TIMEOUT, RIDER_CANCELLED, PROVIDER_CANCELLED,
PICKUP_FAILED, DELIVERY_FAILED, CUSTOMER_CANCEL, OPS_REASSIGN, SWITCHED_TO_OWN, COMPLETED), `retryable`, `assigned_at`,
`call_ms`, `rank_position`, `selection_reason` (CHEAPEST, RELIABILITY, PROBE, ONLY_OPTION), `reliability_score`,
`breaker_state`, and `decision` (every courier considered and every one skipped). Quotes record `call_ms`,
`error_class` (TIMEOUT, TRANSIENT, REFUSED, UNSERVICEABLE) and `retryable`; food quotes also record the vehicle.

Supplier delivery fills the cause, timing, rank, reason and candidate list, but not the score or breaker state, because
it does not compute them yet.

Configuration: `costonomy.mp.delivery.reliability.*`.
