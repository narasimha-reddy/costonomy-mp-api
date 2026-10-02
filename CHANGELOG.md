# Changelog

All notable changes across the platform (Delivery/Logistics and Razorpay Payment Integrations) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Razorpay Integrations]

### [feat/razorpay-17-withdrawal-failure-reversal] - PR / Step 17
#### Added
- Database migration `V44__withdrawal_failure_reversal.sql` adding withdrawal failure audit states and operator resolution tracking.
- `WithdrawalReversalService`: verified bank proof validation before restoring failed withdrawals to restaurant wallets (D-110).
- `AdminRefundController`: dedicated admin endpoints (`/api/v1/admin/refunds/...`) for operational audit reviews and manual override exits.
- Concurrency deadlock protection utility `DeadlockRetry` with unit and integration tests.

### [feat/razorpay-16-upi-cancel-refund] - PR / Step 16
#### Added
- Database migration `V43__debited_payment_on_cancelled_order.sql`.
- `CancellationService`: automatic refund orchestration for debited/auto-captured orders (UPI/cards) cancelled prior to fulfilment (D-109).
- Periodic background worker `cancelDebitedPayments` in `PaymentJobs` running every 15s to auto-refund cancelled orders.
- Configuration parameters `costonomy.mp.razorpay.manual-expiry-minutes` and `costonomy.mp.razorpay.cancel-refund-speed`.

### [feat/razorpay-15-order-payment-status] - PR / Step 15
#### Added
- Dynamic payment status resolution on `SupplierOrder`: live evaluation of payment state from the underlying funding mechanism (Razorpay, credit line, or wallet).
- Updated `SupplierOrderMapper` and `CreditFundingAdapter` with live payment state queries.

### [feat/razorpay-12-dispute-refund-requests] - PR / Step 12
#### Added
- Database migration `V39__dispute_refunds.sql` supporting dispute refund allocations and ledger adjustments.
- `SupplierRefundLedger`: automatic deduction of approved dispute refunds from supplier settlement balances in `SettlementService`.
- Currency formatting utility `Rupees` rendering formatted INR amounts with two decimals in user notifications and messages.

### [feat/razorpay-11-dispute-refunds] - PR / Step 11
#### Added
- Database migration `V38__refunds_to_wallet.sql` for wallet refund destinations.
- Wallet refund routing: refunds credit restaurant wallet balances immediately for frictionless re-orders.
- `WalletWithdrawalService`: bank payouts restricted strictly back to the source payment method.

### [feat/razorpay-10-capture-at-dispatch] - PR / Step 10
#### Added
- Two-phase authorization & capture model (D-102): authorization holds placed at order creation; funds captured only upon order readiness / dispatch in `OrderReleaseService`.
- Background reconciliation jobs to release/expire uncaptured authorization holds when orders are cancelled.

### [feat/razorpay-9-payment-intent-lookup] - PR / Step 9
#### Added
- Payment intent lookup endpoint: `GET /api/v1/orders/{id}/payment-intent` returning checkout parameters (order ID, amount in paise, currency, Razorpay key).

### [feat/razorpay-8-review-fixes] - PR / Step 8
#### Added
- Database migration `V37__refund_attempts.sql` for tracking max refund retry counts.
- `ProductionProviderGuard`: strict startup check ensuring mock payment providers cannot be used in production environments.
- Resilient failure recovery: secondary declined attempts do not corrupt previously successful order authorizations.

### [feat/razorpay-7-payment-tracing] - PR / Step 7
#### Added
- Distributed correlation tracing with `TraceScope` and `CorrelatedTaskScheduler` propagating payment IDs across HTTP endpoints, worker threads, and webhook handlers.

### [feat/razorpay-3-small-fixes] - PR / Step 3
#### Added
- Sandbox top-up protection in `WalletController`: prevents test wallet credits when real payment provider mode is active.
- Quote token reuse prevention in `DeliveryFeeQuoteService`.

### [feat/razorpay-2-hardening] - PR / Step 2
#### Added
- Non-blocking external HTTP calls: connection release before invoking external Razorpay endpoints.
- Pessimistic row-level locking on payment transactions to eliminate concurrent update race conditions.

### [feat/razorpay-1-adapter] - PR / Step 1
#### Added
- Production `RazorpayPaymentProvider` adhering strictly to Razorpay's API contracts (D-098).
- Payment ownership enforcement ensuring orders are funded only by their own verified payment intent.
- `docs/RAZORPAY.md` and test suite `PaymentOwnershipTest` & `RazorpayPaymentProviderTest`.

---

## [Delivery & Logistics Platform Integrations]

## [feat/pidge-16-outlet-active-deliveries-and-arrival-radar] - PR 16
### Added
- Outlet Delivery Radar endpoint: `GET /api/v1/outlets/{outletId}/deliveries/radar`.
  - Answers *“Which one is approaching the kitchen?”* by ranking active deliveries by arrival urgency (`AT_KITCHEN_DOOR` first, then `APPROACHING` by shortest ETA).
  - Answers *“Is this supplier still on schedule?”* with real-time `ScheduleStatus` (`ON_SCHEDULE`, `RUNNING_LATE`, `CRITICALLY_DELAYED`) and exact `minutesOverdue`.
  - Answers *“Did the driver call? Is there a problem?”* via `DriverInfo` contacts, `ProblemDetails` (`hasProblem`, `problemType`, carrier exceptions, stale GPS detection).
  - Answers *“Which orders should I check in, receive, or escalate?”* with an actionable `KitchenAction` classifier (`CHECK_IN`, `MEET_DRIVER`, `PREPARE_DOCK`, `CALL_DRIVER`, `ESCALATE`, `MONITOR`).
  - Aggregates `RadarSummaryResponse` counts (`totalActive`, `atDoorCount`, `approachingCount`, `delayedCount`, `pendingCheckInCount`, `requiresEscalationCount`).
- Outlet Delivery Search & Pagination endpoint: `GET /api/v1/outlets/{outletId}/deliveries?page=0&size=10&status=...`.
  - Scoped to outlet with `Permissions.ORDER_VIEW` access control and tenant isolation.
- `OutletDeliveryRadarService` situational engine and comprehensive unit test suite `OutletDeliveryRadarServiceTest`.

---

## [feat/pidge-15-admin-delivery-search-and-late-tracking] - PR 15
### Added
- Operations Delivery Search & Listing endpoint: `GET /api/v1/admin/deliveries?page=0&size=10&status=...&providerCode=...&supplierStoreId=...`.
  - Lists last 10 deliveries by default (newest first) with full pagination metadata (`PagedResponse`).
  - Joins order number (`supplier_order`), outlet name (`outlet`), supplier store name (`supplier_store`), supplier org name (`supplier_organization`), and delivery provider name (`delivery_provider`).
- Dedicated Missed ETA Tracking endpoint: `GET /api/v1/admin/deliveries/late?liveOnly=true&page=0&size=10`.
  - Computes `minutesOverdue` live using `estimated_arrival_at` for active deliveries or historical SLA overruns for completed deliveries.
  - Sorts automatically by urgency (highest `minutesOverdue` first).
- `AdminDeliverySummaryResponse` and `PagedResponse` DTO records.
- Unit test coverage in `AdminDeliveryServiceTest`.

---

## [feat/pidge-14-webhook-signature-verification] - PR 14
### Added
- Environment-injected webhook secret binding: `costonomy.mp.delivery.webhook-secret=${DELIVERY_WEBHOOK_SECRET:${PIDGE_WEBHOOK_SECRET:}}`.
- Unit test suite (`PidgeWebhookServiceTest`) testing HMAC-SHA256 verification, forged signatures, payload tampering, and local-dev pass-through.
### Security
- Constant-time HMAC comparison using `MessageDigest.isEqual` to prevent side-channel timing attacks.
- Outbox audit logging (`PIDGE_WEBHOOK_REJECTED`) when signature fails.

---

## [feat/pidge-13-bulk-delivery-export] - PR 13
### Added
- Admin streaming bulk delivery export endpoint: `GET /api/v1/admin/deliveries/export?format=csv|json&since=YYYY-MM-DD&status=...`.
- `DeliveryExportRow` DTO containing flattened delivery journey, vehicle categorization, fees, timestamps, and failure codes.
- `AdminDeliveryExportService` streaming service respecting `costonomy.mp.admin.export.max-rows=10000` memory guard.
- RFC 4180 CSV escaping and unit test suite (`AdminDeliveryExportServiceTest`).

---

## [feat/pidge-12-provider-metrics] - PR 12
### Added
- Database migration `V27__delivery_provider_metrics.sql` for 2-hour rolling performance snapshots.
- `DeliveryProviderMetrics` JPA entity tracking average latency (`avg_latency_ms`), 95th percentile latency (`p95_latency_ms`), total cost (`total_cost_inr`), and order count.
- `DeliveryMetricsAggregationService` computing latencies from `DRIVER_ASSIGNED` to `DELIVERED` and ledger sums.
- `DeliveryMetricsJob` running every 2 hours with ShedLock protection (`0 0 0/2 * * *`).
- Admin endpoints: `GET /api/v1/admin/deliveries/providers/metrics` and `GET /api/v1/admin/deliveries/providers/{code}/metrics`.
- `DeliveryMetricsAggregationServiceTest` unit tests verifying P95 percentile calculation.

---

## [feat/pidge-11-delivery-rate-limiting] - PR 11
### Added
- Per-endpoint rate limits for delivery API:
  - `delivery-request`: `POST /api/v1/supplier-orders/**` (10/min per user)
  - `delivery-reassign`: `POST /api/v1/deliveries/**` (5/min per user, covers cancels + reassignments)
  - `delivery-read`: `GET /api/v1/deliveries/**` (60/min per user for live tracking polls)
- Environment-configurable variables in `application.properties`:
  - `costonomy.mp.ratelimit.delivery-request=10`
  - `costonomy.mp.ratelimit.delivery-reassign=5`
  - `costonomy.mp.ratelimit.delivery-cancel=5`
  - `costonomy.mp.ratelimit.delivery-read=60`
- `DeliveryRateLimitPoliciesTest` unit suite verifying path routing and zero-limit test bypass.

---

## [feat/pidge-10-provider-stats] - PR 10
### Added
- Database migration `V26__delivery_provider_stats.sql` for nightly provider reliability aggregation.
- `DeliveryProviderStats` JPA entity with derived rates: `cancellationRate()`, `etaBreachRate()`, `overallFailureRate()`.
- `DeliveryStatsAggregationService` combining `delivery`, `delivery_event`, `delivery_quote`, and `delivery_ledger`.
- `DeliveryStatsJob` scheduled at 02:00 UTC daily under ShedLock.
- Admin endpoints: `GET /providers/stats`, `GET /providers/{code}/stats`, and `POST /providers/stats/aggregate`.
- Unit tests (`DeliveryProviderStatsTest`) for derived reliability percentages.

---

## [feat/pidge-09-docs-and-specs] - PR 09
### Added
- Architecture documentation updates in `docs/specs/06-delivery.md` detailing Pidge Smart Dispatch, waterfall cascade sequence, weight thresholds, and ledger schema.
- Event-driven waterfall timer explanation and zero-polling architecture guidelines.

---

## [feat/pidge-08-integration-tests] - PR 08
### Added
- `PidgeDeliveryFlowIT` Testcontainers integration test suite using MySQL 8.
- End-to-end testing of webhook ingestion, rider details parsing, live tracking URL extraction, signature verification, and admin financial ledger inspection.

---

## [feat/pidge-07-ops-reassign] - PR 07
### Added
- Operations delivery inspection and manual waterfall escalation endpoints:
  - `GET /api/v1/admin/deliveries/{id}/ledger`
  - `POST /api/v1/admin/deliveries/{id}/force-waterfall`
- `AdminDeliveryController` and `AdminDeliveryService` with `DELIVERY_INSPECT` and `DELIVERY_OPERATE` permission enforcement.

---

## [feat/pidge-06-tracking-notifications] - PR 06
### Added
- Migration `V25__delivery_tracking_url.sql` adding `tracking_url` column to `delivery` table.
- Notification outbox integration: includes live tracking URL in customer and restaurant SMS/push notifications.

---

## [feat/pidge-05-dispatch-triggers] - PR 05
### Added
- `DeliveryDispatchListener` listening to domain events:
  - `ORDER_ACCEPTED` triggers provider quoting.
  - `PREPARING` locks vehicle classification.
  - `READY_FOR_PICKUP` triggers auto-dispatch booking if enabled (`costonomy.mp.delivery.auto-dispatch.enabled=true`).

---

## [feat/pidge-04-ledger-and-waterfall] - PR 04
### Added
- Migration `V24__delivery_vehicle_and_weight.sql` adding `delivery_ledger` table.
- `DeliveryWaterfallService`: event-driven timeout handling without polling loops; cascades unassigned bookings to next best carrier quote.
- Central ledger audit entries (`DeliveryLedgerEntry`) recording `QUOTED`, `BOOKED`, `ADJUSTED`, and `REFUNDED` entries.

---

## [feat/pidge-03-webhooks-and-security] - PR 03
### Added
- `/api/v1/webhooks/delivery/pidge` public webhook ingest endpoint.
- `PidgeWebhookService`: HMAC-SHA256 signature verification, deduplication on provider event ID, and safe state machine transitions.

---

## [feat/pidge-02-provider-adapter] - PR 02
### Added
- `PidgeDeliveryProvider` adapter implementing domain `DeliveryProvider` port.
- Resilient `PidgeApiClient` with token bucket rate limiting (20 RPS) and circuit breaker guardrails.
- `PidgeProperties` with timeout and API credentials.

---

## [feat/pidge-01-schema-and-vehicle-sizing] - PR 01
### Added
- `VehicleType` enum: `TWO_WHEELER` (<= 20 kg), `THREE_WHEELER` (20-100 kg), `FOUR_WHEELER_TRUCK` (> 100 kg).
- Dynamic payload calculation aggregating order line items: $\sum (\text{item.quantity} \times \text{sku.effectiveWeightKg})$.
- Added `weight_kg`, `volume_cbm`, and `vehicle_type` to `delivery` and `delivery_quote` schemas.
