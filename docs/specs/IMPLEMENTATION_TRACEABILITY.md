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
| REC-001 | Best-value recommendation | 01,07 | discovery: BestValueScorer, RecommendationService, SupplierPerformanceProvider | V8 ranking weights in app_config | GET /products/{id}/recommendations | Recommended Procurement pending | BestValueScorerTest (20), RecommendationIT$Filtering (7), $Honesty (3) | TESTED |
| REC-002 | Serviceability | 07,41 | discovery: Serviceability, RecommendationService | V4 supplier_delivery_policy | applied in recommendations | — | ServiceabilityTest (7), RecommendationIT$Filtering | TESTED |
| REQ-001 | Requirement lifecycle | 01,03,04 | procurement: RequirementService, RequirementStatus | V9 requirement, requirement_item | /outlets/{id}/requirements, /requirements/{id} | Requirements screen pending | LifecycleTest, ProcurementIT$Requirements (3) | TESTED |
| PROC-001 | Procurement, cart and checkout | 01,03,04 | procurement: ProcurementService, Pricing | V9 procurement, procurement_item | /cart, /procurements/{id}/validate | Cart/Checkout pending | PricingTest (7), ProcurementIT$Cart (4), $PriceChanges (5) | TESTED |
| PROC-002 | Multi-supplier split | 01,03,04 | procurement: ProcurementSubmitter | V10 supplier_order, supplier_order_item | POST /procurements/{id}/submit | Orders screen pending | ProcurementIT$Submission (6) | TESTED |
| PROC-003 | Approval policy | 01,03,28 | procurement: ApprovalPolicyEvaluator | V3 procurement_policy | /procurements/{id}/approve, /reject | Approval screen pending | ProcurementIT$Approval (6) | TESTED |
| ORD-001 | Supplier acceptance | 01,03,04 | Order module | supplier_order | accept API | supplier order | race tests | NOT_STARTED |
| ORD-002 | Partial acceptance | 01,03,04 | Order module | order items | partial API | partial screen | partial tests | NOT_STARTED |
| PAY-001 | Provider abstraction | 01,04,06 | Payment module | payment | payment APIs | payment | provider tests | NOT_STARTED |
| PLAT-001 | Idempotency framework | 04 | common.idempotency: Service + Store | V2 idempotency_record | Idempotency-Key header | — | IdempotencyServiceTest (10), MigrationIT | IMPLEMENTED |
| PLAT-002 | Outbox / domain events | 02,08 | common.outbox: Service + Publisher | V2 outbox_event | — | — | pending consumers | IMPLEMENTED |
| PAY-002 | Webhook idempotency | 01,04,09 | Payment module | webhook event | webhook | status | webhook tests | NOT_STARTED |
| PAY-003 | Partial capture | 01,03 | Payment module | payment txn | confirm | payment status | financial tests | NOT_STARTED |
| CRD-001 | Supplier credit | 01,03,04 | Credit module | credit tables | credit APIs | credit screens | credit tests | NOT_STARTED |
| CRD-002 | Credit reservation | 01,03 | Credit module | reservation/txn | internal/domain | order UI | race tests | NOT_STARTED |
| DEL-001 | Delivery abstraction | 01,06 | Delivery module | delivery | delivery APIs | tracking | provider tests | NOT_STARTED |
| DEL-002 | Reassignment | 01,03,06 | Delivery module | attempts/events | reassign API | tracking | failure E2E | NOT_STARTED |
| RCV-001 | Receiving | 01,03,04 | Receiving | receiving | receive API | receiving | receiving tests | NOT_STARTED |
| DSP-001 | Disputes | 01,03,04 | Dispute module | dispute | dispute APIs | dispute | dispute tests | NOT_STARTED |
| RAT-001 | Ratings | 01,04 | Rating module | rating | rating API | rating | rating tests | NOT_STARTED |
| ORG-002 | Memberships in /auth/me | 04,05 | access: MembershipService | V1/V3/V4 | GET /auth/me | role-based routing pending | TenantIsolationIT$Memberships (5) | TESTED |
| AUTH-003 | Device registration | 04,08 | identity: DeviceService | V1 device | POST/GET /devices, DELETE /devices/{id} | push registration pending | AuthFlowIT$Devices (2) | TESTED |
| NTF-001 | Notifications | 08 | Notification module | notification | notification API | notifications | notification tests | NOT_STARTED |
| ANA-001 | Analytics | 08 | Analytics module | analytics_event | event pipeline | event tracking | analytics tests | NOT_STARTED |
| SET-001 | Commission | 01,09 | Settlement module | commission | admin APIs | settlement view | financial tests | NOT_STARTED |
| SET-002 | Settlement | 01,09 | Settlement module | settlement | admin APIs | supplier settlement | settlement tests | NOT_STARTED |
| SEC-001 | Authorization | 03,09 | access: AccessControlService, ScopeType, RoleGrantService | V1 role/permission/user_role, V5 seed | enforced on all scoped APIs | permissions in /auth/me | ScopeTypeTest (5), PermissionCatalogIT (7), TenantIsolationIT (17) | TESTED |
| SEC-002 | Audit | 03,09 | common.audit: AuditService (redacts secrets) | V2 audit_log | admin audit pending | — | covered via AuthFlowIT | IN_PROGRESS |
| OPS-001 | Operations APIs | 09 | Admin module | all | admin APIs | future web | API tests | NOT_STARTED |

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

Before declaring implementation complete:

- [ ] all PRD requirements mapped
- [ ] all tables migrated
- [ ] all state machines implemented
- [ ] all API endpoints implemented
- [ ] all mobile screens implemented
- [ ] all provider abstractions implemented
- [ ] mock providers work
- [ ] all critical events emitted
- [ ] audit trail complete
- [ ] idempotency complete
- [ ] concurrency tests pass
- [ ] E2E scenarios pass
- [ ] security tests pass
- [ ] no mobile-only financial authority
- [ ] no hidden commission ranking
- [ ] no Costonomy runtime dependency
- [ ] no fake delivery tracking
- [ ] docs synchronized with code
