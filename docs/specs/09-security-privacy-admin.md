# Security, Privacy, Audit & Administration

## 1. Authentication

OTP authentication through MSG91 in production.

Local/test mock provider.

Controls:

- OTP expiration
- maximum verification attempts
- resend cooldown
- IP/device/user throttling where supported
- brute-force protection
- session expiry
- refresh token rotation/revocation

## 2. Authorization

Backend authorization is mandatory.

Use:

`Role → Permission → Policy → Approval`

Never rely on mobile route visibility for security.

## 3. Tenant/resource isolation

Every query must enforce actor scope.

Examples:

- restaurant users can access only permitted restaurants/outlets
- supplier users only permitted supplier stores
- internal users require explicit operations permission
- users cannot alter unrelated order/payment/credit records by changing IDs

## 4. Secrets

Never store provider secrets in source code.

Use environment/secret management.

Never return:

- JWT signing secrets
- payment keys
- provider credentials
- webhook signing secrets

## 5. Payment security

- verify provider signatures
- persist provider event IDs
- idempotent processing
- never store raw card credentials
- never trust client payment status
- reconcile provider state

## 6. PII

Minimize stored personal information.

Access to sensitive data must be permission-controlled.

Logs must redact sensitive fields.

## 7. Audit

Audit all:

- authentication security events
- role/permission changes
- supplier verification
- supplier suspension
- catalog moderation
- price changes where operationally significant
- order state changes
- payment/refund actions
- credit approval/limit changes
- delivery reassignment
- dispute resolution
- settlement changes
- configuration changes

Audit record:

```json
{
  "actorId": 1,
  "action": "SUPPLIER_SUSPENDED",
  "entityType": "SUPPLIER_STORE",
  "entityId": 22,
  "oldState": "ACTIVE",
  "newState": "SUSPENDED",
  "reason": "QUALITY_REVIEW",
  "requestId": "..."
}
```

## 8. Supplier verification

Initial GST verification.

Verification result must be stored with:

- verification provider/source
- requested data
- normalized result
- status
- verified timestamp
- reviewer if manual
- evidence/reference

## 9. Moderation

Operations APIs must support:

- supplier suspension
- supplier reactivation
- SKU disable
- canonical product edit
- dispute moderation
- rating moderation

All moderation is auditable.

## 10. Configuration

Configurable operational values include:

- supplier acceptance SLA
- commission
- settlement timing
- delivery policies
- credit rules
- ranking weights
- notification policies
- throttling
- feature flags

Configuration changes must be:

- versioned
- audited
- effective-dated where financially relevant

## 11. Financial controls

Commission configuration must be snapshotted into each financial calculation.

Settlement must be reproducible.

Refunds must be linked to original payment transactions.

Credit ledger must be append-only; corrections use adjustment transactions.

## 12. Admin APIs

Operations endpoints must support:

- search suppliers
- verify/suspend suppliers
- search orders
- inspect order timeline
- inspect payment/reconciliation state
- inspect delivery attempts
- inspect credit exposure
- manage disputes
- manage catalog
- inspect settlements
- view audit
- manage config

## 13. Support access

Support users may inspect records without receiving unrestricted mutation rights.

Separate read and write permissions.

Sensitive financial mutations require finance-specific permissions.

## 14. Rate limiting

Apply endpoint-specific limits to:

- OTP request
- OTP verification
- login
- search
- payment initiation
- webhook endpoints
- admin mutations

## 15. Request correlation

Every request should have a request ID.

Propagate correlation ID into:

- logs
- audit
- domain events
- provider calls
- API response metadata

## 16. Logging

Structured logs.

Never log:

- OTP
- authorization headers
- card credentials
- webhook secrets
- full sensitive PII

Log:

- request ID
- actor ID where safe
- endpoint
- status
- duration
- error code
- aggregate ID
- provider operation ID where safe

## 17. Admin separation

Operations backend is not a mobile feature.

Design APIs so a future Operations web app can consume them without changing domain rules.
