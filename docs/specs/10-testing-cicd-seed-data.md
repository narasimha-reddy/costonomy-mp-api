# Testing, CI/CD & Seed Data

## 1. Testing pyramid

### Unit

Cover:

- state transition rules
- authorization policies
- pricing
- commission
- credit calculations
- recommendation scoring
- delivery provider selection
- validation

### Integration

Cover:

- MySQL persistence
- transactions
- Flyway migrations
- idempotency
- outbox
- payment webhook handling
- provider adapters

### API

Test every endpoint for:

- happy path
- validation
- authentication
- authorization
- tenant isolation
- state conflicts
- idempotency

### Mobile

Test:

- navigation
- screen rendering
- loading
- empty
- error
- offline
- stale
- reconnect
- permission-driven UI
- API failure

### E2E

Mandatory scenarios:

1. full prepaid order
2. supplier timeout → alternatives
3. partial fulfillment → remaining requirement → alternative supplier
4. payment failure → supplier receives nothing
5. lost payment callback → recovery without duplicate
6. delivery provider/driver failure → reassignment
7. credit request → modified approval → reserve → utilize → invoice → due → payment → reconciliation
8. receiving discrepancy → dispute

## 2. Concurrency tests

Mandatory:

- acceptance vs timeout
- acceptance vs cancellation
- duplicate acceptance
- duplicate procurement submission
- duplicate payment initiation
- duplicate payment webhook
- duplicate refund
- credit reservation race
- duplicate delivery booking
- receiving race

Expected behavior:

Exactly one valid transition/financial operation succeeds.

## 3. Property/invariant tests

### Credit

`available >= 0`

`approved = reserved + utilized + available`

where applicable.

### Partial acceptance

`acceptedQuantity <= requestedQuantity`

`fulfilledQuantity <= requestedQuantity`

### Payment

`captured <= authorized`

`refunded <= captured`

### Requirement

`fulfilledQuantity <= requestedQuantity`

### Delivery

A logical delivery has one current provider/attempt but can have many historical attempts.

## 4. Mock providers

Mandatory:

### OTP

- success
- wrong OTP
- expired
- attempt limit
- resend

### Payment

- authorization success
- authorization failure
- capture success
- capture failure
- delayed webhook
- duplicate webhook
- out-of-order webhook
- refund success/failure

### Delivery

- quote success/failure
- booking success/failure
- driver assignment
- location stream
- driver cancel
- provider failure
- reassignment
- delivery success

## 5. Test data

Seed:

### Restaurants

- one single-outlet restaurant
- one multi-outlet restaurant
- users with each restaurant role

### Suppliers

- verified active supplier
- pending supplier
- offline supplier
- suspended supplier
- new supplier with no performance history
- high-performing supplier

### Catalog

- multiple brands mapping to same canonical product
- out-of-stock SKU
- inactive SKU
- different pack sizes
- different GST rates

### Orders

- pending acceptance
- accepted
- preparing
- ready
- out for delivery
- delivered
- partial
- rejected
- expired
- cancelled

### Credit

- active agreement
- low available
- reserved
- utilized
- overdue
- suspended

### Delivery

- own delivery
- Costonomy delivery
- provider assigned
- stale GPS
- reassignment scenario

### Disputes

- open
- under review
- resolved

## 6. Local development

Required local configuration:

```text
MYSQL_URL
MYSQL_USERNAME
MYSQL_PASSWORD
JWT_SECRET
MSG91_MODE=MOCK
PAYMENT_PROVIDER=MOCK
DELIVERY_PROVIDER=MOCK
```

No external provider should be mandatory for local development.

## 7. Test environment

CI must use deterministic mock providers.

Do not depend on production provider availability.

## 8. Database

Every migration must run from a clean database in CI.

Also test migration upgrade from previous schema version.

## 9. Contract tests

Backend API contracts should be generated/validated through OpenAPI.

Provider adapters should have contract tests.

## 10. Mobile CI

At minimum:

- lint
- type check
- unit/component tests
- build validation
- API mock integration tests

## 11. Backend CI

At minimum:

- formatting
- static analysis
- compile
- unit tests
- integration tests
- migration tests
- API contract tests
- security checks

## 12. Seed data safety

Seed data must be deterministic and clearly marked as non-production.

No real payment credentials, OTPs or customer PII.

## 13. Failure injection

Provide controlled test hooks for:

- payment timeout
- payment webhook loss
- supplier timeout
- delivery provider failure
- driver cancellation
- database deadlock/retry where feasible
- notification provider failure

## 14. Release readiness

A release candidate requires:

- all migrations clean
- all tests passing
- no known critical authorization defect
- no unresolved financial idempotency defect
- E2E scenarios passing
- API documentation current
- traceability current
