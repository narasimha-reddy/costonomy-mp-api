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
| AUTH-001 | OTP login | 01,04 | Auth module | users/otp | auth endpoints | Login/OTP | auth tests | NOT_STARTED |
| AUTH-002 | JWT session | 01,04 | Auth module | refresh_token | refresh/logout | session handling | auth tests | NOT_STARTED |
| ORG-001 | Restaurant/outlet | 01,02,04 | Restaurant module | restaurant/outlet | outlet APIs | setup/outlet selector | API tests | NOT_STARTED |
| SUP-001 | Supplier lifecycle | 01,03,04 | Supplier module | supplier tables | supplier APIs | supplier onboarding | transition tests | NOT_STARTED |
| SUP-002 | GST verification | 01,09 | Verification module | verification | admin/supplier APIs | verification | verification tests | NOT_STARTED |
| CAT-001 | Canonical products | 01,02,04 | Catalog module | product tables | product APIs | product screens | catalog tests | NOT_STARTED |
| CAT-002 | Supplier SKU | 01,02,04 | Catalog module | supplier_sku | SKU APIs | catalog | catalog tests | NOT_STARTED |
| CAT-003 | Bulk import | 01,04,05 | Catalog import | import tables | import APIs | bulk import | import tests | NOT_STARTED |
| SRCH-001 | Product search | 01,07 | Search module | product/index | search APIs | Search | search tests | NOT_STARTED |
| REC-001 | Best-value recommendation | 01,07 | Recommendation | score config | recommendation API | recommendations | scoring tests | NOT_STARTED |
| REQ-001 | Requirement lifecycle | 01,03,04 | Requirement | requirement | requirement APIs | requirements | lifecycle tests | NOT_STARTED |
| PROC-001 | Procurement | 01,03,04 | Procurement | procurement | procurement APIs | cart/checkout | E2E | NOT_STARTED |
| PROC-002 | Multi-supplier split | 01,03,04 | Procurement | split/order | submit API | comparison | split tests | NOT_STARTED |
| ORD-001 | Supplier acceptance | 01,03,04 | Order module | supplier_order | accept API | supplier order | race tests | NOT_STARTED |
| ORD-002 | Partial acceptance | 01,03,04 | Order module | order items | partial API | partial screen | partial tests | NOT_STARTED |
| PAY-001 | Provider abstraction | 01,04,06 | Payment module | payment | payment APIs | payment | provider tests | NOT_STARTED |
| PAY-002 | Webhook idempotency | 01,04,09 | Payment module | webhook event | webhook | status | webhook tests | NOT_STARTED |
| PAY-003 | Partial capture | 01,03 | Payment module | payment txn | confirm | payment status | financial tests | NOT_STARTED |
| CRD-001 | Supplier credit | 01,03,04 | Credit module | credit tables | credit APIs | credit screens | credit tests | NOT_STARTED |
| CRD-002 | Credit reservation | 01,03 | Credit module | reservation/txn | internal/domain | order UI | race tests | NOT_STARTED |
| DEL-001 | Delivery abstraction | 01,06 | Delivery module | delivery | delivery APIs | tracking | provider tests | NOT_STARTED |
| DEL-002 | Reassignment | 01,03,06 | Delivery module | attempts/events | reassign API | tracking | failure E2E | NOT_STARTED |
| RCV-001 | Receiving | 01,03,04 | Receiving | receiving | receive API | receiving | receiving tests | NOT_STARTED |
| DSP-001 | Disputes | 01,03,04 | Dispute module | dispute | dispute APIs | dispute | dispute tests | NOT_STARTED |
| RAT-001 | Ratings | 01,04 | Rating module | rating | rating API | rating | rating tests | NOT_STARTED |
| NTF-001 | Notifications | 08 | Notification module | notification | notification API | notifications | notification tests | NOT_STARTED |
| ANA-001 | Analytics | 08 | Analytics module | analytics_event | event pipeline | event tracking | analytics tests | NOT_STARTED |
| SET-001 | Commission | 01,09 | Settlement module | commission | admin APIs | settlement view | financial tests | NOT_STARTED |
| SET-002 | Settlement | 01,09 | Settlement module | settlement | admin APIs | supplier settlement | settlement tests | NOT_STARTED |
| SEC-001 | Authorization | 03,09 | Security | role tables | all APIs | role UI | authz tests | NOT_STARTED |
| SEC-002 | Audit | 03,09 | Audit module | audit_log | admin audit | — | audit tests | NOT_STARTED |
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
