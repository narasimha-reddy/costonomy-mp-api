# Costonomy Marketplace (Mandi) — Claude Code Implementation Specification

## 1. Purpose

This specification is the engineering contract for building the Costonomy Marketplace mobile application and backend end-to-end.

Current user-facing product name: **Mandi**.  
Repository names must remain:

- Backend: `costonomy-mp-api`
- Mobile: `costonomy-mp-mobile`

Technical identifiers should remain Costonomy MP-oriented so the product can be renamed later without structural migration.

## 2. System

| Layer | Technology / Decision |
|---|---|
| Mobile | React Native |
| Backend | Spring Boot |
| Database | MySQL 8, database `costonomy_mp` |
| Java package | `com.costonomy.mp` |
| Architecture | Modular monolith |
| Initial clients | Restaurant + Supplier mobile app |
| Operations UI | No mobile UI; backend APIs only |
| Payments | Provider abstraction; Razorpay initially |
| Delivery | Provider abstraction; mock provider mandatory |
| Maps | Google Maps |
| Auth | OTP + JWT/session pattern |
| OTP | MSG91 + mock provider |
| Search | Abstraction; do not make external search infrastructure authoritative |
| Realtime | WebSocket + push + polling fallback |

## 3. Product principle

Mandi is a demand-led restaurant procurement network:

> Costonomy knows what your restaurant needs. Mandi gets it from the best source.

Initial product level is recommendation/orchestration. The restaurant remains in control of supplier selection and purchasing decisions.

## 4. Required implementation behavior

Claude Code must:

1. Inspect the existing repository conventions before adding code.
2. Preserve the technical conventions of the existing Costonomy ecosystem where applicable.
3. Implement database migrations before dependent code.
4. Implement backend domain rules as authoritative.
5. Treat mobile state as a projection of backend state.
6. Make financial and state-changing operations idempotent.
7. Implement failure paths, not only happy paths.
8. Provide mock providers for OTP, payment and delivery.
9. Add tests for state transitions and concurrency-sensitive operations.
10. Maintain traceability against `IMPLEMENTATION_TRACEABILITY.md`.

## 5. Scope

### In scope

- authentication
- restaurant/outlet management
- supplier organization/store onboarding
- GST verification
- canonical product catalog
- supplier SKU/catalog
- search and discovery
- requirements
- recommended procurement
- procurement/cart/checkout
- approval workflow
- supplier order acceptance/rejection/partial acceptance
- payment authorization/capture/refund
- supplier-funded credit
- delivery orchestration
- receiving
- disputes
- ratings
- notifications
- audit
- settlement/commission
- backend operations/admin APIs
- analytics
- realtime order/delivery updates

### Out of scope initially

- Costonomy API runtime dependency
- Mandi website
- Operations mobile app
- proprietary delivery fleet
- proprietary wallet
- supplier-owned delivery live tracking
- product substitution
- MOQ/order increments
- restaurant rating by suppliers
- automated autonomous procurement without restaurant controls

## 6. Non-negotiable guardrails

Never:

- trust mobile payment success as financial truth
- duplicate financial transactions
- accept an expired supplier order
- silently reprice a cart/order
- silently drop unmet requirement quantities
- force partial acceptance
- expose internal delivery bidding/provider quotes to restaurants
- use supplier commission as an organic ranking factor
- create a runtime dependency on Costonomy APIs
- make provider integrations authoritative over backend state
- fake driver movement
- claim delivery completion without provider evidence
- create infinite loading states
- skip failure-path tests

## 7. Documentation set

- `00-README.md` — implementation entry point
- `01-product-requirements.md` — complete product requirements
- `02-domain-model-database.md` — domain model + MySQL DDL
- `03-state-machines-permissions.md` — states, transitions, policies and authorization
- `04-api-specification.md` — exact REST contracts
- `05-mobile-screens.md` — complete mobile screen inventory and UX implementation contract
- `06-delivery.md` — delivery provider abstraction and orchestration
- `07-search-recommendations.md` — discovery, matching and recommendation rules
- `08-notifications-analytics.md` — notifications, events, analytics
- `09-security-privacy-admin.md` — security, audit, moderation and operations APIs
- `10-testing-cicd-seed-data.md` — testing, local development, CI/CD and deterministic seed data
- `IMPLEMENTATION_TRACEABILITY.md` — requirement-to-code/test/document mapping

## 8. Build sequence

1. workspace discovery
2. backend foundation
3. mobile foundation
4. auth
5. organization + authorization
6. supplier onboarding
7. catalog
8. search/discovery
9. recommendations
10. requirements/procurement
11. cart/checkout
12. approvals
13. supplier acceptance
14. payments
15. credit
16. delivery
17. realtime
18. receiving/disputes/ratings
19. notifications
20. operations APIs
21. hardening
22. audit/reconciliation

After every stage: compile, test, fix, document, update traceability.

## 9. Definition of Done

A feature is complete only when it has:

- migration
- domain model
- service/business rules
- REST API
- authorization
- mobile UI where applicable
- loading/empty/error/offline/stale states
- analytics
- audit
- idempotency where required
- concurrency handling where required
- provider abstraction where required
- mock provider
- unit tests
- integration tests
- E2E coverage
- edge-case coverage
- documentation
- traceability entry
