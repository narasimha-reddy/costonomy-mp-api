# State Machines, Permissions & Policies

## 1. General transition rule

Every state transition must:

1. load current state
2. authorize actor
3. validate transition
4. validate business conditions
5. lock/version-check aggregate
6. persist new state
7. write audit record
8. emit domain event
9. return authoritative resulting state

Illegal transitions return a typed business error.

## 2. Supplier lifecycle

`INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE`

Additional:

- `ACTIVE → OFFLINE`
- `OFFLINE → ACTIVE`
- `ACTIVE → SUSPENDED`
- `SUSPENDED → ACTIVE`
- `VERIFICATION_PENDING → SUSPENDED`

Verification failure must not silently activate supplier.

## 3. Requirement lifecycle

Recommended:

`OPEN → SOURCING → PARTIALLY_FULFILLED → FULFILLED`

Branches:

- `OPEN → CANCELLED`
- `SOURCING → CANCELLED`
- `PARTIALLY_FULFILLED → CANCELLED`
- `OPEN/SOURCING → EXPIRED` only if a configured business policy exists

A requirement item has its own fulfillment quantity and status.

Unmet quantity must remain traceable.

## 4. Procurement lifecycle

`DRAFT → VALIDATING → READY → PENDING_APPROVAL → APPROVED → SUBMITTED`

Branches:

- `DRAFT → CANCELLED`
- `READY → CANCELLED`
- `PENDING_APPROVAL → REJECTED`
- `APPROVED → CANCELLED`
- `SUBMITTED → FAILED` for operational failure before supplier order creation, with retry semantics

A validation response must identify every changed/invalid item.

## 5. Supplier order lifecycle

`DRAFT → PENDING_ACCEPTANCE → CONFIRMED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED → COMPLETED`

Branches:

- `PENDING_ACCEPTANCE → ACCEPTED/CONFIRMED`
- `PENDING_ACCEPTANCE → REJECTED`
- `PENDING_ACCEPTANCE → EXPIRED`
- `CONFIRMED/PREPARING → CANCELLED` only if cancellation policy permits
- `READY_FOR_PICKUP → OUT_FOR_DELIVERY`
- `OUT_FOR_DELIVERY → DELIVERED`
- `DELIVERED → COMPLETED`
- `CONFIRMED → PARTIALLY_ACCEPTED` when supplier changes item quantities
- partial acceptance must preserve unmet requirement quantity

Acceptance and timeout must race safely. Exactly one terminal outcome wins.

## 6. Payment lifecycle

Recommended:

`CREATED → AUTHORIZED → CAPTURE_PENDING → CAPTURED`

Branches:

- `CREATED → FAILED`
- `AUTHORIZED → RELEASED`
- `CAPTURE_PENDING → FAILED`
- `CAPTURED → PARTIALLY_REFUNDED`
- `CAPTURED → FULLY_REFUNDED`

Provider webhooks can arrive out of order. State transitions must be monotonic where possible and reconciled against provider state.

## 7. Refund lifecycle

`REQUESTED → PROCESSING → COMPLETED`

Failure:

`PROCESSING → FAILED`

Retry must not create a duplicate refund.

## 8. Credit agreement lifecycle

`REQUESTED → APPROVED → ACTIVE`

Branches:

- `REQUESTED → REJECTED`
- `APPROVED → ACTIVE`
- `ACTIVE → SUSPENDED`
- `SUSPENDED → ACTIVE`
- `ACTIVE → EXPIRED`
- `ACTIVE → CLOSED`

Supplier approval may modify terms before activation.

## 9. Credit reservation lifecycle

`REQUESTED → RESERVED → UTILIZED`

Branches:

- `REQUESTED → FAILED`
- `RESERVED → RELEASED`
- `RESERVED → EXPIRED`

Reservation must be tied to an order/procurement operation.

## 10. Delivery lifecycle

`DELIVERY_REQUESTED → QUOTE_RECEIVED → PROVIDER_SELECTED → DRIVER_ASSIGNED → DRIVER_AT_PICKUP → PICKED_UP → IN_TRANSIT → ARRIVED_AT_DESTINATION → DELIVERED`

Failure states:

- `QUOTE_FAILED`
- `PROVIDER_UNAVAILABLE`
- `DRIVER_CANCELLED`
- `PICKUP_FAILED`
- `DELIVERY_FAILED`
- `CANCELLED`

Provider reassignment must preserve a single delivery identity and append attempts/events rather than creating a misleading second delivery.

## 11. Receiving

`PENDING → RECEIVED`

A receiving record may contain discrepancies.

Receiving does not rewrite the supplier order to erase delivered quantities.

## 12. Dispute

`OPEN → UNDER_REVIEW → RESPONDED → RESOLVED`

Alternative terminal:

`OPEN/UNDER_REVIEW → REJECTED`

A dispute is separate from order status.

## 13. Settlement

`PENDING → CALCULATED → APPROVED → PROCESSING → PAID`

Failure:

`PROCESSING → FAILED`

Reconciliation must be idempotent.

## 14. Permission model

Representative permissions:

### Restaurant

- `RESTAURANT_VIEW`
- `OUTLET_VIEW`
- `OUTLET_EDIT`
- `REQUIREMENT_CREATE`
- `REQUIREMENT_EDIT`
- `PROCUREMENT_CREATE`
- `PROCUREMENT_SUBMIT`
- `PROCUREMENT_APPROVE`
- `ORDER_VIEW`
- `ORDER_CANCEL`
- `ORDER_RECEIVE`
- `DISPUTE_CREATE`
- `RATING_CREATE`
- `CREDIT_VIEW`
- `CREDIT_REQUEST`
- `PAYMENT_CREATE`

### Supplier

- `SUPPLIER_VIEW`
- `SUPPLIER_EDIT`
- `STORE_VIEW`
- `STORE_EDIT`
- `CATALOG_VIEW`
- `CATALOG_EDIT`
- `CATALOG_IMPORT`
- `ORDER_VIEW`
- `ORDER_ACCEPT`
- `ORDER_PARTIAL_ACCEPT`
- `ORDER_REJECT`
- `ORDER_PREPARE`
- `ORDER_READY`
- `CREDIT_REQUEST_VIEW`
- `CREDIT_APPROVE`
- `CREDIT_REJECT`
- `CREDIT_MODIFY`
- `SETTLEMENT_VIEW`
- `PERFORMANCE_VIEW`

### Internal

- `SUPPLIER_VERIFY`
- `SUPPLIER_SUSPEND`
- `CATALOG_MODERATE`
- `ORDER_SUPPORT`
- `DISPUTE_MODERATE`
- `DELIVERY_OPERATE`
- `PAYMENT_RECONCILE`
- `CREDIT_AUDIT`
- `SETTLEMENT_OPERATE`
- `AUDIT_VIEW`
- `CONFIG_MANAGE`

## 15. Policy examples

### Approval

Evaluate:

- outlet
- requester role
- order value
- category
- supplier
- payment method

Return:

- approval required?
- approvers
- reason
- policy version

### Supplier availability

Supplier order may be created only if:

- supplier store is ACTIVE
- SKU is ACTIVE
- SKU is available
- offer is valid
- outlet is serviceable
- payment/credit policy permits transaction

### Credit

Before reserve:

- agreement ACTIVE
- outlet covered
- current time within agreement validity
- available credit sufficient
- single-order cap not exceeded
- overdue/suspension rules permit

## 16. Actor scope

Backend must enforce tenant/resource scope. A valid permission does not permit access to an unrelated restaurant, outlet, supplier or order.

## 17. Audit

Every privileged or financially material transition must record:

- actor
- actor role
- action
- entity
- old state
- new state
- request ID
- idempotency key if applicable
- reason
- timestamp
- source/device metadata where available
