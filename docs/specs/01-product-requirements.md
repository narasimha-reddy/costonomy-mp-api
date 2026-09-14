# Product Requirements — Mandi by Costonomy

## 1. Vision

Mandi is a product-first, demand-led B2B restaurant procurement network.

Long-term product progression:

1. Marketplace
2. Intelligent Procurement
3. Procurement Agent

Initial launch emphasizes recommendation and orchestration while keeping the restaurant in control.

## 2. Core promise

**Costonomy knows what your restaurant needs. Mandi gets it from the best source.**

## 3. Product principles

1. Demand first.
2. Product first.
3. Best value, not cheapest.
4. Supplier owns commercial/credit risk.
5. Costonomy owns orchestration.
6. Restaurant controls decisions initially.
7. Delivery neutral/provider-agnostic.
8. No hidden commission-based ranking.
9. Every transaction creates intelligence.
10. Build for a network.
11. Progress from Search → Recommendation → Automation → Autonomy.

## 4. Actors

### Restaurant

- Owner
- Admin
- Purchase Manager
- Store Manager
- Procurement Staff
- Receiving Staff
- Finance Staff

Procurement is outlet-level initially.

### Supplier

- Supplier Owner
- Supplier Admin
- Store Manager
- Salesperson
- Operations Staff
- Finance Staff

### Internal

- Marketplace Operations
- Supplier Verification
- Customer Support
- Finance
- Moderation
- Marketplace Administration
- Delivery Operations

## 5. Authorization model

Use:

**Role → Permission → Policy → Approval**

Policies may depend on:

- order value
- supplier
- category
- outlet
- payment method
- requester role
- combinations of the above

## 6. Supplier model

A Supplier Organization is separate from a Supplier Store/Location.

Each store owns:

- catalog
- commercial SKUs
- prices
- availability
- delivery radius/policy
- credit terms
- timings
- preparation time
- operational status
- own-delivery settings
- Costonomy-delivery settings
- response SLA

Lifecycle:

`INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE`

Additional:

`SUSPENDED`, `OFFLINE`

Initial verification: GST.

Catalog import supports CSV/XLSX with explicit mapping, validation and import summary.

## 7. Product model

Mandi/Costonomy owns canonical product identity.

Supplier owns commercial SKU mapped to a canonical product.

Comparable supplier products may differ by brand while mapping to the same canonical product.

Supplier SKU contains:

- canonical mapping
- SKU code
- brand
- pack
- unit
- price
- GST
- availability
- image
- status

Canonical product images are platform-owned.

Availability initially:

- Available
- Out of Stock

No MOQ or order increments initially.

No Mandi minimum order.

A supplier may have its own minimum-order requirement for own delivery.

## 8. Procurement lifecycle

Primary:

`Requirement → Discover → Compare → Select → Cart → Checkout → Payment/Credit → Supplier Acceptance → Preparing → Ready → Delivery → Receive → Completed`

Secondary flow: manual product/supplier search.

Recommendation means **best value**, not automatically cheapest.

Ranking factors include:

- price
- availability
- ETA
- fill rate
- cancellation
- quality
- rating
- reliability
- on-time performance

## 9. Requirements

A requirement represents an unmet restaurant procurement need.

Recommended procurement can show:

- required quantity
- recommended supplier
- price
- ETA
- estimated total
- estimated savings

Architecture must support multi-supplier split.

If supplier rejects, times out, becomes unavailable or partially accepts:

- preserve unmet requirement
- present alternatives
- restaurant chooses
- do not recreate the requirement

## 10. Partial acceptance

Supplier may accept item quantities individually.

Restaurant must be able to:

1. accept revised order
2. find another supplier for remaining quantity
3. source all remaining quantity from another supplier

Never force partial acceptance.

No substitution initially.

## 11. Cart and pricing

At checkout, backend validates:

- SKU availability
- price
- GST
- delivery applicability
- supplier status
- quantity
- outlet
- payment/credit eligibility

If price changed:

- refresh
- show new price
- require explicit restaurant confirmation

Never silently change a confirmed commercial value.

Buy Again is supported.

Recommended Procurement is supported.

## 12. Order lifecycle

`DRAFT → PENDING_ACCEPTANCE → CONFIRMED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED → COMPLETED`

Branches:

- REJECTED
- EXPIRED
- PARTIALLY_ACCEPTED
- CANCELLED
- delivery failures

Default supplier acceptance SLA: 60 seconds, configurable.

No response = EXPIRED.

Supplier cannot accept an expired order.

Acceptance vs timeout must be concurrency-safe.

## 13. Cancellation

- before supplier acceptance: generally free
- after acceptance/preparation: conditional
- after pickup: generally no cancellation; use return/dispute path

Exact fee/refund behavior must be represented by backend policy, not mobile assumptions.

## 14. Payments

Provider abstraction; Razorpay initially.

Flow:

`Order placed → Payment authorization → Supplier accepts → Capture accepted amount → Fulfillment → Delivery → Settlement`

Payment failure means the order is not sent to supplier.

For partial acceptance:

- capture only accepted amount
- release remainder

Webhooks:

- signature verified
- persisted
- idempotent
- out-of-order safe
- reconciled

Financial truth is backend/provider reconciliation.

## 15. Refunds

Refund states:

`REQUESTED → PROCESSING → COMPLETED / FAILED`

Full and partial refunds are supported.

Refund requests must be idempotent and auditable.

## 16. Commission

Default commission: 1%.

Fee base:

`Item value + applicable GST`

Supplier pays commission.

Example:

₹100,000 commercial value → ₹1,000 commission → ₹99,000 supplier proceeds.

Delivery charges are excluded.

Partial fulfillment commission uses actual accepted/fulfilled commercial value.

Commission must never influence organic ranking.

Sponsored placement, if introduced later, must be clearly labeled.

## 17. Settlement

Default:

`T+2`

Settlement may be configurable.

Net settlement:

`Gross - Commission ± Adjustments = Net`

All adjustments must be auditable.

## 18. Supplier credit

Credit is supplier-funded and supplier-controlled.

Restaurant can request credit from any supplier.

Supplier may:

- approve
- reject
- modify
- request additional information

Agreements are supplier-specific and may be store/outlet-specific.

Mandi handles:

- request workflow
- profile
- agreement
- ledger
- utilization
- invoice/dues view
- payment reconciliation
- notifications
- audit
- credit intelligence

Mandi does not:

- fund credit
- guarantee receivables
- own receivables
- bear credit losses
- perform debt recovery

Supplier may manually adjust exposure/limit with audit.

Rules may include:

- grace period
- maximum overdue
- auto suspension
- limit reduction
- maximum single-order credit
- effective/review dates

Exposure concepts must remain distinct:

- approved limit
- reserved
- utilized
- due
- overdue
- available

Formula:

`Available = Approved Limit - Reserved - Utilized`

Available cannot become negative.

## 19. Credit reservation

- order placed → reserve
- supplier accepts → utilize
- supplier rejects/times out → release
- partial acceptance → only accepted value utilized; remainder released

This must be transactional and concurrency-safe.

## 20. Delivery

Restaurant pays delivery by default.

If supplier offers free delivery, restaurant pays ₹0 and supplier absorbs/handles it.

Two delivery modes:

### Own Delivery

- supplier handles delivery
- no live tracking initially
- Costonomy is not responsible
- no delivery performance measurement initially

### Costonomy Delivery

- normal Mandi delivery flow
- restaurant pays platform delivery fee
- third-party provider bidding is internal
- future Costonomy fleet can use same abstraction

Delivery starts after Ready for Pickup.

Initial delivery mode is ASAP.

Provider failure should retry/select an alternative provider where possible.

## 21. Delivery evidence

Where provider supports it:

- pickup OTP
- delivery OTP
- timestamp
- GPS
- recipient
- photo
- signature

## 22. Receiving

Restaurant records:

- received quantity
- missing quantity
- damaged quantity
- other discrepancy

Order remains Delivered even when a dispute is raised.

## 23. Disputes

Restaurant-to-supplier dispute categories:

- wrong product
- short quantity
- damaged
- expired
- quality
- incorrect invoice
- other

Mandi records disputes for intelligence/audit.

Delivery disputes depend on delivery mode.

## 24. Ratings

Restaurant may rate:

- product quality
- quantity accuracy
- packaging
- delivery
- overall

Ratings are public to the marketplace subject to moderation.

Supplier rating of restaurant is not required initially.

## 25. Search and discovery

Search is product-first.

Restaurant can discover:

- products
- suppliers
- offers
- categories
- recommended procurement

Supplier discovery is secondary to product discovery.

## 26. Marketplace leakage

Leakage is a strategic problem, not a fully solved MVP requirement.

Potential future retention mechanisms:

- Mandi-only supplier credit workflow
- convenient payment
- delivery
- dispute history
- pricing intelligence
- procurement analytics
- loyalty/incentives
- automated procurement

Do not implement an artificial lock-in mechanism that creates user-hostile behavior.

## 27. Metrics

North star:

**Successfully fulfilled GMV through Mandi**

Supporting metrics:

- orders/day
- GMV/day
- active restaurants
- active suppliers
- fulfillment rate
- supplier acceptance rate
- fill rate
- on-time rate
- cancellation rate
- repeat rate
- AOV
- procurement frequency
- savings vs restaurant history
- credit utilization
- repayment
- delivery success

Future:

`% of Costonomy-detected requirements fulfilled through Mandi`

## 28. Future integration boundaries

Do not create an initial runtime dependency on Costonomy APIs.

Future adapters:

- Costonomy Inventory → Mandi Requirements
- Costonomy Consumption Intelligence → Mandi Recommendations
- Costonomy Restaurant Identity → Mandi Identity Mapping
- Costonomy Supplier Network → Mandi Supplier Mapping

Use adapters/mappers, not shared external DTO coupling.
