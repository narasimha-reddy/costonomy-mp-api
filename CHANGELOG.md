# Changelog

All notable changes across the platform (Procurement, Wallet, Payments, Delivery, Security, and Architecture) are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Credit]

### [Supplier receipts] - B5 (D-134)
#### Added
- [Credit] `POST /api/v1/credit/agreements/{id}/payments/preview {amount, invoiceIds?}` (a pure read: allocations with `statusAfter`, the line's position after, and `pendingClaims` warnings) and `POST /api/v1/credit/agreements/{id}/payments` with `Idempotency-Key` and `{amount, method, reference, paidOn, note, invoiceIds?, allowDuplicateReference?}` (201 `{receiptId, amount, method, reference, paidOn, allocations[], agreement{due, overdue, available, status}}`). One `credit_repayment` receipt (source `SUPPLIER_RECORDED`) split oldest due date first (ties by invoice id), one `credit_payment` per invoice through the shared `applyPayment`, in one transaction. `method` is CASH, UPI, BANK_TRANSFER, CHEQUE or CARD (ADJUSTMENT is refused here); `reference` (4 to 64 characters) is required for UPI, BANK_TRANSFER and CHEQUE; `paidOn` is an India day, not in the future and not before the oldest targeted invoice was issued. Errors: `CREDIT_OVERPAYMENT` (422, details `outstanding`), `CREDIT_DUPLICATE_REFERENCE` (409, details `receiptId`, `paidOn`, `amount`; the same reference in this store within 90 days unless `allowDuplicateReference`), `IDEMPOTENCY_KEY_REUSE` (409). Needs `CREDIT_COLLECT` or `CREDIT_MODIFY` on the store; others get 404. The single-invoice endpoint is unchanged.
- [Credit] V58: `credit_repayment.source` widened to VARCHAR(32), new `method`, `reference`, `paid_on`, `note`; `ix_credit_payment_reference` for the duplicate check.

### [feat/sup-b9-b12-lifecycle-context] - Close a credit line (B9, D-136) and offer expiry (D-137)
#### Added
- [Credit] `ClaimResponse` (supplier inbox `GET /supplier-stores/{id}/credit/claims`, agreement claims, invoice detail, claim replies) gains `ageDays`, `stale` (7+ days waiting), `invoiceOutstanding`, `invoiceOpenClaimsAmount`, `invoiceOtherOpenClaimsAmount`, `possibleDuplicateOf` and `possibleDuplicateKind` (D-139). Additive.
- [Credit] `GET /api/v1/supplier-stores/{storeId}/credit/requests/{agreementId}/context` (D-139): this store's own history with the requesting outlet (90-day orders, average, last order, past line status and history, earlier overdue count); never another supplier's data. Needs `CREDIT_REQUEST_VIEW`; others 404.
- [Credit] `POST /api/v1/credit/invoices/{id}/extend-due {newDueDate, reason}` (D-138): later only, at most 60 days past the original due date, not on settled invoices; an OVERDUE invoice that is no longer late goes back to ISSUED/PARTIALLY_PAID, a sweep suspension it caused lifts, the restaurant is told (`CreditDueDateExtended`). Idempotent via `Idempotency-Key`. V61 `credit_due_extension`; the invoice detail gains `extensions[]`.
- [Credit] V61 `credit_agreement.offer_made_at`. An APPROVED offer the restaurant has not accepted for 14 India days becomes EXPIRED (hourly job, `costonomy.mp.credit.offer-expiry-interval`); both sides are told (`CreditOfferExpired`), an accept racing the job has exactly one winner, and the restaurant may ask again. `AgreementResponse` gains `offerMadeAt` and `offerExpiresOn` (null unless APPROVED).
- [Credit] `POST /api/v1/credit/agreements/{id}/close {reason}`: a supplier closes an ACTIVE or SUSPENDED line. Refused with 409 `INVALID_STATE_TRANSITION` (details `owed`, `reserved`) while anything is owed or held for an order in flight. The restaurant is told (`CreditClosed`), a closed line takes no orders, and the restaurant may ask again. Needs `CREDIT_MODIFY` on the store; others get 404.

### [Supplier payouts list] - B4
#### Added
- [Credit] `GET /api/v1/supplier-stores/{storeId}/credit/payouts?status=PENDING|APPLIED|ALL&from&to&page&size` and `GET .../payouts/{payoutId}`: the wallet repayments Mandi collected for the store, with gross, the commission as snapshotted at repayment time, net, the invoices each settled, and the settlement once applied; plus `summary {pendingNet, appliedNetThisMonth}`. Read-only, no schema change. Needs `CREDIT_VIEW` or `SETTLEMENT_VIEW` on the store; others get 404.

### [feat/sup-b1-b2-permissions-reinstate] - Supplier collect permission (D-132) and manual-reinstate floor (D-133)
- V57: `CREDIT_COLLECT` (SUP_OWNER, SUP_ADMIN, SUP_FINANCE_STAFF, SUP_STORE_MANAGER) and `CREDIT_WRITE_OFF` (SUP_OWNER, SUP_ADMIN), granted explicitly. Recording a payment and confirming or rejecting a claim now accept `CREDIT_COLLECT` or `CREDIT_MODIFY`; terms, suspend and reinstate stay `CREDIT_MODIFY`.

- V57: `credit_agreement.overdue_floor`. A supplier's manual reinstate of a SYSTEM (overdue-sweep) suspension stores the overdue amount at that moment; the sweep suspends again only above `max(maxOverdueAmount, overdue_floor)`, and the floor clears when overdue returns to zero. No API change.

---

### [feat/sup-b3-receivables-reads] - supplier receivables read model (B3)
#### Added
- `GET /supplier-stores/{storeId}/credit/receivables`, `/receivables/restaurants`, `/ageing` and `/payments`, and `GET /credit/agreements/{id}/payments`: server-computed totals, restaurant rows, ageing buckets and payment feeds for the supplier's Receivables screens. India dates from `creditClock`; overdue by `CreditDueState`, never the status column. No migration.

## [Architecture & Security Audit]

### [Security Audit Report] - 2026-10-02
#### Added
- [`docs/SECURITY_AUDIT_REPORT.md`](file:///Users/rac/Documents/costonomy_projects/marketplace_be/costonomy-mp-api/docs/SECURITY_AUDIT_REPORT.md): Comprehensive code-level penetration testing report evaluating identity boundaries, financial invariants, multi-tenant IDOR isolation, and state machine integrity.
#### Security
- Evaluated ATO vectors: verified constant-time BCrypt/HMAC matching, independent `REQUIRES_NEW` transaction commits for OTP attempts in `OtpAttemptStore`, and automatic whole-lineage session revocation upon refresh token reuse.
- Evaluated Financial Invariants: proved zero money creation (`paymentProvider != 'MOCK'` gate in production), zero double-spending (`UPDATE ... WHERE balance >= amount` and `SELECT FOR UPDATE` pessimistic locks), and strictly FIFO pinned payouts (gateway refunds).
- Identified and remediated 3 hardening areas: VULN-01 (IP rate limiting on OTP requests), VULN-02 (300-second webhook freshness window), and VULN-03 (storage capability isolation).

### [docs/architecture-sequence-diagrams] - Architecture Integration PR
#### Added
- Complete endpoint and domain sequence diagrams under `docs/architecture/sequence-diagrams/`:
  - `01-payment-and-wallet.md`: Razorpay orders, checkout signature verification, top-up capture sweep, FIFO withdrawals, dispute refunds, and idempotency boundaries.
  - `02-delivery-and-logistics.md`: Multi-carrier dispatch waterfall, vehicle auto-sizing, courier tracking webhooks, delivery milestone state transitions, and arrival radar.
  - `03-intent-and-procurement.md`: Cart creation, line price snapshotting, multi-supplier order splitting, and atomic transitions.
  - `04-trust-and-disputes.md`: Evidence upload, dispute life-cycle, manual mediation, and refund settlements.
  - `05-credit-and-settlement.md`: BNPL supplier credit line underwriting, invoice settlements, and debit adjustments.
  - `06-identity-and-orgs.md`: Passwordless OTP flow with MSG91, attempt tracking in isolated transactions, JWT issuance, and refresh token rotation with theft detection.
  - `07-discovery-and-catalog.md`: Product listing, supplier stock checks, and catalog search.
  - `08-communication-and-admin.md`: Outbox event publishing, notification dispatch, and tenant audit trails.
  - `README.md`: Architecture directory map and visual guide.

---

## [Procurement & Open Requests]

### [PR #17] [feat/edit-open-request-quantities]
#### Added
- Modification support for open procurement requests prior to supplier acceptance.
- Real-time cart line revalidation against active supplier catalog pricing.
#### Changed
- `ProcurementRequestService`: enforces state checks ensuring quantities cannot be altered once a supplier has committed to fulfillment.

---

## [Wallet & QuickScan Payments]

### [PR #15] [feat/wallet-2-history-statements] - Wallet History & Statements (D-108)
#### Added
- Database migration `V42__wallet_top_up_payment_method.sql` recording payment method instruments (card last 4, UPI).
- `WalletHistoryService`: paginated ledger history with cursor-based navigation, Asia/Kolkata month aggregates, and entry kind/status filters.
- `WalletStatementService`: PDF and CSV statement rendering engine (`WalletStatementPdf`, `WalletStatementCsv`) with strict mathematical reconciliation enforcement ($\text{Opening} + \text{Added} - \text{Spent} = \text{Closing}$).
- Download endpoint `GET /api/v1/outlets/{outletId}/wallet/statement?range=...&format=PDF|CSV` (max 20,000 entries guard).
- Integration test suites: `WalletHistoryIT`, `WalletStatementIT`, `StatementPeriodTest`, `WalletStatementFilesTest`.
#### Security
- Scoped strictly to outlet with `Permissions.ORDER_VIEW` access control and live database authorization.
- Masked instrument details: never stores or renders full card numbers or sensitive credentials.

### [PR #14] [feat/wallet-1-razorpay-top-up] - Razorpay Wallet Top-Ups (D-107)
#### Added
- Database migration `V41__wallet_top_up.sql` creating `wallet_top_up` ledger table.
- `WalletTopUpService`: manages the complete lifecycle of prepaid balance loading via Razorpay checkout.
  - `POST /api/v1/outlets/{outletId}/wallet/top-ups`: validates tiered limits, records top-up intent, and opens a Razorpay order configured for instant capture.
  - `POST /api/v1/outlets/{outletId}/wallet/top-ups/{topUpId}/confirm`: verifies checkout HMAC signature and inspects provider payment state before crediting.
  - `GET /api/v1/outlets/{outletId}/wallet/top-ups/{topUpId}`: returns live top-up status.
- `WalletTopUpJobs`: background reconciliation sweeper crediting unconfirmed captured payments and expiring abandoned intents after 24 hours.
- Integration tests: `WalletTopUpIT`, `WalletTopUpLimitsIT`, `WalletLimitsTest`.
#### Security
- Out-of-band verification: backend calls Razorpay API directly (`provider.inspect`) rather than trusting client-submitted payload amounts.
- Over-limit protection: if crediting exceeds wallet max balance limits, funds are automatically refunded back to the originating funding instrument.

### [PR #13] [feat/quickscan-1-wallet-payments] - QuickScan Wallet Payments (D-106)
#### Added
- Database migration `V40__quickscan.sql` creating `quickscan_payment` table and granting `QUICKSCAN_PAY` permission.
- `QuickScanService`: allows restaurants to scan third-party merchant UPI QR codes and settle payments directly from their wallet balance.
- Payout integration port `PayoutProvider` with testbed sandbox simulation `MockPayoutProvider`.
- `QuickScanController`: endpoints `GET /config`, `POST /pay`, `GET /payments`, `GET /payments/{id}`.
- Integration tests: `QuickScanFlowIT`, `QuickScanDisabledIT`.
#### Security
- Gated rollout: feature is controlled via `costonomy.mp.quickscan.enabled=false` by default; `ProductionProviderGuard` blocks startup if mock payout providers are active in production profiles.
- Pessimistic locking: wallet is locked (`SELECT FOR UPDATE`) before balance debit, eliminating concurrency race conditions.

---

## [Razorpay Core Payments]

### [PR #12] [feat/razorpay-17-withdrawal-failure-reversal] - Step 17 (D-110)
#### Added
- Database migration `V44__withdrawal_failure_reversal.sql` adding failure audit states and operator resolution tracking.
- `WithdrawalReversalService`: verified bank proof validation before restoring failed withdrawals back to restaurant wallets.
- `AdminRefundController`: dedicated administrative endpoints (`/api/v1/admin/refunds/...`) for manual audit review and override exits.
- Concurrency utility `DeadlockRetry` with unit and integration tests.
#### Security
- Prevents double-credit reversals: reversal reference `withdrawal-reversal-{refundId}` is unique, guaranteeing single crediting even under concurrent retries.

### [PR #11] [feat/razorpay-16-upi-cancel-refund] - Step 16 (D-109)
#### Added
- Database migration `V43__debited_payment_on_cancelled_order.sql`.
- `CancellationService`: automated refund orchestration for debited/auto-captured orders (UPI/cards) cancelled prior to fulfillment.
- Periodic background worker `cancelDebitedPayments` in `PaymentJobs` running every 15s to auto-refund cancelled orders.
- Configuration parameters: `costonomy.mp.razorpay.manual-expiry-minutes` and `costonomy.mp.razorpay.cancel-refund-speed`.

### [PR #10] [feat/razorpay-15-order-payment-status] - Step 15
#### Added
- Dynamic payment status resolution on `SupplierOrder`: live evaluation of payment state from the underlying funding mechanism (Razorpay, credit line, or wallet).
- Updated `SupplierOrderMapper` and `CreditFundingAdapter` with live payment state queries.

### [PR #9] [feat/razorpay-12-dispute-refund-requests] - Step 12
#### Added
- Database migration `V39__dispute_refunds.sql` supporting dispute refund allocations and ledger adjustments.
- `SupplierRefundLedger`: automatic deduction of approved dispute refunds from supplier settlement balances in `SettlementService`.
- Currency formatting utility `Rupees` rendering formatted INR amounts with two decimals in user notifications and messages.

### [PR #8] [feat/razorpay-11-dispute-refunds] - Step 11
#### Added
- Database migration `V38__refunds_to_wallet.sql` for wallet refund destinations.
- Wallet refund routing: refunds credit restaurant wallet balances immediately for frictionless re-orders.
- `WalletWithdrawalService`: bank payouts restricted strictly back to the source payment method.

### [PR #7] [feat/razorpay-10-capture-at-dispatch] - Step 10 (D-102)
#### Added
- Two-phase authorization & capture model: authorization holds placed at order creation; funds captured only upon order readiness / dispatch in `OrderReleaseService`.
- Background reconciliation jobs to release/expire uncaptured authorization holds when orders are cancelled.

### [PR #6] [feat/razorpay-9-payment-intent-lookup] - Step 9
#### Added
- Payment intent lookup endpoint: `GET /api/v1/orders/{id}/payment-intent` returning checkout parameters (order ID, amount in paise, currency, Razorpay key).

### [PR #5] [feat/razorpay-8-review-fixes] - Step 8
#### Added
- Database migration `V37__refund_attempts.sql` for tracking max refund retry counts.
- `ProductionProviderGuard`: strict startup check ensuring mock payment providers cannot be used in production environments.
- Resilient failure recovery: secondary declined attempts do not corrupt previously successful order authorizations.

### [PR #4] [feat/razorpay-7-payment-tracing] - Step 7
#### Added
- Distributed correlation tracing with `TraceScope` and `CorrelatedTaskScheduler` propagating payment IDs across HTTP endpoints, worker threads, and webhook handlers.

### [PR #3] [feat/razorpay-3-small-fixes] - Step 3
#### Added
- Sandbox top-up protection in `WalletController`: prevents test wallet credits when real payment provider mode is active.
- Quote token reuse prevention in `DeliveryFeeQuoteService`.

### [PR #2] [feat/razorpay-2-hardening] - Step 2
#### Added
- Non-blocking external HTTP calls: connection release before invoking external Razorpay endpoints.
- Pessimistic row-level locking on payment transactions to eliminate concurrent update race conditions.

### [PR #1] [feat/razorpay-1-adapter] - Step 1 (D-098)
#### Added
- Production `RazorpayPaymentProvider` adhering strictly to Razorpay's API contracts.
- Payment ownership enforcement ensuring orders are funded only by their own verified payment intent.
- `docs/RAZORPAY.md` and test suite `PaymentOwnershipTest` & `RazorpayPaymentProviderTest`.

---

## [Delivery & Logistics Platform Integrations (Pidge)]

### [PR 16] [feat/pidge-16-outlet-active-deliveries-and-arrival-radar]
#### Added
- Outlet Delivery Radar endpoint: `GET /api/v1/outlets/{outletId}/deliveries/radar`.
  - Prioritizes active deliveries by urgency (`AT_KITCHEN_DOOR` first, then `APPROACHING` by shortest ETA).
  - Evaluates `ScheduleStatus` (`ON_SCHEDULE`, `RUNNING_LATE`, `CRITICALLY_DELAYED`) with exact `minutesOverdue`.
  - Surfaces driver contact info, carrier problem alerts, and actionable `KitchenAction` recommendations.
- Outlet Delivery Search endpoint: `GET /api/v1/outlets/{outletId}/deliveries?page=0&size=10&status=...`.
- `OutletDeliveryRadarService` situational engine and unit tests `OutletDeliveryRadarServiceTest`.

### [PR 15] [feat/pidge-15-admin-delivery-search-and-late-tracking]
#### Added
- Operations Delivery Search endpoint: `GET /api/v1/admin/deliveries?page=0&size=10&status=...`.
- Missed ETA Tracking endpoint: `GET /api/v1/admin/deliveries/late?liveOnly=true&page=0&size=10`.
- Unit test coverage in `AdminDeliveryServiceTest`.

### [PR 14] [feat/pidge-14-webhook-signature-verification]
#### Added
- Webhook secret configuration: `costonomy.mp.delivery.webhook-secret`.
- Unit test suite `PidgeWebhookServiceTest` testing HMAC-SHA256 verification and payload tampering.
#### Security
- Constant-time HMAC comparison using `MessageDigest.isEqual` to prevent timing attacks.

### [PR 13] [feat/pidge-13-bulk-delivery-export]
#### Added
- Streaming CSV and JSON delivery export endpoint: `GET /api/v1/admin/deliveries/export`.
- `AdminDeliveryExportService` streaming service respecting 10,000-row memory protection limits.

### [PR 12] [feat/pidge-12-provider-metrics]
#### Added
- Database migration `V27__delivery_provider_metrics.sql` for 2-hour rolling performance snapshots.
- `DeliveryMetricsJob` aggregating P95 and average latencies from dispatch to delivery.

### [PR 11] [feat/pidge-11-delivery-rate-limiting]
#### Added
- Per-endpoint rate limits for delivery API:
  - `delivery-request`: `POST /api/v1/supplier-orders/**` (10/min per user)
  - `delivery-reassign`: `POST /api/v1/deliveries/**` (5/min per user)
  - `delivery-read`: `GET /api/v1/deliveries/**` (60/min per user)

### [PR 10] [feat/pidge-10-provider-stats]
#### Added
- Database migration `V26__delivery_provider_stats.sql` for nightly provider reliability aggregation.
- `DeliveryStatsJob` scheduled at 02:00 UTC under ShedLock.

### [PR 09] [feat/pidge-09-docs-and-specs]
#### Added
- Architecture documentation in `docs/specs/06-delivery.md` detailing Pidge Smart Dispatch, waterfall cascade sequence, weight thresholds, and ledger schema.

### [PR 08] [feat/pidge-08-integration-tests]
#### Added
- Testcontainers integration test suite `PidgeDeliveryFlowIT` using MySQL 8.

### [PR 07] [feat/pidge-07-ops-reassign]
#### Added
- Operations delivery inspection and manual waterfall escalation endpoints:
  - `GET /api/v1/admin/deliveries/{id}/ledger`
  - `POST /api/v1/admin/deliveries/{id}/force-waterfall`

### [PR 06] [feat/pidge-06-tracking-notifications]
#### Added
- Database migration `V25__delivery_tracking_url.sql` adding `tracking_url` column.
- Live tracking URL injection into push notifications and SMS templates.

### [PR 05] [feat/pidge-05-dispatch-triggers]
#### Added
- Event-driven dispatch triggers: `ORDER_ACCEPTED` quotes delivery; `READY_FOR_PICKUP` triggers auto-dispatch booking.

### [PR 04] [feat/pidge-04-ledger-and-waterfall]
#### Added
- Database migration `V24__delivery_vehicle_and_weight.sql` creating `delivery_ledger`.
- Event-driven waterfall cascading to fallback couriers upon timeout.

### [PR 03] [feat/pidge-03-webhooks-and-security]
#### Added
- `/api/v1/webhooks/delivery/pidge` public webhook ingest endpoint with HMAC verification and deduplication.

### [PR 02] [feat/pidge-02-provider-adapter]
#### Added
- `PidgeDeliveryProvider` adapter with token bucket rate limiting (20 RPS) and circuit breaker guards.

### [PR 01] [feat/pidge-01-schema-and-vehicle-sizing]
#### Added
- Vehicle classification: `TWO_WHEELER` (<= 20 kg), `THREE_WHEELER` (20-100 kg), `FOUR_WHEELER_TRUCK` (> 100 kg).
- Dynamic line item weight calculation: $\sum (\text{item.quantity} \times \text{sku.effectiveWeightKg})$.
