# Delivery — current state, evidence and gaps

Evidence-based map of the delivery journeys and the money that moves with them. Written from the code,
migrations, tests and sibling mobile repo as they stand on **2026-10-25**; it replaces no spec but corrects
what the older docs say. Diagrams live in [DIAGRAMS.md](DIAGRAMS.md); decision history in
[DECISION_INDEX.md](DECISION_INDEX.md) and `docs/DECISIONS.md`.

## 1. Evidence legend and scope

**Snapshot.** `costonomy-mp-api`, branch `feat/ab-testing-experimentation-platform`, HEAD `341efda`, plus uncommitted
work in the working tree (migration `V101`–`V102`, the `CourierPayable*` / `GatewayRefund*` classes, `DeliveryResolutionService`,
five new test classes, D-204). Mobile: `costonomy-mp-mobile`, branch `feat/enhanced-supplier-capabilites-with-wallet`,
HEAD `7657ffd`. Code that is only in the working tree is marked *(uncommitted)* in the matrix.

| Column | Meaning here | What it is **not** |
|---|---|---|
| **Implemented** | The code, migration and endpoint exist in the working tree. | Proof it is committed, merged or running anywhere. |
| **Tested** | A test class/method that exercises it exists (named). The tests were **not run** for this review; CI exists (`.github/workflows/ci.yml`) but no run result was inspected. | A passing build. |
| **Deployed** | A deploy artifact or release record names an environment. | Anything inferred from code. |
| **App-visible** | The mobile repo calls the endpoint / renders the state (file named). | That a released app build contains it. |

**Deployment evidence found: none.** `CLAUDE.md:123` says "Deployment is out of scope for this version". The repo
has a CI workflow, a WAR packaging setting and a `ProductionProviderGuard` (refuses mock providers under a `prod`
profile) — proof deployment was *anticipated*, not that it happened. No Dockerfile, compose, Kubernetes/Terraform,
`application-prod*`, git tag or release note exists in either repo. **Every "Deployed" cell below is therefore
`Unknown`.**

## 2. Capability matrix

`Y` = yes with evidence, `N` = evidence of absence, `—` = not applicable, `Unknown` = no evidence either way.
Test names are abbreviated; classes are under `src/test/java/com/costonomy/mp/`.

### 2.1 Supplier-order delivery

| # | Capability | Implemented | Tested | Deployed | App-visible |
|---|---|---|---|---|---|
| S1 | **Pickup**: no delivery row; `READY_FOR_PICKUP → COMPLETED` on restaurant `/receive` | Y `DeliveryService.java:370-373,498-499`, `ReceivingService.java:87-89` | `DeliveryFlowIT$Journey.noCourierForAPickup`, `SupplierOrderLifecycleIT$Movement.pickupCompletesOnCollection`, `DeliveryGatingTest.autoDispatchSkipsPickup` | Unknown | Order screens only; receive flow not audited |
| S2 | **Supplier-own delivery**: created at `DRIVER_ASSIGNED`, supplier reports `dispatched`/`delivered`, no GPS | Y `DeliveryService.java:314-331,922-960` | `DeliveryFlowIT$OwnDelivery.*`, `SupplierOrderLifecycleIT$Movement.supplierCarriesTheirOwn` | Unknown | Y `services/delivery.ts:141,152`, `app/supplier/orders/[id].tsx:284,295` |
| S3 | **Courier happy path**: quote → book → assign → pickup → delivered, order bridged | Y `DeliveryBookingService`, `DeliveryEventService`, `DeliveryOrderBridge` | `DeliveryFlowIT$Journey.endToEnd`, `PidgeDeliveryFlowIT`, 8 more `*DeliveryFlowIT` (mock HTTP) | Unknown | Y tracking (`components/delivery/TrackingScreenBody.tsx`, `hooks/useLatestInFlight.ts`) |
| S4 | **No-partner retry** (2 min, 30 min window, ≤15) and **own-delivery offer** at 45 min | Y `DeliveryRetryJobs.java:29-52`, V87 | `DeliveryFlowIT$NoPartner.*` (no unit test of `DeliveryRetryJobs`) | Unknown | Y `services/delivery.ts:117` (switch-to-own); statuses handled in `lib/delivery/deliveryPartner.ts:12` |
| S5 | **Supplier takeover** (`switch-to-own`), fee unchanged | Y `DeliveryService.java:770-829` | `DeliveryFlowIT$NoPartner.switchToOwn`, `.switchRefusals` | Unknown | Y `app/supplier/orders/[id].tsx:273` |
| S6 | **Assignment timeout** cascade (3 min bike / 12 min larger), re-checked under the row lock | Y `DeliveryBookingService`, `DeliveryWaterfallService.cascadeUnassigned` → `DeliveryService.reassignIfAssignmentExpired`, `DeliveryAssignmentSweepJob` | `DeliveryReassignRaceIT`, `DeliveryWaterfallTest`, `DeliveryAssignmentSweepJobTest`, `DeliveryFlowIT.readingNeverCascadesButTheSweepDoes` | Unknown | — |
| S7 | **Driver cancel / pickup failed** → automatic rebook | Y `DeliveryReassignListener`, `DeliveryAssignmentSweepJob.rescueStoodDown` | `DeliveryFlowIT$Reassignment.*`, `DeliveryProviderStandDownTest`, `DeliveryReassignListenerTest` (mock couriers only) | Unknown | Y statuses `DRIVER_CANCELLED`/`PICKUP_FAILED` handled (`lib/delivery/orderTracking.ts:55,266`) |
| S8 | **Cancel delivery** (`POST /deliveries/{id}/cancel`): needs `ORDER_CANCEL` (outlet or store) or `DELIVERY_OPERATE`; cancels the order by default; **`cancelOrder=false` is refused while the order is active** (it would leave a ready order with nothing to rebook) and accepted once the order has ended; refused once collected | Y `DeliveryService.cancel`, `requireCancelPermission` | `DeliveryCancellationLifecycleIT` (6), `DeliverySelectionTest.noCancellationOnceCollected` | Unknown | **N** — no mobile caller |
| S9 | **Delivery failed** after pickup: flagged `reconciliation_required`, never rebooked (manual reassign refused), listed in `exceptions`, closed by `resolve` (cancel and refund, or mark delivered) — an operator-only exit by design (D-205) | Y `DeliveryEventService`, `DeliveryResolutionService`, `AdminDeliveryService.nextStep` | `DeliveryResolutionIT.failureAfterPickupRefundsTheCustomersAndLeavesTheCourierOwed`, `DeliveryReassignRaceIT.manualReassignRefusedAfterAFailedHandOver`, `AdminDeliveryServiceTest` | Unknown | Status handled; no recovery UI |
| S10 | **Ops exits**: `resolve` (cancel and refund, mark delivered, associate consignment, resume booking), `close-pickup`, `force-waterfall` (reports failure, audits the operator), `exceptions`, `inspect`, ledger | Y `AdminDeliveryController`, `DeliveryResolutionService` *(uncommitted)* | `DeliveryResolutionIT.*` (15), `AdminDeliveryServiceTest` | Unknown | — no admin client in either repo |
| S11 | **Courier events**: Pidge webhook (HMAC) + 30 s poll for all adapters | Y `DeliveryWebhookController` (Pidge only), `DeliveryJobs` | `PidgeDeliveryFlowIT`, `PidgeWebhookServiceTest`, `DeliveryJobsTest` | Unknown | — |
| S12 | **Provider adapters**: Pidge (real HTTP) + Borzo, Shadowfax, Porter, Shiprocket, LoadShare, Blowhorn, Delhivery, Xpressbees (all `enabled=false` by default, rows seeded `enabled=0`); mocks default | Y `delivery/provider/*` | `*ApiClientContractTest`, `*DeliveryFlowIT` (HTTP mocked; no live-provider run) | Unknown | — |
| S13 | **Webhooks for the 8 non-Pidge adapters**; **Rapido adapter** | **N** — only `/webhooks/delivery/pidge` exists; no Rapido class | — (traceability DEL-004…021: BLOCKED) | — | — |
| S14 | **Pidge sandbox** advance/auto-advance (test only) | Y `PidgeSandbox*` (flag `pidge.sandbox`, default off; local profile on) | `PidgeSandboxAdvanceIT`, `PidgeSandboxAutoAdvanceJobTest` | Unknown | Y dev only (`useSandboxAdvance.ts`) |

### 2.2 Food delivery (restaurant → its own customer)

| # | Capability | Implemented | Tested | Deployed | App-visible |
|---|---|---|---|---|---|
| F1 | Create with fee hold (wallet or gateway), courier fallback, retry window 5 min, cancel, settle at pickup, rebook | Y `FoodDeliveryBookingService`, V88/V91/V99 | `FoodDeliveryBookingIT`, `FoodDeliveryPaymentGatewayIT`, `FoodCourierSelectorTest` | Unknown | **N, by design** — the mobile app has no food-delivery screen or service (re-checked: no reference in `app`, `services`, `lib`, `components`, `models` or `hooks`); another repository calls this service |
| F2 | Restaurant API: create/get/list/timeline/financials/pay/confirm/cancel | Y `FoodDeliveryController` | `FoodDeliveryOperationsRecoveryIT.outletPermissionsEnforced`, `FoodDeliveryPaymentGatewayIT` | Unknown | **N, by design** — no food-delivery UI in `costonomy-mp-mobile`; the calling repository's use of these endpoints is not evidenced here |
| F3 | Courier events: Pidge webhook (via `UnclaimedDeliveryEventHandler`) + 1 min poll of PROVIDER_SELECTED…ARRIVED | Y `FoodDeliveryEventService`, `FoodDeliveryUnclaimedEventHandler`, `FoodDeliveryReconciliationJob` | `FoodDeliveryOperationsRecoveryIT.duplicateAndOutOfOrderEvents`, `.repeatedStatusPollIsIdempotent`, `FoodDeliveryReconciliationJobTest` | Unknown | — |
| F4 | Public tracking by token (no fee/provider/phone, expires 24 h after end) | Y `PublicFoodDeliveryController` | `FoodDeliveryOperationsRecoveryIT.publicTrackingSanitization`, `.publicTrackingExpiry` | Unknown | **N** |
| F5 | Restaurant notifications/realtime for food | **N, by decision (D-205)** — `FoodDeliveryTimeline` writes `food_delivery_event` rows and publishes nothing to the outbox; the caller is another service that reads status and public tracking | — | — | — |
| F6 | Admin inspect + alerts | Y `AdminFoodDeliveryController`, `AdminDeliveryService.operationalAlerts` | `FoodDeliveryOperationsRecoveryIT.adminInspectionAndAlerts` | Unknown | — |
| F7 | Admin resolve/close for a stuck food delivery | Y `POST /admin/food-deliveries/{id}/resolve` (cancel and refund, mark delivered, clear flag), `FoodDeliveryResolutionService` | `FoodDeliveryOperationsRecoveryIT` (resolve tests) | Unknown | — |
| F8 | Handover OTP verification | **N** — hash fields exist on `FoodDelivery`; no verify method | — | — | — |

### 2.3 Money

| # | Capability | Implemented | Tested | Deployed | App-visible |
|---|---|---|---|---|---|
| M1 | Restaurant pays delivery fee inside the supplier-order total; captured at READY for the accepted amount; released if cancelled before READY | Y `IntentOrderCreator`, `PaymentService.markForCapture/performCapture` | `PaymentFlowIT$Capture`, `IntentFlowIT` | Unknown | Y order payment screens |
| M2 | Supplier funded-delivery share: policy, checkout split, `supplier_deduction DELIVERY_FUNDING` at pickup/delivered | Y `DeliveryCharges`, `DeliveryOrderBridge.chargeFundedDelivery`, V96 | `DeliveryChargesTest`, `SettlementFlowIT$FundedDelivery` | Unknown | **Y in the working tree (uncommitted)** — the store settings screen edits and saves `minOrderValue`, `freeDeliveryThreshold`, `fundCostonomyDelivery` and `fundedDeliveryCap` (`costonomy-mp-mobile/app/supplier/settings/store/[id].tsx`, `services/supplier.ts`); Save waits for the combinations the API refuses. Tests: `tests/supplierSettingsAutoReminders.test.tsx` (the save sends the thresholds and the funding choice; Save is blocked without a threshold and a cap). Not run on a device or against a live API. |
| M3 | Supplier pays the share directly at the gateway; late/duplicate payment refunded | Y `SupplierDeliveryFundingPaymentService`, V98/V100 | `SupplierDeliveryFundingPaymentIT.*` | Unknown | **Y in the working tree (uncommitted)** — More > Money > Delivery charges: `app/supplier/settlements/delivery-funding.tsx` (summary and history from `GET /delivery-funding/summary` and `/history`, per-line `nextAction`, **Pay now** through `POST …/{id}/pay` and `/confirm` with Razorpay checkout), `services/deliveryFunding.ts`, `models/deliveryFunding.ts`, `lib/deliveryFunding/{display,payFlow}.ts`, entry in `app/supplier/(tabs)/more.tsx`. Tests: `tests/deliveryFundingFlow.test.ts` (pay-flow outcomes, never "failed" after a payment was made), `tests/deliveryFundingScreen.test.tsx` (buckets, Pay now only where allowed, closed window), `tests/wordingGuard.test.ts`. Mocked services and a mocked checkout only: not run on a device, against a staging API or against Razorpay. |
| M4 | Supplier share reduced when the courier cost less (waive / credit / partial refund) | Y *(uncommitted)* `SupplierFundingCorrection`, `SupplierShareReduced` | `CourierPayableIT.pendingChargeIsReducedToTheFinalCost`, `.takenChargeIsCorrectedByACredit`, `SupplierDeliveryFundingPaymentIT.directPaymentIsPartlyRefundedWhenTheCourierCostLess` | Unknown | **N** |
| M5 | Courier payable: derived record, funding split, states, event trail; **rebuilt by a durable cursor** (`(updated_at, id)` order, no lookback), with an audit of bookings that have no payable and an operator backfill/rewind (V102) | Y *(uncommitted)* `CourierPayableService`, `CourierPayableSyncService`, V101, V102 | `CourierPayableIT` (24), `CourierPayableSyncIT` (7), `FoodDeliveryOperationsRecoveryIT.foodPayablesAreRebuiltByTheCursorAndBackfill` | Unknown | — |
| M6 | Courier payment lifecycle: lease claim, bounded retry, ambiguity lookup | Y *(uncommitted)* `CourierPaymentService`, `CourierPayableJobs` | `CourierPayableIT.paysOnceThroughTheRail`, `.concurrentPaymentsSendOne`, `.lostAnswerAfterPayingIsFoundNotRepeated` | Unknown | — |
| M7 | **Real courier payment API** | **N** — rails: `MANUAL` (default every provider) and `MOCK_API` (tests) only | — | — | — |
| M8 | Manual courier payment by finance | Y *(uncommitted)* `/api/v1/admin/delivery-finance/payables/{id}/mark-paid` | `DeliveryFinanceAdminIT.markPaidOnceAndAudited` | Unknown | — no admin client |
| M9 | Durable gateway refunds (record → send after commit → lease → lookup), partial, bounded; **a refund the gateway holds as `PENDING` stays pending (id stored) until `COMPLETED`/`FAILED`; a failed one is visible and retried under a new key** (V102) | Y *(uncommitted)* `GatewayRefundService`, V101, V102 | `GatewayRefundIT.*` (15) | Unknown | **N** |
| M10 | Wallet refunds: supplier order (D-104, never to card) and food fee (idempotent credit) | Y `RefundService.refundToWallet`, `WalletService.refundFoodDelivery` | `PaymentFlowIT$Refunds`, `FoodDeliveryBookingIT.cancelBeforePickup` | Unknown | Y wallet screens (`app/restaurant/wallet/index.tsx`) |
| M11 | Finance queues + operator actions (retry, void, cancellation fee, provider credit, final cost, resolve ambiguous/reconciliation, refund retry/resolve) | Y *(uncommitted)* `AdminDeliveryFinanceController` | `DeliveryFinanceAdminIT.*` | Unknown | — |
| M12 | Settlement generate/approve/pay + reconciliation (commission, gross vs captured, wallet vs payouts) | Y `SettlementService`, `SettlementReconciliationService` | `SettlementFlowIT$Settling`, `$Reconciliation` | Unknown | Settlement screens: not audited |
| M13 | Reconciliation of delivery funding / courier payables inside settlement reconcile | **N** — `SettlementReconciliationService` ignores both; courier side has its own flags and queues | — | — | — |
| M14 | Razorpay (real) vs Mock payment adapter | Y `RazorpayPaymentProvider` only if `PAYMENT_PROVIDER=RAZORPAY`; default `MOCK` | `RazorpayPaymentProviderTest`; ITs use the mock (no real-HTTP Razorpay IT) | Unknown | Y checkout in app |

## 3. Money per journey, and failure exits

Facts below are from code; `fee` = the courier price as booked.

### 3.1 Pickup
- **Entry:** order `deliveryMode = PICKUP` (`intent.delivery_preference`, V75; direct orders). Supplier presses `/ready` (`ORDER_READY`).
- **Actors:** supplier (ready), restaurant (`/receive`, `ORDER_RECEIVE`). No courier, no `delivery` row.
- **States:** order `CONFIRMED → PREPARING → READY_FOR_PICKUP → COMPLETED`.
- **Money:** goods only. Held at checkout, captured at READY for the accepted amount, remainder released (D-103/D-128). No delivery fee.
- **Exit paths:** cancel is closed from `READY_FOR_PICKUP` onward (`SupplierOrderStatus.java:51`). A pickup order nobody collects is listed in `GET /admin/deliveries/exceptions` as `PICKUP_NOT_COLLECTED` after 24 h (visibility only; nothing happens automatically) and an operator ends it with `POST /api/v1/admin/deliveries/orders/{orderId}/close-pickup` (cancel and refund; gap 17, exit built, policy open).
- **Retries / operator actions:** none. No uncollected-pickup timeout, reminder or job.
- **Visibility:** order status via `SupplierOrderResponse.deliveryMode`; `SupplierOrderReady` notification "Ready for pickup".

### 3.2 Supplier-carried delivery (`SUPPLIER_OWN`)
- **Entry:** restaurant accepted the supplier's own-delivery offer (`SUPPLIER_DELIVERY`), or the supplier took over a failed courier delivery. `SupplierOrderReady` → `DeliveryDispatchListener` → `DeliveryService.autoDispatch` creates the row at `DRIVER_ASSIGNED`.
- **Actors:** supplier reports `POST /deliveries/{id}/dispatched` then `/delivered`; restaurant `/receive`. Both calls move delivery **and** order via the bridge.
- **Money:** restaurant pays the supplier-set `deliveryFee` inside the order total (often 0); no courier, no payable, no funded-share deduction (`DeliveryOrderBridge.java:64-66` skips non-COSTONOMY). Takeover keeps the fee unchanged and zeroes `delivery_funded_amount`.
- **Exit paths:** `cancel` from `DRIVER_ASSIGNED` needs `ORDER_CANCEL` (or `DELIVERY_OPERATE`) and cancels the order by default; `cancelOrder=false` is refused while the order is active (it would strand a ready order). Ops `resolve`. No failure states are reachable by any code path in this mode.
- **One movement path (gap 5, closed):** the order endpoints `/supplier-orders/{id}/out-for-delivery|delivered` and the delivery endpoints both go through `SupplierDeliveryMovementService`, which moves the order and the delivery together in one transaction, refuses courier-carried orders, and handles a legacy order with no delivery row.
- **Visibility:** `DeliveryResponse` with `trackable=false`; the plain `DriverAssigned` notification no longer says "delivery partner" for own delivery (gap 12, closed).

### 3.3 Costonomy courier delivery (`COSTONOMY`)
- **Entry:** same listener; also manual `POST /supplier-orders/{id}/delivery` (idempotency key). Radius cap 30 km; cold-chain goods need a verified carrier.
- **Transitions and actors:** see [diagram 2a](DIAGRAMS.md#2a-supplier-order-delivery-deliverystatus).
- **Customer money:** restaurant's `deliveryFee` and the supplier's agreed `delivery_funded_amount` are fixed at checkout. The restaurant part is in the order payment (captured at READY).
- **Supplier money:** when the courier has the goods (`PICKED_UP`…`DELIVERED`) one `supplier_deduction(DELIVERY_FUNDING)` is inserted, amount `least(funded share, delivery.fee)`, unique per delivery (`uk_supplier_deduction_delivery`). Then `PENDING → APPLIED` (settlement) or `PAID_DIRECTLY` (gateway); reduced later by `SupplierFundingCorrection` if the final cost is lower.
- **Courier money:** `courier_payable` row per booking attempt that reached the provider. `ACCRUED` at booking; `DUE` when the courier has the goods (`DELIVERED`/`PICKED_UP`/`POST_PICKUP_FAILURE`) or a cancellation fee is recorded; `VOID` if nothing owed. `platform_funded = cost − restaurant fee − supplier share` (can be negative). Paid by finance (`MANUAL`) — no API rail.
- **Quote vs final:** `final_cost` = `delivery.fee` as booked, unless finance sets a manual final cost. Costonomy bears an overrun and keeps an underrun. Cancellation fees and provider credits exist **only when finance enters them**; nothing detects them from a provider.
- **Failure exits:**

| Failure | What the code does | Money | Owner of next action |
|---|---|---|---|
| Nobody quotes | `QUOTE_FAILED`, `no_partner_since` set; retry job every 2 min for 30 min (≤15) | none moved | job, then supplier (offer at 45 min), then ops |
| All couriers refuse booking | `PROVIDER_UNAVAILABLE`, same retry | none | job / supplier / ops |
| Booking timeout on a real provider | attempt `AMBIGUOUS_PROVIDER_OUTCOME`, `reconciliation_required=true`; booking stops, **no second courier is asked while the outcome is unknown**; reassign, auto-retry and the booking sweep are blocked (gap 1: safe and resolvable, lookup not built) | payable not created | ops: `resolve` with `ASSOCIATE_CONSIGNMENT` or `RESUME_BOOKING` |
| No rider within 3/12 min | cascade: stand down, book next quote (same delivery id, new attempt) | first payable `VOID` if courier never had goods | timer / sweep (reads never cascade) / manual |
| Rider cancels or pickup fails | `DRIVER_CANCELLED`/`PICKUP_FAILED`, auto rebook | as above | listener + 30 s sweep |
| Customer/supplier cancels before pickup | `CANCELLED`, provider cancel outside tx with durable retry task | cancellation fee only if finance records it | ops/restaurant/supplier with `ORDER_CANCEL`; the order is cancelled with it by default (gap 4, closed) |
| Cancel after pickup | refused (`INVALID_STATE_TRANSITION`); only ops `resolve` | `CANCEL_AND_REFUND` refunds the customer through the order funding port, gives the supplier share back; **courier stays owed** | ops |
| Delivery failed after pickup | `DELIVERY_FAILED`, flagged `reconciliation_required`, order stays `OUT_FOR_DELIVERY`; no automatic recovery by design (gap 3); a later courier `DELIVERED` clears the failure and the flag (gap 20); tenants see `underReview` | courier owed in full (`failedAfterPickup`) | ops `resolve` (manual reassign is refused) |
| Duplicate/out-of-order provider event | unique (`provider_code`, `provider_event_id`) → `DUPLICATE`; rank check → `OUT_OF_ORDER` | none | — |

- **Operator actions:** `POST /api/v1/admin/deliveries/{id}/resolve` (`CANCEL_AND_REFUND` | `MARK_DELIVERED`, `DELIVERY_OPERATE`), `force-waterfall`, `GET …/exceptions` (ready with no courier, on the road but failed, payable unresolved, supplier share uncollected 14 days; bounded 50 per kind), `GET …/{id}/inspect`.
- **Visibility:** restaurant/supplier `DeliveryResponse` (never the provider code; `retryUntil`, `canSwitchToOwn` while no partner); ops sees quotes via `/admin/orders/{id}/delivery`.

### 3.4 Restaurant-to-customer food delivery
- **Entry:** `POST /api/v1/outlets/{outletId}/food-deliveries` (idempotency key, `OUTLET_EDIT`), `paymentMethod` WALLET (default) or GATEWAY.
- **Money in:** the restaurant agrees a **max fee** (default `ceil(cheapest × 1.5)`); the whole max fee is held before any courier is asked. Wallet: debit in the same short transaction that moves to `QUOTED`. Gateway: authorization with auto-capture is created outside any transaction; courier is **not asked until paid** (`/confirm`, Razorpay webhook or a 60 s sweep); an unpaid checkout is cancelled after 30 min.
- **Money out:** at pickup `settleFee` returns `netHeld − fee` (wallet credit `food-delivery-{id}-adjust`, or a partial gateway refund). Gateway-paid fees refund to the **gateway**, never the wallet.
- **Failure exits:**

| Failure | Result | Refund |
|---|---|---|
| Quote fails at create | `DELIVERY_UNAVAILABLE`, row deleted, nothing debited | — |
| No courier within 5 min | `FAILED/NO_COURIER` | full held fee, wallet or gateway |
| Rider cancels / pickup fails before pickup | rebook (same delivery) | — |
| Restaurant cancels before pickup | `CANCELLED`, fee `0`, provider cancel outside tx with recovery task | whole held fee |
| Cancel after pickup | `INVALID_STATE_TRANSITION` | — |
| Delivery fails after pickup | `FAILED`, restaurant keeps paying the courier fee | none (by design, D-204 §4) |
| Provider `CANCELLED` event | before pickup: stand down and rebook; after pickup: `FAILED`, flagged `reconciliation_required`, courier payable `DUE` (gap 6, closed) | none after pickup (D-204 §4 kept) |
| Late, duplicate or wrong-amount gateway payment | `gateway_refund UNUSABLE_PAYMENT` | full amount |

- **Courier money:** payable derived from the food delivery (`FoodCourierPayableSync`); buyer contribution = `fee`, supplier 0, platform 0 until finance records a fee/credit/final cost.
- **Visibility:** restaurant `GET …/financials` (charge: NOT_CHARGED/HELD/CHARGED/RELEASED; refund: NONE/IN_PROGRESS/NEEDS_REVIEW/COMPLETE; wallet vs gateway split); public tracking link for the end customer; ops inspect/alerts. **No push or realtime events** for food.

### 3.5 Reconciliation and finance views (all journeys)
- `GET /api/v1/admin/delivery-finance/queues` — overdue (>72 h), exhausted, ambiguous, unreconciled, awaiting manual payment, refunds overdue/exhausted/ambiguous; each list bounded at 100.
- Courier side flags `reconciliation_required` on mismatch and clears only through `resolve-reconciliation` (needs a note).
- Settlement reconciliation compares gross vs captured and wallet credit vs payouts only (M13).
- Supplier view `GET /supplier-stores/{id}/delivery-funding/{pending,history,summary}` never shows courier payment; the finance queues never list supplier collections.

## 4. Duplicated triggers and race control

The question: are listeners, in-memory timers and database sweeps intentional resilience or accidental duplication?
Rule used: **a second trigger is intentional when its javadoc/test names it as a backstop and every entry point converges on the same lock + guard**.

| Outcome | Triggers | Claim / idempotency / race control | Verdict |
|---|---|---|---|
| Courier dispatch after READY | outbox listener `DeliveryDispatchListener`; manual POST; `DeliveryAssignmentSweepJob.rescueUnbooked` | `uk_delivery_order`, existing-row short-circuit, `lockById` + PENDING-attempt check, attempt-number unique, outbox `SKIP LOCKED` claim; `DeliveryDispatchIdempotencyIT` | **Intentional** |
| Assignment-timeout cascade | in-memory `taskScheduler` one-shot at the deadline (`DeliveryWaterfallService`); 30 s DB sweep; manual reassign / `force-waterfall` (the lazy cascade on `GET` was removed, gap 16) | timer and sweep call `reassignIfAssignmentExpired`, which re-checks status, deadline, flag, pending attempt and order state **under the row lock** (gap 2, closed); manual reassign is refused after pickup or `DELIVERY_FAILED` | Timer + sweep **intentional** (timer is lost on restart; sweep is the guarantee). The lazy-on-read entry point was removed (gap 16); manual reassign remains and is guarded |
| Rebook after rider cancel / pickup failed | `DeliveryReassignListener` (after commit); `rescueStoodDown` sweep | `reassignAfterProviderFailure` re-checks status, provider id, order READY, PENDING attempt under lock | **Intentional** |
| No-partner retry | `DeliveryRetryJobs`; manual reassign; ops force-waterfall | `claimRetry` conditional UPDATE (increments `auto_retry_count`), ShedLock | **Intentional** (manual reassign does not count toward the cap) |
| Own-delivery offer | `DeliveryRetryJobs.dueForOffer` only | `claimOffer` conditional UPDATE | single trigger |
| Delivery status | Pidge webhook; 30 s poll; sandbox advance | event unique key (own `REQUIRES_NEW` store), rank + transition check, `@Version`, poll `claimDeliveryForPoll` (2 min stale claim), ShedLock | **Intentional** ("safety net for callbacks that never arrive") |
| Supplier-own movement | `/deliveries/{id}/dispatched|delivered` **and** `/supplier-orders/{id}/out-for-delivery|delivered` | bridge CAS `where status=?`; both paths call `SupplierDeliveryMovementService` (one transaction, order and delivery together) | **Two entry points, one implementation** (gap 5, closed) |
| Provider cancel | inline call + durable `delivery_recovery_task` | `uk_delivery_recovery_idempotency`, lease, ≤5 attempts | **Intentional** |
| Food courier events | Pidge webhook; 1 min poll with deterministic poll event id | `uk_food_event_provider`, rank check, `claimDeliveryForPoll` | **Intentional** |
| Food gateway payment → booking | client `/confirm`; Razorpay webhook; 60 s payment sweep | all reach `settleGatewayPayment`: row lock, `paidAt` null, status `REQUESTED`, amount equals max fee; anything else becomes an `UNUSABLE_PAYMENT` refund | **Intentional** (`FoodDeliveryPaymentGatewayIT` has confirm/webhook/sweep variants) |
| Food retry / rebook / stale attempt | retry job 30 s; assignment sweep; stale-pending sweep; events | `reserveNext` PENDING-attempt guard, row lock, `findByReference` lookup before declaring abandoned | **Intentional** |
| Gateway refund send | `sendSoon` after commit; 30 s `GatewayRefundJobs` sweep; operator retry | conditional UPDATE claim with lease + `claim_token`; outcome only if token still ours; key = receipt; reservation caps total ≤ captured | **Intentional** |
| Courier payable | after-commit `syncSoon` (bridge, food timeline, resolve); 1 min `CourierPayableJobs` | `uk_courier_payable_attempt`, idempotent `sync`; payment claim by conditional UPDATE | **Intentional** |
| Supplier funding payment | `/confirm`; Razorpay webhook; 60 s sweep | `recordDirectPayment` compare-and-set from `PENDING`, `payment_id` unique; a payment that loses is refunded | **Intentional** — but the sweep window excludes a duplicate arriving *after* `PAID_DIRECTLY`; only webhook/confirm catch it |
| Outbox | scheduled drain; `drainAfterCommit` | same ShedLock name + `SKIP LOCKED` | **Intentional** |

## 5. Divergent flows and their boundaries

These are separate on purpose; do not force one state machine over the others.

| Boundary | Supplier-order delivery | Food delivery | Why they stay apart |
|---|---|---|---|
| Money in | fee inside the order payment, fixed at checkout, captured at READY | max fee held before booking, adjusted at pickup | different payer intent and timing |
| Retry window | 30 min, 2 min interval, offer at 45 min, supplier takeover possible | 5 min, 30 s poll, then fail with full refund | food has no "supplier" to take over and the customer is waiting |
| Failure after pickup | order stays `OUT_FOR_DELIVERY`, ops refunds customers, courier owed | restaurant keeps paying the fee | different counterparties |
| Refund route | order refunds → **wallet** (D-104); card only by explicit withdrawal | gateway-paid → **gateway**; wallet-paid → wallet | different payment objects (order payment vs delivery-fee payment) |
| Events | outbox → realtime/notifications | none | food notifications not built |
| Shared | provider registry, adapters, reliability ranking, `delivery_recovery_task`, `courier_payable`, `AfterCommit` | | one place to pay the courier |

Overlaps worth watching (not merged): two refund engines (`RefundService` for orders vs `GatewayRefundService` for
delivery fees); two claim/lease implementations (`CourierPaymentService`, `GatewayRefundService`) that follow the same
pattern but share no code; Pidge webhook fan-out (`PidgeWebhookService` tries supplier deliveries first, then
`UnclaimedDeliveryEventHandler`s).

## 6. Known gaps and risks

Only items supported by code or by missing evidence. IDs are stable for cross-reference.

### 6.1 Implementation gaps (code)
Status after D-207. **Closed** = fixed in the working tree with the test named; **Open** = deliberately left, with the reason.

| # | Gap | Status | Evidence |
|---|---|---|---|
| 1 | Ambiguous booking is a dead end for real providers. | **Closed (made safe and resolvable); lookup not built.** No real adapter has a verified lookup-by-reference (Pidge's client only reads an order by Pidge's own id; the repo holds no provider API document that shows otherwise), so none was implemented. An unknown outcome is flagged, listed as `RECONCILIATION_REQUIRED`, blocks every reassign, auto-retry and booking sweep, and an operator closes it with `ASSOCIATE_CONSIGNMENT` or `RESUME_BOOKING` after checking the provider's console. | `DeliveryBookingServiceTest` (timeout then found / not found / lookup fails / unsupported), `DeliveryResolutionIT` (ambiguous x2), `DeliveryFlowIT$NoPartner.aFlaggedDeliveryIsNotRetried` |
| 2 | `reassign` did not re-check under the lock. | **Closed.** The timer and the sweep call `reassignIfAssignmentExpired`: status `PROVIDER_SELECTED`, deadline passed, a courier to release, no flag, no pending attempt, order `READY_FOR_PICKUP`, all under the row lock. A manual reassign is refused after `DELIVERY_FAILED` or any pickup. | `DeliveryReassignRaceIT` (4, incl. two threads) |
| 3 | `DELIVERY_FAILED` has no automatic recovery. | **Closed by decision: operator-only.** Goods were collected, so nothing may be rebooked. It is flagged, `nextAction` says `OPERATOR_RESOLVE`, it is listed in `exceptions`, and `resolve` ends it. A failure before pickup is treated as a failed pickup and rebooked. | `DeliveryResolutionIT.failureAfterPickup…`, `AdminDeliveryServiceTest` |
| 4 | Delivery `cancel` needs only `ORDER_VIEW`, does not cancel the order. | **Closed.** | `DeliveryCancellationLifecycleIT`, `DeliverySelectionTest.noCancellationOnceCollected` |
| 5 | Two supplier-own movement paths. | **Closed** (`SupplierDeliveryMovementService`). | `SupplierOrderLifecycleIT$Movement` |
| 6 | Food provider `CANCELLED` cancels without refund; no test. | **Closed.** Before pickup: stand down and rebook. After pickup: `FAILED`, flagged, nothing refunded, courier payable `DUE` (D-204 §4 kept). | `FoodDeliveryOperationsRecoveryIT` (provider CANCELLED / DELIVERY_FAILED before and after pickup) |
| 7 | Food `reconciliation_required` never cleared; `exceptions` supplier-only. | **Closed.** `POST /admin/food-deliveries/{id}/resolve`; `exceptions` lists `FOOD_RECONCILIATION_REQUIRED` and `FOOD_COURIER_PAYMENT_UNRESOLVED`. | `FoodDeliveryOperationsRecoveryIT` (resolve, exceptions, guards) |
| 8 | Admin permission holes (stats/aggregate, Pidge sandbox). | **Closed.** | `AdminDeliveryEndpointsIT` |
| 9 | `inspect.nextAction` says "TERMINAL" for non-terminal states; `force-waterfall` swallows failure. | **Closed.** `nextStep` answers for every status; `force-waterfall` throws the failure and audits the operator and the outcome. | `AdminDeliveryServiceTest` (every status), `DeliveryResolutionIT.forceWaterfallReports…` |
| 10 | Provider event stored before it is applied; a failed apply is later seen as `DUPLICATE`. | **Closed.** The event row is written in the same transaction as the change (`INSERT IGNORE`), so a failed apply leaves nothing and the retry is applied (the `DeliveryEventStore` javadoc and DIAGRAMS pattern 5 describe it). | `DeliveryEventRetryIT` (failed apply then retry; concurrent duplicate) |
| 11 | Recovered stale booking discarded; bike deadline; no ledger/audit/event. | **Closed.** Adopted in every state that wants a courier and holds none; recorded through the same `recordBooked` as a normal booking, with the vehicle's deadline. | `DeliveryBookingRecoveryTest` (13) |
| 12 | Missing notification rules; wrong `DriverAssigned` text for own delivery. | **Closed.** Rules for the seven events; plain `DriverAssigned` no longer says "delivery partner". Food publishes no outbox events, by decision (D-205). | `NotificationRulesTest$DeliveryFailureExits`, `NotificationWordingTest` |
| 13 | Each failed auto-retry appends a timeline row and outbox event. | **Closed.** Only a change of status or failure code is recorded. | `DeliveryFlowIT$NoPartner.aRepeatedFailureIsAnnouncedOnce` |
| 14 | Food `failNoCourier` wallet refund inside the retry transaction; unproduced states. | **Closed.** The wallet is no longer called inside the transaction: a durable recovery task is queued with the failure and the refund is made after commit. `RETURNED` now has a producer (operator `resolve`). Food `QUOTE_FAILED` still has none: kept for compatibility, handled by the retry job. | `FoodDeliveryBookingIT.failsAfterTheWindowAndRefunds`, `FoodDeliveryOperationsRecoveryIT` |
| 15 | 15-minute gap between auto-retry (30) and the own-delivery offer (45). | **Closed as intentional (D-185); listing made consistent.** The quiet period is the supplier's chance to be offered the takeover; `READY_WITHOUT_COURIER` now lists a no-partner delivery only once the offer is due, not while retries run. | `DeliveryResolutionIT.noPartnerIsListedAfterTheRetriesNotDuring` |
| 16 | `GET /deliveries/{id}` reassigns and calls providers on read. | **Closed.** Reads are read-only; the sweep moves expired assignments. | `DeliveryFlowIT.readingNeverCascadesButTheSweepDoes` |
| 17 | No uncollected-pickup policy or ops exit. | **Closed (exit); policy open.** `POST /admin/deliveries/orders/{id}/close-pickup` cancels and refunds an uncollected pickup order; `exceptions` lists `PICKUP_NOT_COLLECTED` after 24 h (visibility only, nothing happens automatically). A reminder/cancel/fee policy is a product decision. | `DeliveryResolutionIT` (pickup exit, guards) |
| 18 | Dead config. | **Closed.** `costonomy.mp.delivery.webhook-secret` and `costonomy.mp.pidge.unassigned-timeout` were never read (repo-wide grep) and are removed. | grep |
| 19 | A gateway refund was marked successful while the provider reported `PENDING`. | **Closed.** `PENDING` (immediate answer, or found by lookup) is stored with the provider's refund id and asked about (`reconcilePending`, in the sweep) until `COMPLETED` (success, announced once) or `FAILED` (visible, retried as a new refund under a new idempotency key and receipt; `send_round`). Stuck pending refunds appear in the finance queue and the customer's `refundState` stays `IN_PROGRESS`. | `GatewayRefundIT` (immediate PENDING, listed PENDING, PENDING then FAILED then retried, stuck PENDING) |
| 20 | A late provider `DELIVERED` left the failure and the reconciliation flag in place. | **Closed.** A confirmed `DELIVERED` supersedes a prior `DELIVERY_FAILED`/`FAILED` for supplier and food deliveries: failure fields and the flag cleared, the attempt's cause becomes `COMPLETED`, audit `DELIVERY_FAILURE_SUPERSEDED`/`FOOD_DELIVERY_FAILURE_SUPERSEDED`, events kept; the inspection says `TERMINAL`. | `DeliveryLateDeliveredIT` (3), `FoodDeliveryOperationsRecoveryIT.foodFailureThenDelivered` |
| 21 | Payable sync starved or missed eligible deliveries (newest 500 in 48 h each pass). | **Closed.** A durable cursor per source (`courier_payable_sync_cursor`) walks `(updated_at, id)` order; the audit counts bookings with no payable (gauges, ALERT log, finance totals); `POST /admin/delivery-finance/payables/backfill` and `/sync-cursor/rewind` repair. A row failing three passes in a row is skipped loudly and listed. Remaining limit: rows that commit later than the 30 s settle lag with an earlier `updated_at` are caught by the after-commit sync and the audit, not by the cursor. | `CourierPayableSyncIT` (7), food backfill test |
| 22 | `cancelOrder=false` could leave a ready order with a cancelled delivery and no rebooking path. | **Closed by refusing it** while the order is active. A previously stuck order is repaired by cancelling the order (the default). Switch-to-own and the automatic retry are also refused while a delivery is flagged. | `DeliveryCancellationLifecycleIT`, `DeliveryLateDeliveredIT.takeoverIsRefusedWhileUnderReview`, `DeliveryFlowIT$NoPartner.aFlaggedDeliveryIsNotRetried` |
| 23 | Mobile hid recovery for `DELIVERY_FAILED` and stopped refreshing at it. | **Closed** in `costonomy-mp-mobile`: failed and under-review deliveries show what happens next, keep polling every 30 s until the server reports `DELIVERED`/`CANCELLED`, and offer no retry or takeover (`canRetryPartner(…, underReview)`). Needs a device build to verify. | `trackingScreenBody.test.tsx` (5), `trackingPoll.test.ts`, `deliveryFailureRecovery.test.ts` |
| 24 | Financial rows (`courier_payable`, `gateway_refund`) vulnerable to read-modify-write races between operators and sweep jobs. | **Closed.** `lockById` annotated with `@Lock(LockModeType.PESSIMISTIC_WRITE)` on both `CourierPayableRepository` and `GatewayRefundRepository`, ensuring atomic state transitions. | `FinancialLockRaceIT` (6) |
| 25 | Razorpay refund webhooks silently dropped (`extractPaymentId` looked only at payment entity). | **Closed.** `PaymentWebhookService.routeRefund` ingests `refund.processed`, `refund.failed`, `refund.created` from `payload.refund.entity` and applies them to matching `GatewayRefund` records. Out-of-order or duplicate events no-op; unmatched left to poll. | `GatewayRefundWebhookIT` (6) |
| 26 | `ProductionProviderGuard` did not protect delivery provider. | **Closed.** Added `costonomy.mp.providers.delivery` to guarded properties; requires non-mock delivery provider and valid `costonomy.mp.pidge.api-token` if Pidge is configured under production profiles. | `ProductionProviderGuardTest` |

### 6.2 Deployment gaps
- No environment is evidenced: nothing says the backend, the migrations V1–V102 or any job is running anywhere. `application-prod*` does not exist; production env vars are documented only in `docs/ONBOARDING.md` and `docs/RAZORPAY.md`.
- Production prerequisites documented but not evidenced as met: `JWT_SECRET`, `REALTIME_ALLOWED_ORIGINS`, Pidge webhook secret, MySQL `binlog_format=ROW` (`docs/RAZORPAY.md:506`), Redis backend for rate limiting on >1 instance (D-051).
- The uncommitted work (V101, V102 and the payable/refund/recovery code, plus the mobile changes) is not in any commit on these branches. V102 is new and unapplied anywhere; it must be applied to a staging database before release (it widens one CHECK, adds one column with a default, one table and two indexes).

### 6.2a Checks that still need a staging environment (nothing here was run against a real provider)
- **Razorpay refund vocabulary and idempotency.** `processed` → completed, `failed` → failed, anything else → pending is the adapter's mapping (`RazorpayPaymentProvider`); confirm against a real refund that a `pending` refund later reads `processed`, and that a refund Razorpay reports `failed` can be re-requested with a *new* idempotency key and receipt (the retry path assumes it can, and that reusing the old key would replay the failure).
- **Refund `amount_refunded` while pending.** The pre-send guard compares the gateway's `amountRefunded` with the capture; confirm whether it counts pending refunds and removes a failed one.
- **Pidge lookup by reference.** No lookup endpoint is wired (gap 1); confirm with Pidge whether an order can be found by `reference_id`, and what a timeout-then-lookup returns.
- **V102 on a MySQL 8 with real data.** The CHECK is dropped and re-added and two indexes are built on `delivery` and `food_delivery`; time it on a production-sized copy.
- **The sync cursor's first pass.** On a database with history the first pass walks every delivery (200 per batch, up to 5 batches a minute); size `sync-batch` and watch `courier.payable.sync.cursor.lag.seconds`.
- **Mobile.** The failed and under-review states, the 30 s refresh, and the absence of any retry button on a device build against a staging API.

### 6.3 Mobile-app status
Evidence is the working tree of `costonomy-mp-mobile` (uncommitted; HEAD `7657ffd` has none of it), checked by reading the files and by the jest suites named below. None of it was run on a device, against a staging or production API, or against a real checkout, and no release or deployment record was found.

| Capability | Mobile implementation (files) | Tests | Status |
|---|---|---|---|
| Supplier-funded delivery policy in store settings: store minimum, free-delivery value, "pay for delivery on big orders", cap; all four sent on every save (the API saves the policy whole) | `app/supplier/settings/store/[id].tsx`, `services/supplier.ts` (`DeliveryPolicy`, `saveDeliveryPolicy`) | `tests/supplierSettingsAutoReminders.test.tsx` | Implemented, tested with mocked services |
| Supplier delivery charges: totals, every charge in every state, next step, Pay now (gateway checkout, confirm, never reported failed once paid) | `app/supplier/settlements/delivery-funding.tsx`, `services/deliveryFunding.ts`, `models/deliveryFunding.ts`, `lib/deliveryFunding/display.ts`, `lib/deliveryFunding/payFlow.ts`, `app/supplier/(tabs)/more.tsx` | `tests/deliveryFundingFlow.test.ts`, `tests/deliveryFundingScreen.test.tsx`, `tests/wordingGuard.test.ts` | Implemented, tested with a mocked checkout |
| Failed and under-review deliveries: next step shown, kept refreshing every 30 s until the server reports `DELIVERED`/`CANCELLED`, no retry or takeover while `underReview` | `lib/delivery/trackingPoll.ts`, `deliveryPartner.ts`, `orderTracking.ts`, `trackingHeader.ts`, `supplierTrackingHeader.ts`, `components/delivery/TrackingScreenBody.tsx`, `app/restaurant/orders/[id].tsx`, `models/delivery.ts` | `tests/trackingScreenBody.test.tsx`, `tests/trackingPoll.test.ts`, `tests/deliveryFailureRecovery.test.ts`, `tests/orderTracking.test.ts`, the two header tests | Implemented, tested with mocked services |
| Food delivery (restaurant to its own customer): endpoints, public tracking, financials | **None, by design.** No reference in the app; another repository calls the service | — | Not applicable here |
| Gateway-refund and courier-payable states | None, and none intended: finance and operations use the admin API; the customer-facing food `financials` view is for the calling repository | — | Not applicable here |

The app's `DeliveryStatus` mirrors the backend's 15 values and ignores `FoodDeliveryStatus`. The app reads the delivery through `GET /supplier-orders/{id}/delivery` (`services/delivery.ts` `fetchDelivery`) and has no function for `POST /deliveries/{id}/cancel`. Radar kitchen actions `CHECK_IN`, `PREPARE_DOCK`, `MONITOR` appear in the app model; the backend names were not all found in `OutletDeliveryRadarService` (unverified).

### 6.4 Provider-contract gaps
- Webhooks: only Pidge. Borzo, Shadowfax, Porter, Shiprocket, LoadShare, Blowhorn, Delhivery, Xpressbees rely on a 30 s poll; payload/signature contracts unverified (traceability DEL-004…021 BLOCKED).
- Shadowfax and Porter decline every quote until a carrier-fare contract is verified (D-121); Shadowfax is still listed food-capable.
- No real adapter supports lookup-by-reference (gap 1): none was built because no provider API document in the repo shows an endpoint that finds an order by our reference. Confirm with each provider before building one.
- No Rapido adapter although the plan names it.
- No real provider payment API (M7); all live-provider behaviour is mocked in tests.

### 6.5 Product and finance decisions (from D-204 and the code)
Provider payment rails; who bears courier cost differences (built: Costonomy bears); whether D-104 (refund to wallet) should change for card-paid supplier orders; supplier bearing post-pickup loss; pickup no-show; GST/invoicing of the supplier delivery charge and the courier's invoice; overdue thresholds (72 h / 30 min / 14 days); an uncollected-pickup policy; a cap on a restaurant's food-delivery max fee. `OPEN-005` ("delivery fee never charged") still reads open in `DECISIONS.md:1442`, but the intent path adds the fee to the total (`IntentOrderCreator.java:~311-330`); the legacy procurement path was not verified.

## 7. Evidence still needed

| From | Evidence |
|---|---|
| Operations / platform | Which environments exist; which commit and migration version each runs; which of `PAYMENT_PROVIDER`, `DELIVERY_PROVIDER`, `*_ENABLED` flags are set; whether the jobs in [DIAGRAMS §4](DIAGRAMS.md#4-production-architecture) are running. |
| Mobile | Which build is in each store track and whether it contains the working-tree changes above; a device or staging run of the delivery-funding screens and the failed-delivery states; how the calling repository uses the food-delivery endpoints. |
| Providers | Live webhook/signature contracts for the 8 non-Pidge adapters; carrier-fare APIs for Shadowfax/Porter; whether any provider can be paid by API (rail decision). |
| Finance | Rail choice, cancellation-fee policy, overdue thresholds, GST on supplier delivery charge. |
| Engineering | A CI run of `mvn verify` on the current tree. A local run is reported in the change notes; no CI result was inspected. |
