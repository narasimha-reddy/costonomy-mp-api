# Implementation Traceability

This document is the contract for proving that requirements are implemented.

## 1. Status values

Use:

- `NOT_STARTED`
- `IN_PROGRESS`
- `IMPLEMENTED`
- `TESTED`
- `BLOCKED`
- `DEFERRED`

## 2. Traceability matrix

| ID | Requirement | Source | Backend | DB | API | Mobile | Tests | Status |
|---|---|---|---|---|---|---|---|---|
| AUTH-001 | OTP login | 01,04 | identity: OtpService, AuthService, OtpProvider (MSG91 + Mock) | V1 users, otp_verification | POST /auth/otp/request, /auth/otp/verify | Login/OTP screens pending | PhoneNumbersTest, AuthFlowIT$Login (9) | TESTED |
| AUTH-002 | JWT session | 01,04 | identity: JwtService, RefreshTokenService + Store | V1 refresh_token | POST /auth/refresh, /auth/logout, GET /auth/me | session handling pending | JwtServiceTest, AuthFlowIT$Refresh (4), $Protected (2) | TESTED |
| ORG-001 | Restaurant/outlet | 01,02,04 | restaurant: RestaurantService | V3 restaurant, outlet, restaurant_user | /restaurants, /outlets, /outlets/{id}/users | setup/outlet selector pending | TenantIsolationIT$Restaurants (6) | TESTED |
| SUP-001 | Supplier lifecycle | 01,03,04 | supplier: SupplierService, SupplierLifecycleStatus | V4 supplier_organization, supplier_store | /suppliers, /supplier-stores | onboarding pending | SupplierLifecycleTest (7), TenantIsolationIT$Suppliers (6) | TESTED |
| SUP-002 | GST verification | 01,09 | supplier: SupplierService.submitVerification/reviewVerification | V4 supplier_verification | POST /suppliers/{id}/verification, /admin/suppliers/... | verification screen pending | TenantIsolationIT$Suppliers | TESTED |
| CAT-001 | Canonical products | 01,02,04 | catalog: CatalogQueryService, Normalization | V6 canonical_product/alias/category/brand, V7 seed | /categories, /brands, /products, /products/{id}/offers | product screens pending | NormalizationTest (5), CatalogIT$Canonical (5), $Comparison (5) | TESTED |
| CAT-002 | Supplier SKU and offer | 01,02,04 | catalog: SupplierCatalogService | V6 supplier_sku, supplier_offer | /supplier-stores/{id}/skus, /supplier-skus/{id} | catalog screen pending | CatalogIT$Skus (6) | TESTED |
| CAT-003 | Bulk import | 01,04,05 | catalog: CatalogImportService, CatalogFileParser | V6 catalog_import, catalog_import_row | /catalog/import, /catalog/imports/{id} | bulk import stepper pending | ImportParsingTest (11), CatalogIT$Import (9) | TESTED |
| SRCH-001 | Product search | 01,07 | catalog: CatalogQueryService; discovery: SearchService | V6 ix_canonical_normalized | /search/products, /search/suggestions, /search/suppliers | Search screen pending | CatalogIT$Canonical (5), RecommendationIT$Search (4) | TESTED |
| REC-001 | Best-value recommendation | 01,07 | discovery: BestValueScorer, RecommendationService, OrderDerivedPerformanceProvider (all five signals measured since Phase 13) | V8 ranking weights in app_config | GET /products/{id}/recommendations | Recommended Procurement pending | BestValueScorerTest (20), RecommendationIT$Filtering (7), $Honesty (3), TrustFlowIT$Performance (1) | TESTED |
| REC-002 | Serviceability | 07,41 | discovery: Serviceability, RecommendationService | V4 supplier_delivery_policy | applied in recommendations | — | ServiceabilityTest (7), RecommendationIT$Filtering | TESTED |
| REQ-001 | Requirement lifecycle | 01,03,04 | procurement: RequirementService, RequirementStatus | V9 requirement, requirement_item | /outlets/{id}/requirements, /requirements/{id} | Requirements screen pending | LifecycleTest, ProcurementIT$Requirements (3) | TESTED |
| PROC-001 | Procurement, cart and checkout | 01,03,04 | procurement: ProcurementService, Pricing | V9 procurement, procurement_item | /cart, /procurements/{id}/validate | Cart/Checkout pending | PricingTest (7), ProcurementIT$Cart (4), $PriceChanges (5) | TESTED |
| PROC-002 | Multi-supplier split | 01,03,04 | procurement: ProcurementSubmitter, OrderReleaseService, OrderFundingPort | V10 supplier_order, supplier_order_item | POST /procurements/{id}/submit | Orders screen pending | ProcurementIT$Submission (6) | TESTED |
| PROC-003 | Approval policy | 01,03,28 | procurement: ApprovalPolicyEvaluator | V3 procurement_policy | /procurements/{id}/approve, /reject | Approval screen pending | ProcurementIT$Approval (6) | TESTED |
| ORD-001 | Supplier acceptance | 01,03,04 | procurement: SupplierOrderService, SupplierOrderTransitions | V10 supplier_order | /supplier-orders/{id}/accept, /reject, /preparing, /ready | Supplier Home/New Order pending | SupplierAcceptanceIT$Acceptance (4), $Concurrency (3) | TESTED |
| ORD-002 | Partial acceptance | 01,03,04 | procurement: SupplierOrderTransitions.partialAccept | V10 supplier_order_item | POST /supplier-orders/{id}/partial-accept | Partial Acceptance screen pending | SupplierAcceptanceIT$PartialAcceptance (6) | TESTED |
| ORD-003 | Supplier timeout | 01,13,38 | procurement: SupplierOrderTimeoutJob | V10 ix_supplier_order_deadline | — | countdown pending | SupplierAcceptanceIT$RejectionAndTimeout (6) | TESTED |
| ORD-004 | Alternative sourcing | 01,15 | procurement: AlternativeSourcingService | V9 requirement_item | POST /requirements/{id}/find-suppliers | alternatives screen pending | SupplierAcceptanceIT$PartialAcceptance | TESTED |
| PAY-001 | Provider abstraction | 01,04,06 | payment: PaymentProvider port, Razorpay + Mock adapters, PaymentService | V11 payment, payment_transaction | POST /payments/{id}/confirm, GET /payments/{id} | payment sheet pending | PaymentLifecycleTest (11), PaymentFlowIT$FundingGate (4) | TESTED |
| PLAT-001 | Idempotency framework | 04 | common.idempotency: Service + Store | V2 idempotency_record | Idempotency-Key header | — | IdempotencyServiceTest (10), MigrationIT | IMPLEMENTED |
| PLAT-002 | Outbox / domain events | 02,08 | common.outbox: Service + Publisher; consumers: RealtimeEventRelay, NotificationRelay | V2 outbox_event | — | — | RealtimeFlowIT$Projection (2), NotificationFlowIT$FanOut (5) | TESTED |
| PAY-002 | Webhook idempotency | 01,04,09 | payment: PaymentWebhookService + Store, PaymentJobs.reconcileStale | V11 payment_webhook_event | POST /webhooks/razorpay | — | PaymentFlowIT$Recovery (4) | TESTED |
| PAY-003 | Partial capture, release and refund | 01,03,22 | payment: PaymentService.markForCapture/performCapture/release, RefundService | V11 payment_transaction, refund | POST /payments/{id}/refund, GET /payments/{id}/refunds | refund status pending | PaymentLifecycleTest, PaymentFlowIT$Capture (4), $Refunds (4) | TESTED |
| CRD-001 | Supplier credit | 01,03,04 | credit: CreditAgreementService, CreditPolicyService, CreditInvoiceService, CreditJobs | V12 credit_agreement, credit_request, credit_limit_history, credit_invoice, credit_payment | /credit/requests, /credit/agreements/{id}/{approve,reject,modify,accept,suspend}, /credit/invoices/{id}/payments, /outlets/{id}/credit/summary | Credit Overview/Request pending | CreditExposureTest (14), CreditFlowIT$Negotiation (7), $InvoicesAndRepayment (7) | TESTED |
| CRD-002 | Credit reservation | 01,03 | credit: CreditLedgerService, CreditLedger, CreditExposureStore, CreditFundingAdapter | V12 credit_reservation, credit_transaction | via procurement submit and supplier response | credit state in cart pending | CreditFlowIT$ReserveAndUtilize (5), $Limits (5), $Concurrency (1) | TESTED |
| DEL-001 | Delivery abstraction | 01,06 | delivery: DeliveryProvider port + 2 mocks, DeliveryService, DeliveryQuotingService, DeliverySelection | V13 delivery, delivery_provider, delivery_quote | POST /supplier-orders/{id}/delivery, GET /deliveries/{id}, /events, /cancel | tracking screen pending | DeliverySelectionTest (16), DeliveryFlowIT$Journey (3), $Choosing (5) | TESTED |
| DEL-002 | Reassignment and tracking | 01,03,06 | delivery: DeliveryBookingService, DeliveryEventService, DeliveryOrderBridge, DeliveryJobs | V13 delivery_provider_attempt, delivery_event, delivery_location | POST /deliveries/{id}/reassign, /dispatched, /delivered | live tracking pending | DeliveryFlowIT$Reassignment (2), $Tracking (4), $OwnDelivery (3), $Access (2) | TESTED |
| RCV-001 | Receiving | 01,03,04 | trust: ReceivingService, TrustDirectory | V15 receiving, receiving_item | POST /supplier-orders/{id}/receive, GET /receiving | Receiving screen pending | TrustLifecycleTest (10), TrustFlowIT$Receiving (7) | TESTED |
| DSP-001 | Disputes | 01,03,04 | trust: DisputeService, DisputeStatus, DisputeCategory | V15 dispute, dispute_item, dispute_message, dispute_evidence | POST /supplier-orders/{id}/disputes, /disputes/{id}/{response,messages,resolve,reject} | Dispute screen pending | TrustFlowIT$Disputes (7) | TESTED |
| RAT-001 | Ratings | 01,04,09 | trust: RatingService, RatingModerationStatus | V15 rating | POST /supplier-orders/{id}/rating, GET /supplier-stores/{id}/ratings, POST /internal/ratings/{id}/moderate | Rating screen pending | TrustFlowIT$Ratings (5) | TESTED |
| ORG-002 | Memberships in /auth/me | 04,05 | access: MembershipService | V1/V3/V4 | GET /auth/me | role-based routing pending | TenantIsolationIT$Memberships (5) | TESTED |
| AUTH-003 | Device registration | 04,08 | identity: DeviceService | V1 device | POST/GET /devices, DELETE /devices/{id} | push registration pending | AuthFlowIT$Devices (2) | TESTED |
| NTF-001 | Notifications | 08,04 | notification: NotificationRules, NotificationRelay, NotificationDispatcher, NotificationSender port + mocks | V16 notification, notification_delivery, notification_preference | GET /notifications, /{id}/read, /read-all, GET+PATCH /notification-preferences | Notifications screen pending | NotificationRulesTest (12), NotificationFlowIT$FanOut (5), $Preferences (3), $Delivery (5), $Inbox (4) | TESTED |
| RT-001 | Realtime updates | 05,06 | realtime: RealtimeEventRelay, RealtimeRouter, RealtimeSessionRegistry, RealtimeBroadcaster (Local/Redis) | V14 realtime_event | WS /realtime/socket, GET /realtime/events | live tracking/order screens pending | RealtimeRoutingTest (9), RealtimeFlowIT$Isolation (4), $Polling (5), $Projection (2) | TESTED |
| RT-002 | Realtime authentication | 06,09 | realtime: RealtimeTicketService, RealtimeTicketStore, RealtimeHandshakeInterceptor, RealtimeEntitlements | V14 realtime_ticket | POST /realtime/ticket | socket client pending | RealtimeFlowIT$Handshake (6) | TESTED |
| ANA-001 | Analytics | 08 | notification: AnalyticsService, AnalyticsEventStore (secret stripping, idempotent ingest) | V16 analytics_event | POST /analytics/events | client event tracking pending | NotificationFlowIT$Analytics (4) | TESTED |
| SET-001 | Commission | 01,09 | settlement: CommissionService, CommissionConfiguration (scoped, effective-dated) | V18 commission_configuration, commission_calculation | rate resolution is internal; rates visible on settlement lines | Settlements screen pending | SettlementLifecycleTest (9), SettlementFlowIT$Commission (3), $Reproducibility (2) | TESTED |
| SET-002 | Settlement and reconciliation | 01,03,09 | settlement: SettlementService, SettlementReconciliationService, SettlementJobs | V18 settlement, settlement_adjustment; V19 settlement.offsetDays | GET /supplier-stores/{id}/settlements, /settlements/{id}, /admin/settlements/{id}/{approve,processing,paid,failed,adjustments,reconcile} | Settlements screen pending | SettlementFlowIT$Settling (4), $Adjustments (3), $Reconciliation (3) | TESTED |
| SEC-003 | Rate limiting | 09 | common.ratelimit: RateLimiter port (InMemory + Redis), RateLimitPolicies, RateLimitInterceptor | — | 429 + Retry-After on OTP, auth, search, payment, webhooks, admin mutations | client backoff pending | RateLimiterTest (9), RateLimitIT (7) | TESTED |
| SEC-004 | Error contract | 04 | common.error: ErrorCode catalogue, GlobalExceptionHandler | — | every documented code and status | typed client errors pending | ErrorContractTest (5) | TESTED |
| SEC-001 | Authorization | 03,09 | access: AccessControlService, ScopeType, RoleGrantService | V1 role/permission/user_role, V5 seed | enforced on all scoped APIs | permissions in /auth/me | ScopeTypeTest (5), PermissionCatalogIT (7), TenantIsolationIT (17) | TESTED |
| SEC-002 | Audit | 03,09 | common.audit: AuditService (redacts secrets); admin: audit search | V2 audit_log | GET /admin/audit | — | AuthFlowIT, OperationsIT$Inspection.auditIsSearchable | TESTED |
| OPS-001 | Operations inspection | 09,04,08 | admin: AdminQueryService, OperationsDashboardService | V17 inspection permissions | GET /admin/{suppliers,orders,orders/{id}/timeline,orders/{id}/payment,orders/{id}/delivery,credit/exposure,disputes,audit,config,dashboard} | future operations web | OperationsIT$Boundary (3), $Inspection (5) | TESTED |
| OPS-002 | Operations moderation and config | 09 | admin: AdminModerationService, AdminConfigService | V17 role grants; app_config versioning | POST /admin/suppliers/{id}/{suspend,reactivate}, /admin/catalog/skus/{id}/disable, PATCH /admin/catalog/products/{id}, POST /admin/disputes/{id}/resolve, PATCH /admin/config | future operations web | OperationsIT$ReadWriteSeparation (2), $Moderation (3), $Configuration (4) | TESTED |

## 3. State-machine coverage

Every state machine in `03-state-machines-permissions.md` must have:

- legal transition tests
- illegal transition tests
- authorization tests
- concurrency tests where applicable
- audit verification
- event verification

## 4. API coverage

Every endpoint in `04-api-specification.md` must have:

- controller
- request DTO
- response DTO
- validation
- authorization
- service
- error mapping
- OpenAPI documentation
- integration test

## 5. Screen coverage

Every screen in `05-mobile-screens.md` must have:

- route
- component
- API integration
- loading
- empty
- error
- offline
- stale where relevant
- permissions
- analytics
- accessibility
- navigation tests

## 6. Financial invariants

Must always be tested:

- captured <= authorized
- refunded <= captured
- available credit >= 0
- accepted quantity <= requested quantity
- fulfilled quantity <= requested quantity
- supplier commission calculated from correct historical commercial value
- settlement is reproducible

## 7. Final audit checklist

Before declaring implementation complete. Checked at the end of the backend build
sequence (phases 1, 3–17); **unchecked items are genuinely outstanding**, not
oversights.

- [x] all PRD requirements mapped — every row above is TESTED
- [x] all tables migrated — V1–V19, verified from clean in `MigrationIT`
- [x] all state machines implemented — requirement, procurement, supplier order,
      payment, refund, credit agreement, credit reservation, delivery, receiving,
      dispute, settlement
- [x] all API endpoints implemented — doc 04 §1–19
- [ ] **all mobile screens implemented** — only the design system exists in
      `costonomy-mp-mobile`. Every screen in `05-mobile-screens.md` is outstanding,
      and it is the largest remaining piece of work.
- [x] all provider abstractions implemented — `OtpProvider`, `PaymentProvider`,
      `DeliveryProvider`, `NotificationSender`, plus `OrderFundingPort` and
      `RateLimiter` as internal ports
- [x] mock providers work — every port has one, and CI runs entirely on them
- [x] all critical events emitted — doc 08 §1, with `NotificationRulesTest`
      pinning the names consumers match on
- [x] audit trail complete — doc 09 §7's list, searchable via `/admin/audit`
- [x] idempotency complete — doc 04 §21's list
- [x] concurrency tests pass — doc 10 §2's mandatory set: acceptance vs timeout,
      duplicate acceptance, duplicate submission, duplicate payment, duplicate
      webhook, credit reservation race
- [x] E2E scenarios pass — including doc 10 §1's seventh, credit request through
      to reconciliation
- [x] security tests pass — tenant isolation, permission catalogue invariants,
      operations boundary, rate limiting, error contract
- [x] no mobile-only financial authority — every price, total, capture and payout
      is computed server-side
- [x] no hidden commission ranking — `BestValueScorerTest` asserts the component
      set; nothing in `discovery` depends on `settlement`
- [x] no Costonomy runtime dependency — the `costonomy-*` repos are reference only
- [x] no fake delivery tracking — positions come from a provider or are absent,
      and a stale fix is reported stale
- [x] docs synchronized with code — this file, `DECISIONS.md`, `CLAUDE.md` and
      `README.md` updated at the end of every phase

**Open items** (`docs/DECISIONS.md`): OPEN-005 — the delivery fee is never charged
to the restaurant, because it is only known after the payment is authorised.
