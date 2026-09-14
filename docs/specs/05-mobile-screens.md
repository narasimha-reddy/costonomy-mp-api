# Mobile Screen Inventory & UX Specification

## 1. Mobile architecture

One React Native app with role-aware experiences.

Primary navigation:

### Restaurant

`Home | Discover | Requirements | Orders | Account`

### Supplier

`Home | Orders | Catalog | Credit | More`

Outlet selector is globally accessible for multi-outlet restaurant users.

No Operations mobile UI.

## 2. Global UX contract

Every screen must define:

- screen ID
- purpose
- entry points
- information hierarchy
- components
- APIs
- realtime behavior
- permissions
- primary CTA
- secondary actions
- loading
- empty
- error
- offline
- stale
- retry
- analytics
- accessibility
- edge cases

Use reusable components:

- `MandiButton`
- `MandiCard`
- `MandiSearchBar`
- `MandiProductCard`
- `MandiSupplierCard`
- `MandiPrice`
- `MandiStatusChip`
- `MandiTimeline`
- `MandiSkeleton`
- `MandiEmptyState`
- `MandiErrorState`
- `MandiBottomSheet`
- `MandiQuantityStepper`
- `MandiMap`
- `MandiDeliveryHeader`

## 3. Design direction

Premium, modern, fast, image-rich and operationally clear.

Use inspiration from high-quality food-commerce apps for hierarchy, search, cards, bottom sheets, skeletons, tracking and transitions without copying proprietary layouts/assets.

Design tokens must centralize:

- typography
- spacing
- radii
- elevations
- icon sizes
- touch targets
- semantic colors
- animation durations

## 4. Restaurant screens

| ID | Screen |
|---|---|
| REST-AUTH-01 | Splash |
| REST-AUTH-02 | Login |
| REST-AUTH-03 | OTP Verification |
| REST-ONB-01 | Restaurant/Outlet Setup |
| REST-HOME-01 | Home |
| REST-SEARCH-01 | Search |
| REST-SEARCH-02 | Search Results |
| REST-PROD-01 | Product Detail |
| REST-SUP-01 | Supplier Comparison |
| REST-REQ-01 | Requirements |
| REST-REC-01 | Recommended Procurement |
| REST-CART-01 | Cart |
| REST-CHECKOUT-01 | Checkout |
| REST-APPROVAL-01 | Approval |
| REST-PAY-01 | Payment |
| REST-ORDERS-01 | Orders |
| REST-ORDER-TRACK-01 | Order/Delivery Tracking |
| REST-RECEIVE-01 | Receiving |
| REST-RATING-01 | Rating |
| REST-CREDIT-01 | Credit Overview |
| REST-CREDIT-02 | Credit Request |
| REST-DISPUTE-01 | Dispute |
| REST-NOTIF-01 | Notifications |
| REST-ACCOUNT-01 | Account |

## 5. Restaurant Home

Hierarchy:

1. outlet selector
2. product search
3. recommended procurement
4. pending approvals
5. buy again
6. open requirements
7. active orders
8. categories
9. supplier recommendations

APIs:

- current outlet
- recommendation feed
- requirements
- orders
- categories

States:

- skeleton
- partially loaded sections
- empty recommendation
- offline cached read
- retry per failed section

## 6. Search

Search must feel instant.

Behavior:

- debounce input
- recent searches
- suggestions
- product-first results
- supplier filter
- category filter
- availability filter
- sort/filter sheet

Do not show stale price as authoritative checkout price.

## 7. Product Detail

Show:

- canonical product image
- product name
- comparable supplier offers
- brand/pack
- price
- GST treatment
- availability
- ETA
- supplier trust indicators
- add quantity
- add to cart
- buy again where applicable

If an offer becomes unavailable, refresh and explain.

## 8. Supplier Comparison

Compare offers by:

- total commercial value
- unit/pack price
- availability
- ETA
- reliability
- fill rate
- quality/rating indicators
- delivery fee

Recommended offer must show why it is recommended.

Never show commission as a ranking factor.

## 9. Requirements

Show:

- open requirements
- fulfilled quantities
- remaining quantities
- sourcing status
- alternatives

Requirement detail must preserve unmet quantities after supplier rejection/timeout/partial acceptance.

## 10. Recommended Procurement

Show:

- requirement
- recommended supplier
- quantities
- unit prices
- ETA
- estimated total
- estimated savings
- alternative suppliers

Restaurant chooses whether to proceed.

## 11. Cart

Group by supplier.

Show:

- product
- pack
- quantity
- unit price
- GST
- subtotal
- delivery
- total

On resume/checkout, revalidate.

If price changed:

- highlight change
- show old/new
- require explicit confirmation

## 12. Checkout

Show:

- outlet
- supplier
- items
- totals
- delivery mode
- payment/credit method
- expected acceptance deadline

No order is considered successful until backend confirms.

## 13. Approval

If policy requires approval:

- show reason
- amount
- supplier
- approvers
- status
- approve/reject if authorized

No payment capture before approval where policy requires approval.

## 14. Payment

Show provider UI/status.

Possible states:

- preparing
- authorization pending
- success
- failed
- verification pending

Never infer final financial success only from client callback.

## 15. Orders

Tabs/filters:

- pending
- active
- completed
- cancelled

Cards show:

- supplier
- amount
- item count
- state
- ETA
- delivery status

## 16. Order/Delivery Tracking

Before assignment:

> Finding the best delivery partner…

After assignment:

- map
- restaurant and driver markers where available
- route
- ETA
- driver details
- call
- timeline
- support
- stale-location indicator

Realtime sources:

1. WebSocket
2. push notification
3. polling fallback

On reconnect/cold start, refresh authoritative state.

## 17. Receiving

For each item:

- ordered
- accepted
- received
- damaged
- missing

CTA:

`Complete Receiving`

Allow dispute creation immediately for discrepancies.

## 18. Rating

Capture:

- quality
- quantity accuracy
- packaging
- delivery
- overall

Prevent duplicate submission.

## 19. Credit Overview

Show:

- approved limit
- reserved
- utilized
- due
- overdue
- available

Show supplier-specific agreements separately.

## 20. Credit Request

Fields:

- supplier
- outlet
- requested limit
- requested payment days
- purpose

Status:

- requested
- additional information
- approved
- modified
- rejected
- active

## 21. Dispute

Step flow:

1. select category
2. select affected items
3. quantity/issue
4. evidence
5. notes
6. submit

Show order remains delivered while dispute is separately tracked.

## 22. Notifications

Types:

- supplier accepted/rejected/expired
- payment
- delivery
- credit
- dispute
- approval

Unread count must reconcile with backend.

## 23. Supplier screens

| ID | Screen |
|---|---|
| SUP-AUTH-01 | Authentication |
| SUP-ONB-01 | Supplier Registration |
| SUP-ONB-02 | Verification |
| SUP-HOME-01 | Supplier Home |
| SUP-ORD-01 | New Order |
| SUP-ORD-02 | Partial Acceptance |
| SUP-ORD-03 | Reject |
| SUP-ORD-04 | Preparing |
| SUP-ORD-05 | Ready for Pickup |
| SUP-CATALOG-01 | Catalog |
| SUP-CATALOG-02 | SKU Editor |
| SUP-CATALOG-03 | Bulk Import |
| SUP-CREDIT-01 | Credit Requests |
| SUP-CREDIT-02 | Credit Portfolio |
| SUP-SETTLE-01 | Settlements |
| SUP-PERF-01 | Performance |

## 24. Supplier Home

Show:

- new orders
- expiring acceptance deadlines
- preparing orders
- ready orders
- credit requests
- settlement summary
- performance

Urgent acceptance items must be visually prioritized.

## 25. New Order

Show:

- restaurant/outlet
- items
- quantities
- commercial total
- delivery mode
- acceptance deadline

Actions:

- accept
- partial accept
- reject

Deadline is authoritative from backend.

## 26. Partial Acceptance

Per item:

- requested quantity
- accepted quantity
- reason

Show:

- revised subtotal
- revised GST
- revised total
- unmet quantity

Confirmation is required.

## 27. Reject

Require rejection reason.

Examples:

- out of stock
- unable to deliver
- store closed
- price issue
- other

Rejecting triggers alternative sourcing for unmet requirement.

## 28. Preparing / Ready

Supplier can advance state only when authorized and valid.

Ready action creates/activates delivery flow where applicable.

## 29. Catalog

Search/filter:

- active
- out of stock
- inactive

SKU editor:

- canonical product
- SKU code
- brand
- pack
- price
- GST
- availability
- image

## 30. Bulk import

Display:

`Upload → Parse → Map → Validate → Preview → Confirm → Import → Summary`

Errors must be row-specific.

Never silently skip invalid data.

## 31. Credit Requests

Show:

- restaurant/outlet
- requested limit
- requested days
- risk/context indicators
- requested date

Actions:

- approve
- reject
- modify
- request information

## 32. Credit Portfolio

Show:

- approved
- reserved
- utilized
- due
- overdue
- available
- agreements
- ledger

Manual adjustments require explicit reason and authorization.

## 33. Settlements

Show:

- gross
- commission
- adjustments
- net
- settlement date
- status

Historical settlement must not depend on current commission configuration.

## 34. Performance

Show:

- acceptance rate
- fill rate
- on-time rate
- cancellation rate
- ratings
- order volume

## 35. Global state rules

### Loading

Use skeletons for known layouts.

### Empty

Explain why empty and give the next useful action.

### Error

Show human-readable message + retry.

### Offline

Never claim state-changing success offline.

Persist only safe drafts.

### Stale

Clearly label stale delivery/location or cached operational data.

### Permissions

Hide unavailable actions where useful, but backend remains authoritative.

### Resume/cold start

Refresh active order, payment, credit and delivery states.

## 36. Accessibility

- minimum touch target 44x44pt
- semantic labels
- readable contrast
- dynamic text support
- screen-reader-friendly status
- do not rely on color alone

## 37. Analytics

Every key CTA emits an analytics event with:

- screen
- action
- role
- outlet
- entity ID where permitted
- timestamp
- app version
- correlation/request ID where available

Do not include sensitive payment credentials or OTP values.

## 38. UI Definition of Done

Every screen must have:

- real API integration
- all states
- navigation
- permission handling
- analytics
- accessibility
- tests
- edge cases
- refresh/reconnect behavior
