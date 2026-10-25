# Delivery decision index

`docs/DECISIONS.md` stays the detailed, historical log; this page is the map from each delivery-relevant decision to
the feature it shaped, where it is drawn, and where it is tested. It does not restate decisions. Where a later
decision or the code contradicts the text of an earlier one, the **Note** column says so — trust the code and the
later decision, and read the original for its reasoning.

Line numbers are `docs/DECISIONS.md` at 2026-10-25 and drift as the log grows; search for the heading.
`D-198`, `D-200`, `D-201`, `D-202` do not exist (the numbering skips them); the highest is `D-207`.
Diagram refs are to [DIAGRAMS.md](DIAGRAMS.md) (§1 journey, §2 states, §3 money, §4 architecture); spec refs to
`docs/specs/06-delivery.md` (`06 §n`). Test names are classes under `src/test/java/com/costonomy/mp/`.

## Core delivery model

| Decision | Feature | Diagram | Spec | API | Tests | Note |
|---|---|---|---|---|---|---|
| D-026 (l.696) One delivery per order | `uk_delivery_order`; cascade and takeover keep the same row | §2a | 06 §7 | `POST /supplier-orders/{id}/delivery` | `DeliveryFlowIT$Journey.requestIsIdempotent`, `DeliveryDispatchIdempotencyIT` | — |
| D-027 (l.717) Bidding never reaches a restaurant | `DeliveryResponse` has no provider code; quotes only at `/admin/orders/{id}/delivery` | §1 | 06 §4, §10 | `GET /deliveries/{id}` | `DeliveryFlowIT$Choosing.biddingStaysInternal` | — |
| D-028 (l.739) Only the partner's events move a partner delivery | `DeliveryOrderBridge`; supplier refused `OUT_FOR_DELIVERY`/`DELIVERED` on courier modes | §2a | 06 §2, §5 | `/deliveries/{id}/dispatched\|delivered` (own mode only) | `DeliveryFlowIT$OwnDelivery.supplierCannotClaimPartnerDelivery`, `SupplierOrderLifecycleIT$Movement.supplierCannotClaimCourierMovement` | The order-side endpoints leave the own-delivery row stale (gap 5) |
| D-029 (l.762) Two mock providers | `MOCK_EXPRESS`, `MOCK_SAVER` | §4 | 06 §11 | — | `DeliverySelectionTest`, `DeliveryWaterfallTest` | Default adapters until `PROVIDER` flags change |
| D-030 (l.782) One transaction for composed writes | `DeliverySimulationService` | §4 | — | `POST /internal/deliveries/{id}/simulate` | `DeliveryFlowIT$Tracking` | — |
| D-063 (l.1639) One timeline row per applied event | `DeliveryEventService.apply` | §4 | 06 §8 | `GET /deliveries/{id}/events` | `DeliveryFlowIT$Journey.timelineDoesNotRepeatItself` | Failed retries can still repeat (gap 13) |
| D-120 (l.5170) 30 km radius, tiered deadlines | `max-radius-km`, 3 min bike / 12 min larger | §2a | 06 §4, §6 | — | `DeliveryBookingServiceTest`, `DeliveryQuotingServiceTest` | — |
| D-134 (l.5874) Cold chain | `ColdChainCarrierGate`, carrier capability table | §1 | 06 §4 | `POST /intents/{id}/delivery-quote` (422) | `ColdChainIT`, `CarrierFareFailClosedIT` | — |
| D-127 (l.5399) Slots and subscriptions | `DeliverySlot*`, subscription generation | — | 06 | `/supplier-stores/{id}/delivery-slots`, `/available-slots` | `DeliverySlotsAndSubscriptionsIT`, `DeliverySlotServiceTest` | Not a delivery state; app uses it |
| D-141 (l.5965), D-143 (l.5988), D-145 (l.6008) Delivery chosen per request; delivery or collect; request cannot override the sale | order `deliveryMode`: `PICKUP`, `SUPPLIER_DELIVERY`, `COSTONOMY_DELIVERY` | §1 | 06 §10 | intents delivery-preference | `IntentFlowIT` | Entry point of all three supplier journeys |

## Providers

| Decision | Feature | Diagram | Spec | API | Tests | Note |
|---|---|---|---|---|---|---|
| D-117 (l.4998) Borzo; D-118 Shadowfax; D-119 Porter; D-122 Shiprocket; D-123 LoadShare; D-124 Blowhorn; D-125 Delhivery; D-126 Xpressbees | one adapter per provider, row seeded `enabled=0`, adapter flag default off | §4 | 06 §3 | none (shared) | `*ApiClientContractTest`, `*DeliveryFlowIT` | Webhooks for all eight are **not built** (traceability DEL-004…021 BLOCKED) |
| D-121 (l.5192), D-131 (l.5823) Fail closed without a carrier fare | Shadowfax/Porter decline | §4 | 06 §3, §4 | — | `CarrierFareFailClosedIT` | Shadowfax is still food-capable (V91) but declines |
| D-188 (l.6638), D-190, D-191, D-200 (l.6720) Pidge sandbox control, real route, rider visible, auto-advance | `PidgeSandbox*` | §4 | 06 §14 | `/deliveries/{id}/sandbox/*`, `/admin/deliveries/pidge/sandbox/*` | `PidgeSandboxAdvanceIT`, `PidgeSandboxAutoAdvanceJobTest` | Test only; the admin sandbox endpoints lack a permission check (gap 8) |

## No-partner, takeover, recovery

| Decision | Feature | Diagram | Spec | API | Tests | Note |
|---|---|---|---|---|---|---|
| D-184 (l.6598), D-185 (l.6606), D-187 (l.6631) Retry a delivery nobody can take, then let the supplier deliver; UTC clocks | `DeliveryRetryJobs`, `switchToOwn`, V87 | §1, §2a | 06 §7 | `POST /deliveries/{id}/switch-to-own`; `retryUntil`, `canSwitchToOwn` | `DeliveryFlowIT$NoPartner.*` | Real timings: retry 2 min × 30 min (≤15), offer at 45 min — older docs say "5 min" |
| D-201 (l.6731) Food delivery is a parallel flow; also rebook on stand-down, reliability ranking | `fooddelivery/*`, `DeliveryReassignListener`, `ProviderReliability*`, V91–V98 | §1, §2a, §2b | 06 §16–17 | `/outlets/{id}/food-deliveries/*`, `/public/food-deliveries/{token}` | `FoodDeliveryBookingIT`, `FoodDeliveryOperationsRecoveryIT`, `FoodDeliveryPaymentGatewayIT`, `DeliveryProviderStandDownTest` | Addenda are partly stale: "ingestion, API and tracking not built" is wrong (they are built); "gateway refunds go to the wallet" is wrong (D-204 §2) |
| D-205 (l.6958) Delivery operational recovery, ambiguous timeouts fail-closed, admin permissions, post-pickup failure | `DeliveryResolutionService`, `FoodDeliveryResolutionService`, `AdminDeliveryService.journeyExceptions`, `closeUncollectedPickup`, `forceWaterfall` | §2a, §2b, §4 | 06 §7, §10, §16 | `POST /admin/deliveries/{id}/resolve`, `POST /admin/food-deliveries/{id}/resolve`, `POST /admin/deliveries/orders/{id}/close-pickup`, `GET /admin/deliveries/exceptions` | `DeliveryResolutionIT` (15), `FoodDeliveryOperationsRecoveryIT`, `AdminDeliveryEndpointsIT`, `AdminDeliveryServiceTest` | Ambiguous timeouts fail closed; permissions added to sandbox and stats endpoints; failure after pickup is operator-resolved |
| D-206 (l.7042) Food-delivery failures, durable payable cursor (V105), tenant visibility, gateway refund PENDING | `CourierPayableSyncService`, `DeliveryResponse.underReview`, late delivery superseding failure, `GatewayRefundService` PENDING status | §2c, §3 D–E, §4 | 06 §7, §10, §16 | `POST /admin/delivery-finance/payables/backfill`, `/sync-cursor/rewind`, `POST /deliveries/{id}/cancel` (cancelOrder=false refused) | `CourierPayableSyncIT` (7), `DeliveryLateDeliveredIT` (4), `DeliveryCancellationLifecycleIT` (6), `GatewayRefundIT` (15) | V105 adds `courier_payable_sync_cursor` and PENDING status; late DELIVERED supersedes failure; underReview suppresses re-booking |

## Money

| Decision | Feature | Diagram | Spec | API | Tests | Note |
|---|---|---|---|---|---|---|
| D-053 (l.1366) Commission rate snapshotted; base excludes delivery | `CommissionService` | §3 F | 05 | settlements | `SettlementFlowIT$Commission` | — |
| D-101 (l.3730), D-103 (l.3827), D-104 (l.3870), D-105, D-109 (l.4352) Hold, capture at ready, refunds to wallet, cancel after debit | order funding and refunds | §3 A, E | 05, 04 | `/payments/{id}/refunds`, wallet withdraw | `PaymentFlowIT$Capture`, `$Refunds`, `$CancelDebited` | D-104 applies to supplier **order** payments only |
| D-110 (l.4561) Withdrawal reversal | `WithdrawalReversalService` | §3 E | 05 | `/admin/refunds` | `PaymentFlowIT$WithdrawalFailures` | Order-payment refunds; separate from `GatewayRefundService` |
| D-128–D-130 (l.5807–5823) Post-dispatch reductions; per-method reconciliation | `OrderAdjustmentService`, `SettlementReconciliationService` | §3 F | 05 | `POST /admin/settlements/{id}/approve` | `CatchWeightSettlementIT`, `SettlementFlowIT$Reconciliation` | Reconciliation does not cover delivery funding or courier payables |
| D-203 (l.6847) Supplier can fund the rider above the free-delivery threshold | `DeliveryCharges.fundCostonomyFee`, `supplier_deduction DELIVERY_FUNDING`, V99–V101, V103 | §3 B | 06 §10 | `PUT /supplier-stores/{id}/delivery-policy`; `/delivery-funding/*` | `DeliveryChargesTest`, `SettlementFlowIT$FundedDelivery`, `SupplierDeliveryFundingPaymentIT` | "Supplier pays the figure fixed at checkout even if the fee differs" is superseded: charge is `least(share, fee)` (D-204 §3). `UnusablePaymentRefunds` named in the addendum no longer exists |
| D-204 (l.6883) Courier payable, refunds back the way they came, no half-finished order | `CourierPayable*`, `GatewayRefund*`, `DeliveryResolutionService`, `DeliveryFinanceAdminService`, V104 | §2c, §3 D–E, §4 | 06 §10 | `/admin/delivery-finance/*`, `POST /admin/deliveries/{id}/resolve`, `GET /admin/deliveries/exceptions`, `GET /outlets/{id}/food-deliveries/{id}/financials`, `/supplier-stores/{id}/delivery-funding/{history,summary}` | `CourierPayableIT`, `DeliveryFinanceAdminIT`, `GatewayRefundIT`, `DeliveryResolutionIT`, `SupplierDeliveryFundingPaymentIT`, `FoodDeliveryPaymentGatewayIT`, `SettlementFlowIT$FundedDelivery` | **Uncommitted** at the snapshot. Its claims match the code (states, V104, 5 attempts, routes). Its exceptions list is supplier-delivery only. The "open decisions" list is the real not-done list |
| D-207 (l.7126) Pessimistic write locks on financial rows, production refuses mock courier, refund webhooks routed | `CourierPayableRepository.lockById`, `GatewayRefundRepository.lockById`, `ProductionProviderGuard`, `PaymentWebhookService.routeRefund` | §3 D–E, §4 | 05, 06, 09 | `POST /webhooks/razorpay` (refund.* events) | `FinancialLockRaceIT` (6), `GatewayRefundWebhookIT` (6), `ProductionProviderGuardTest` | Financial repositories protected with `@Lock(PESSIMISTIC_WRITE)`; mock delivery refused in production profiles; Razorpay refund webhooks routed to `GatewayRefundService` |

## Open question still referenced

| Item | State |
|---|---|
| `OPEN-005` (l.1442) delivery fee never charged | Heading still says open; the intent order path adds the fee to the order total (`IntentOrderCreator`); not verified for the legacy procurement path. |
