# MANDI BY COSTONOMY
## Claude Code Implementation PRD v2.1
**Repository naming update:** Mandi is the current app/product working name, but the product name is not yet finalized.

### 1. Naming and Repository Convention

| Item | Current value | Rule |
|---|---|---|
| App / product name | **Mandi** | Use “Mandi” in the mobile UI, app-facing copy, screen titles, notification text, and current product documentation. |
| Backend repository | **costonomy-mp-api** | This is the canonical repository name for the Mandi backend. |
| Mobile repository | **costonomy-mp-mobile** | This is the canonical repository name for the Mandi React Native mobile app. |
| Backend module/project identity | Mandi / Costonomy MP | Keep business/domain naming flexible so the eventual product name can change without a repository rename. |
| Mobile package | `com.costonomy.mp` | Keep unchanged for now. |
| Database | `costonomy_mp` | Keep unchanged. |
| API base/domain naming | Costonomy MP conventions | Follow existing `costonomy-api` conventions; do not introduce a Mandi-specific infrastructure convention solely because the working app name is Mandi. |

**Important:** “Mandi” is a working product/app name only. Do **not** treat it as a permanent legal, repository, package, namespace, database, or infrastructure identity.

### 2. Naming Principles for Claude Code

Claude Code MUST:
1. Create/use the repositories **`costonomy-mp-api`** and **`costonomy-mp-mobile`**.
2. Use **Mandi** as the current user-facing app name.
3. Keep `com.costonomy.mp` as the current mobile package/application identifier.
4. Keep `costonomy_mp` as the database name.
5. Avoid hard-coding “mandi” into technical identifiers where a stable Costonomy MP identifier is more appropriate.
6. Avoid creating package names, artifact names, Docker image names, environment variable prefixes, infrastructure resource names, or API namespaces that would make a future product rename unnecessarily difficult.
7. Follow existing `costonomy-api` and `costonomy-mobile` conventions where technically relevant, while using the new repository names above.
8. Treat a future product-name change as a **branding/configuration migration**, not as a repository migration.
9. Keep business-domain terminology sufficiently isolated that changing the display name does not require changing core persistence identifiers.
10. Do not rename the repositories to `mandi-api` or `mandi-mobile` unless explicitly instructed later.

### 3. Product Vision

Mandi is Costonomy’s product-first, demand-led restaurant procurement marketplace/network.

Long-term progression:
- Level 1: Marketplace — restaurant searches, compares, buys.
- Level 2: Intelligent Procurement — Mandi recommends what/when/from whom.
- Level 3: Procurement Agent — autonomous procurement within restaurant policies.

Initial release is **Recommendation only**. The restaurant remains in control.

### 4. Positioning

> “Mandi is Costonomy’s product-first restaurant procurement network that intelligently connects every restaurant requirement to the best available supplier, payment/credit option and delivery option.”

Long-term promise:

> “Costonomy knows what your restaurant needs. Mandi gets it from the best source.”

### 5. Architecture

- Separate Mandi mobile app, mobile-only initially.
- React Native repository: **`costonomy-mp-mobile`**.
- Spring Boot REST API repository: **`costonomy-mp-api`**.
- MySQL database: `costonomy_mp`.
- Mobile package: `com.costonomy.mp`.
- One mobile app with role-based views:
  - Restaurant View
  - Supplier View
- No initial Costonomy API runtime dependency.
- No Mandi website initially.
- No Operations mobile UI.
- Operations capabilities exposed through backend APIs for a future Operations website.
- Prefer a modular monolith.
- Match existing `costonomy-api` engineering conventions rather than inventing a new stack.

### 6. Branding vs Technical Identity

The implementation must separate:
- **display name:** Mandi
- **stable technical identity:** Costonomy MP
- **repository identity:** `costonomy-mp-api`, `costonomy-mp-mobile`
- **mobile package:** `com.costonomy.mp`
- **database:** `costonomy_mp`

User-facing strings may say “Mandi.” Internal technical names should preferentially use Costonomy MP conventions where practical.

For example:
- Good: `costonomy-mp-api`
- Good: `costonomy-mp-mobile`
- Good: `com.costonomy.mp`
- Good: `costonomy_mp`
- Acceptable UI: “Mandi”
- Avoid: `mandi-api` as repository name
- Avoid: `mandi-mobile` as repository name
- Avoid: making `mandi` a mandatory database/schema namespace solely because it is the current brand.

### 7. Authentication

Use the Jobs app mobile authentication pattern:
- OTP provider: MSG91.
- JWT/session.
- Secure mobile storage.
- OTP expiry, attempt limits, resend cooldown and throttling.
- Mock OTP provider for local development.
- Backend authorization is authoritative.

### 8. Users, Organizations and Permissions

Restaurant roles:
Owner, Admin, Purchase Manager, Store Manager, Procurement Staff, Receiving Staff, Finance Staff.

Supplier roles:
Supplier Owner, Supplier Admin, Store Manager, Salesperson, Operations Staff, Finance Staff.

Internal roles:
Marketplace Operations, Supplier Verification, Customer Support, Finance, Moderation, Marketplace Administration, Delivery Operations.

Authorization model:
**Role → Permission → Policy → Approval**

Approval policies can depend on:
- order value
- supplier
- category
- outlet
- payment method
- requester role
- combinations

Restaurant procurement is outlet-level initially.

### 9. Supplier and Catalog Model

Supplier organization is separate from supplier store/location.

Supplier lifecycle:
`INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE`
plus `SUSPENDED`, `OFFLINE`.

Initial verification: GST.

Mandi owns canonical product identity. Supplier owns commercial SKU identity.

Canonical product → supplier SKU → supplier offer.

Initial availability:
- Available
- Out of Stock

No MOQ/order increments initially. No Mandi-imposed minimum order.

Price changes between cart and checkout require refresh and explicit confirmation.

No substitution initially.

### 10. Procurement

Primary journey:
**Requirement → Discover → Compare → Select → Cart → Checkout → Payment/Credit → Supplier Acceptance → Preparing → Ready → Delivery → Receive → Completed**

Recommendations use Best Value / Recommended ranking based on:
- price
- availability
- ETA
- fill rate
- cancellation
- quality
- rating
- reliability/on-time performance

The architecture supports splitting a procurement request across multiple supplier orders.

If a supplier rejects, times out, becomes unavailable or partially fulfils:
- preserve unmet requirement
- present alternatives
- restaurant chooses alternative
- do not force the restaurant to recreate the requirement

### 11. Supplier Acceptance and Order State

Top-level:
`DRAFT → PENDING_ACCEPTANCE → CONFIRMED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED → COMPLETED`

Branches include:
- REJECTED
- EXPIRED
- PARTIALLY_ACCEPTED
- CANCELLED
- delivery failure states

Supplier response SLA:
**60 seconds default**, configurable.

No response = expires.

Acceptance vs timeout must be concurrency-safe.

### 12. Payments

Provider abstraction with Razorpay initially.

Flow:
**order placed → payment authorization → supplier accepts → capture accepted amount → fulfillment → delivery → settlement**

Payment failures prevent the order from reaching the supplier.

Partial acceptance means only the accepted commercial amount is captured/paid.

Webhook handling:
- signature verification
- persistence of provider references
- idempotency
- out-of-order event handling
- reconciliation

No proprietary Costonomy wallet.

### 13. Commission and Settlement

Default transaction fee: **1%**.

Fee base:
**item + GST**

Supplier pays the fee.

Partial fulfillment applies commission only to actual accepted/fulfilled commercial value.

Delivery charges are excluded.

Settlement default: **T+2**, configurable.

Organic ranking must never be influenced by commission.

### 14. Supplier Credit

Credit is first-class and supplier-funded.

Credit agreements are:
- supplier-store specific
- restaurant-outlet specific

Supplier can approve, reject or modify:
- credit limit
- credit period
- terms

Credit formula:

`Available = Approved Limit - Reserved - Utilized`

Reservation lifecycle:
- order placed → reserved
- supplier accepts → utilized
- reject/timeout → release
- partial acceptance → accepted value only

Supplier owns credit risk. Costonomy does not fund, guarantee, own receivables or bear credit losses.

### 15. Delivery

Restaurant pays delivery by default.

Two choices when both are available:
1. Own Delivery — supplier's delivery; no live tracking initially; Costonomy not responsible.
2. Costonomy Delivery — restaurant pays Costonomy delivery fee.

Costonomy Delivery can bid to third-party providers such as Porter, Rapido, Shadowfax and future providers.

Delivery abstraction treats third-party providers and a future Costonomy fleet as interchangeable.

Delivery starts after supplier marks Ready for Pickup.

Realtime:
**WebSocket + Push + Polling fallback**

Google Maps Platform for maps, geocoding, places and routes.

Mock delivery provider is mandatory for:
- quote
- driver assignment
- GPS movement
- delay
- cancellation
- provider failure
- reassignment
- delivery completion

Never fake driver movement in production.

### 16. Marketplace Trust and Ranking

Default ranking: Best Value / Recommended.

Commission has zero effect on organic ranking.

Sponsored placements may exist later and must be clearly labeled.

Supplier rating dimensions:
- product quality
- quantity accuracy
- packaging
- delivery
- overall

High supplier cancellation may make a supplier offline.

### 17. Disputes

Product disputes are primarily restaurant ↔ supplier.

Categories:
- wrong product
- short quantity
- damaged
- expired
- quality
- incorrect invoice
- other

Mandi records disputes for intelligence/audit.

Order remains Delivered even when disputed.

### 18. Core Database Entities

Core entities include:
Restaurant, Outlet, User, Role, Permission, UserRole, ProcurementPolicy, SupplierOrganization, SupplierStore, SupplierVerification, ProductCategory, Brand, CanonicalProduct, CanonicalProductAlias, SupplierSku, SupplierOffer, Requirement, RequirementItem, ProcurementRequest, ProcurementItem, SupplierOrder, SupplierOrderItem, Fulfillment, Delivery, DeliveryProvider, DeliveryQuote, DeliveryEvent, Payment, PaymentTransaction, Refund, CreditAgreement, CreditReservation, CreditTransaction, Invoice, Settlement, CommissionConfiguration, Rating, Dispute, Device, Notification, AuditLog, IdempotencyRecord.

### 19. API

Use established Costonomy API conventions and OpenAPI.

Representative routes:
- `/api/v1/auth/*`
- `/api/v1/restaurants/*`
- `/api/v1/outlets/*`
- `/api/v1/suppliers/*`
- `/api/v1/supplier-stores/*`
- `/api/v1/products/*`
- `/api/v1/search/*`
- `/api/v1/requirements/*`
- `/api/v1/procurements/*`
- `/api/v1/supplier-orders/*`
- `/api/v1/deliveries/*`
- `/api/v1/credit/*`
- `/api/v1/payments/*`
- `/api/v1/webhooks/razorpay`
- `/api/v1/notifications/*`

Exact DTOs and endpoint conventions should match `costonomy-api` patterns.

### 20. Idempotency and Concurrency

Use `Idempotency-Key`.

Mandatory for:
- procurement creation/submission
- supplier acceptance/rejection/partial acceptance
- payment creation/confirmation
- refunds
- credit reservation/utilization
- delivery booking/reassignment
- receiving
- dispute creation

Mandatory race tests:
- acceptance vs timeout
- acceptance vs cancellation
- duplicate acceptance
- duplicate procurement submission
- duplicate payment
- duplicate webhook
- credit reservation race
- delivery booking race
- receiving race

### 21. Mobile Navigation

Restaurant:
`Home | Discover | Requirements | Orders | Account`

Supplier:
`Home | Orders | Catalog | Credit | More`

One app, role determines experience.

### 22. Screen Implementation Contract

Every screen must specify:
- Screen ID
- purpose
- entry points
- navigation
- information hierarchy
- components
- API dependencies
- realtime dependencies
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

#
## 23A. Mobile UI/UX Implementation Specification

This section is **implementation-grade**. Claude Code must not infer major product UI behavior when the requirement is specified here. The visual direction should be premium, modern and operationally clear, inspired by the information hierarchy and interaction quality of leading consumer commerce apps, without copying proprietary layouts, assets or branding.

### 23A.1 UI Architecture Principles

1. Product discovery is the primary restaurant experience.
2. Search must be prominent and fast.
3. Product cards must make price, pack size, availability and supplier value easy to compare.
4. Recommendations must explain why an option is recommended.
5. Procurement progress must always be visually clear.
6. Supplier response countdown is a first-class interaction.
7. Money, approval and credit states must never be visually ambiguous.
8. Delivery tracking must show authoritative state and concrete ETA.
9. Every network-dependent screen must have loading, empty, error, retry and stale states.
10. Destructive actions require confirmation.
11. Important actions must remain usable with one hand on a typical phone.
12. UI must never imply success until the backend confirms it.

### 23A.2 Design Tokens

Create a centralized theme rather than hard-coding values in screens.

Required token groups:
- colors: background, surface, elevated surface, primary, secondary, text, muted text, border, success, warning, error, info
- typography: display, title, section title, body, body emphasis, caption, price, numeric emphasis
- spacing: 4/8-point based spacing scale
- radii: small, medium, large, pill
- elevation: card, modal, floating action
- icon sizes
- control heights
- screen horizontal padding
- animation durations/easing
- minimum touch target

Use the existing Costonomy mobile design conventions where available. Do not create an unrelated visual language.

### 23A.3 Global Components

Implement reusable components before duplicating UI across screens.

Required components:
- `MandiAppHeader`
- `MandiOutletSelector`
- `MandiSearchBar`
- `MandiButton`
- `MandiIconButton`
- `MandiCard`
- `MandiProductCard`
- `MandiSupplierCard`
- `MandiOfferRow`
- `MandiPrice`
- `MandiQuantityStepper`
- `MandiStatusChip`
- `MandiBadge`
- `MandiSectionHeader`
- `MandiBottomSheet`
- `MandiModal`
- `MandiConfirmationDialog`
- `MandiTimeline`
- `MandiProgress`
- `MandiSkeleton`
- `MandiEmptyState`
- `MandiErrorState`
- `MandiOfflineBanner`
- `MandiRetryButton`
- `MandiToast`
- `MandiFormField`
- `MandiDropdown`
- `MandiCheckbox`
- `MandiRadioGroup`
- `MandiDateTimeDisplay`
- `MandiMoneySummary`
- `MandiApprovalBanner`
- `MandiDeliveryHeader`
- `MandiMap`
- `MandiDriverCard`
- `MandiRating`
- `MandiCreditSummary`
- `MandiOrderCard`

Components must support accessibility labels, disabled/loading states and test IDs.

### 23A.4 Restaurant Navigation

Bottom navigation:
1. Home
2. Discover
3. Requirements
4. Orders
5. Account

Global:
- outlet selector is accessible from Home and Account and available wherever outlet context affects data.
- notifications are accessible from the global header where appropriate.
- active order/delivery can be surfaced contextually without replacing the primary navigation.

### 23A.5 REST-AUTH-01 — Splash

**Purpose:** Establish authentication/session state.

Layout:
- Mandi logo/name centered.
- Minimal branded background.
- No unnecessary interaction.

Behavior:
- restore secure token/session
- call `/auth/me`
- route to authenticated experience or Login
- show a non-blocking retry state if session restoration fails because of a temporary network problem.

Never show the authenticated home screen based only on cached client state if the session is invalid.

### 23A.6 REST-AUTH-02 — Login

Layout:
- Mandi brand
- mobile number field
- primary “Continue” CTA
- terms/privacy acknowledgement where legally required.

States:
- initial
- submitting
- invalid number
- rate limited
- provider failure
- offline

API:
`POST /api/v1/auth/otp/request`

### 23A.7 REST-AUTH-03 — OTP Verification

Layout:
- masked mobile number
- six-digit OTP input
- countdown/resend
- verify CTA
- change number action.

Behavior:
- disable repeated submission while request is pending
- enforce server-side expiry and attempts
- show resend cooldown
- support mock OTP in local development only.

API:
`POST /api/v1/auth/otp/verify`

### 23A.8 REST-ONB-01 — Restaurant / Outlet Setup

Progressive setup:
1. restaurant details
2. outlet details
3. address/location
4. user role/profile

Primary CTA changes from Continue to Complete Setup.

Validation is server-authoritative.

### 23A.9 REST-HOME-01 — Restaurant Home

Information hierarchy, top to bottom:

1. Header + outlet selector
2. Large product search
3. Recommended Procurement
4. Pending Approvals
5. Buy Again
6. Open Requirements
7. Active Orders
8. Categories
9. Supplier recommendations

**Recommended Procurement card**
- product
- required quantity
- recommended supplier
- price
- ETA
- estimated total
- estimated savings where available
- “Review & Procure” CTA
- explanation such as “Best value” or “Reliable supplier”.

**Active order card**
- order number
- supplier
- current status
- ETA when delivery is active
- tap to track.

Empty state should guide the restaurant toward search or creating a requirement.

### 23A.10 REST-SEARCH-01 — Search

Layout:
- prominent search field
- recent searches
- suggested products
- categories
- optionally buy-again suggestions.

Search must debounce requests and cancel/stale-protect obsolete responses.

States:
- typing
- suggestions loading
- no suggestions
- recent searches
- error.

### 23A.11 REST-SEARCH-02 — Search Results

Product-first result structure.

Each product result should expose:
- canonical product name
- pack size/unit
- representative image
- best/current price
- availability
- number of supplier offers
- recommended offer
- quick add where unambiguous.

Sorting/filtering:
- recommended
- price
- availability
- ETA
- supplier
- brand

Do not rank based on commission.

### 23A.12 REST-PROD-01 — Product Detail

Layout:
- product image
- canonical product name
- pack/unit
- supplier offers
- price/GST display
- availability
- ETA
- supplier rating/performance indicators
- quantity selector
- Add to Cart
- Buy Again where applicable.

The restaurant is comparing actual supplier offers mapped to the same canonical product.

### 23A.13 REST-SUP-01 — Supplier Comparison

Use a vertically scannable comparison list.

Each supplier offer:
- supplier/store
- brand
- SKU/pack
- price
- GST
- effective commercial total
- ETA
- availability
- rating/performance
- recommendation badge where applicable.

Recommended offer should explain the dominant reasons:
“Best value”, “Fastest”, “Most reliable”, etc.

Never hide materially relevant commercial information.

### 23A.14 REST-REQ-01 — Requirements

Show:
- open requirements
- partially fulfilled requirements
- completed requirements
- source: manual/recommendation
- required quantity
- fulfilled quantity
- remaining quantity
- created date
- urgency where supported.

Primary CTA:
“Add Requirement”

Requirement detail must make remaining quantity explicit.

### 23A.15 REST-REC-01 — Recommended Procurement

This is a key differentiation screen.

Show a procurement plan:
- requirement
- recommended supplier
- quantity
- price
- ETA
- estimated total
- savings/benefit
- reason for recommendation.

Actions:
- Review
- Change supplier
- Adjust quantity
- Remove item
- Proceed to procurement

If no suitable supplier exists, show the unmet item clearly and offer alternatives/search rather than silently dropping it.

### 23A.16 REST-CART-01 — Cart

Group cart items by supplier order.

Each line:
- product
- supplier
- brand
- pack size
- quantity
- unit price
- GST
- line total
- availability
- remove/edit.

Summary:
- item subtotal
- GST
- delivery
- total
- payment/credit method if already selected.

If an offer changes:
- mark affected item
- refresh authoritative price/availability
- require explicit confirmation before checkout.

Never silently reprice.

### 23A.17 REST-CHECKOUT-01 — Checkout

Sections:
1. Outlet/delivery address
2. Supplier orders
3. Delivery mode
4. Payment method
5. Order totals
6. Approval requirement
7. Final confirmation

Delivery mode:
- Own Delivery
- Costonomy Delivery

Show Costonomy delivery charge only when applicable.

Final totals come from the server.

### 23A.18 REST-APPROVAL-01 — Approval

Show:
- order summary
- requested by
- supplier
- outlet
- amount
- payment method
- policy triggering approval
- item breakdown
- approval history.

Actions:
- Approve
- Reject
- Request clarification where supported.

Approval actions must be permission-gated and idempotent.

### 23A.19 REST-PAY-01 — Payment

Show:
- amount being paid/authorized
- supplier order summary
- payment method
- secure payment provider experience.

States:
- initiating
- provider interaction
- awaiting confirmation
- success
- failed
- cancelled
- reconciliation pending.

Do not display “Payment successful” until backend confirmation.

### 23A.20 REST-ORDERS-01 — Orders

Tabs/filters:
- Active
- Completed
- Cancelled
- Disputed

Order card:
- order number
- supplier
- outlet
- amount
- status
- date
- delivery ETA where relevant.

### 23A.21 REST-ORDER-TRACK-01 — Order / Delivery Tracking

Top:
- clear current status
- concrete ETA: “Arriving by 3:30 PM”
- supplier
- order amount.

Timeline:
Confirmed → Preparing → Ready → Driver Assigned → Picked Up → In Transit → Delivered

Costonomy Delivery:
- map
- restaurant destination
- driver marker
- route
- driver details/call action if supported
- last location update
- stale-location indicator
- support.

Before assignment:
“Finding the best delivery partner…”

On reassignment:
“Your delivery partner is being reassigned.”

Never fake driver movement or display stale GPS as current.

Realtime:
WebSocket → Push → Polling fallback.

### 23A.22 REST-RECEIVE-01 — Receiving

Show each supplier order item:
- requested quantity
- accepted quantity
- received quantity
- discrepancy amount.

Actions:
- Mark received
- Report discrepancy
- Add note/evidence where supported.

Receiving is not a blind “Complete” button; item-level discrepancies must be possible.

### 23A.23 REST-RATING-01 — Rating

After completion:
- overall rating
- product quality
- quantity accuracy
- packaging
- delivery
- optional comment.

Prevent duplicate rating submission.

### 23A.24 REST-CREDIT-01 — Credit Overview

Show:
- approved limit
- reserved
- utilized
- available
- due
- overdue
- credit period.

Use clear financial hierarchy.

Available credit must always match backend calculation.

### 23A.25 REST-CREDIT-02 — Credit Request

Fields:
- supplier
- outlet
- requested limit
- requested credit period
- note/supporting information where required.

Statuses:
- submitted
- under review
- approved
- approved with modified terms
- rejected
- additional information requested.

### 23A.26 REST-DISPUTE-01 — Dispute

Flow:
1. Select category
2. Select affected items
3. Enter quantity/issue
4. Add description
5. Attach evidence if supported
6. Submit
7. Track response.

Categories:
wrong product, short quantity, damaged, expired, quality, incorrect invoice, other.

Clearly state that the dispute does not change the Delivered status.

### 23A.27 REST-NOTIF-01 — Notifications

Group notifications by:
- orders
- approvals
- payments
- credit
- delivery
- marketplace/account

Unread state must be server-backed.

Actions:
- mark read
- mark all read
- deep-link to relevant object.

### 23A.28 REST-ACCOUNT-01 — Account

Sections:
- user profile
- restaurant
- outlet
- role/permissions summary
- notifications
- help/support
- legal
- logout

Users must not see actions they are not authorized to perform.

### 23A.29 Supplier Navigation

Bottom navigation:
1. Home
2. Orders
3. Catalog
4. Credit
5. More

Supplier store selector is required where a supplier user can operate multiple stores.

### 23A.30 SUP-AUTH-01 — Supplier Authentication

Same authentication foundation as restaurant users, but route to supplier experience based on server-authoritative organization/store membership and role.

### 23A.31 SUP-ONB-01 — Supplier Registration

Progressive setup:
- organization details
- store details
- address
- contact
- delivery capabilities
- catalog readiness

Show lifecycle status prominently.

### 23A.32 SUP-ONB-02 — Verification

Show:
- GST verification state
- submitted information
- pending/verified/rejected status
- action required.

Never expose internal moderation notes unnecessarily.

### 23A.33 SUP-HOME-01 — Supplier Home

Information hierarchy:
1. New orders
2. Response countdown
3. Preparing
4. Ready for pickup
5. Credit requests
6. Settlements
7. Alerts

New order card must prominently show:
- response deadline
- order value
- outlet
- requested quantities
- delivery mode
- payment/credit method
- Accept / Partial Accept / Reject

### 23A.34 SUP-ORD-01 — New Order

Detailed order:
- outlet
- products
- requested quantity
- price snapshot
- GST
- total
- response countdown
- delivery mode
- payment/credit status.

Countdown uses server deadline and must remain correct after app resume.

### 23A.35 SUP-ORD-02 — Partial Acceptance

For each item:
- requested quantity
- accepted quantity input
- reason when required.

Actions:
- accept all
- partial accept
- reject.

Server recalculates commercial totals.

### 23A.36 SUP-ORD-03 — Reject

Show standardized rejection reasons plus optional note.

Require confirmation.

Record explicit rejection separately from timeout.

### 23A.37 SUP-ORD-04 — Preparing

Show:
- accepted items
- preparation status
- outlet
- delivery mode
- expected readiness.

Primary CTA:
“Mark Ready”

### 23A.38 SUP-ORD-05 — Ready for Pickup

Show:
- order summary
- pickup information
- Costonomy delivery status if applicable
- primary “Ready for Pickup” state.

Once Costonomy delivery is requested, supplier should not be able to falsely mark pickup/delivery states that belong to the delivery provider.

### 23A.39 SUP-CATALOG-01 — Catalog

Show:
- SKU search
- filters
- availability
- price
- mapped canonical product
- status.

Primary actions:
- Add SKU
- Edit
- Change availability
- Import catalog

### 23A.40 SUP-CATALOG-02 — SKU Editor

Fields:
- canonical product
- supplier SKU code
- product name
- brand
- pack size
- unit
- image
- price
- GST
- availability.

Validation:
- required fields
- numeric price
- valid GST
- canonical mapping
- duplicate SKU handling.

### 23A.41 SUP-CATALOG-03 — Bulk Import

Stepper:
**Upload → Parse → Map → Validate → Preview → Confirm → Import → Summary**

Show row-level errors and allow correction/re-upload.

Never partially import silently.

### 23A.42 SUP-CREDIT-01 — Credit Requests

List:
- restaurant/outlet
- requested limit
- requested period
- status
- requested date.

Detail actions:
- approve
- reject
- modify
- request information.

### 23A.43 SUP-CREDIT-02 — Credit Portfolio

Show:
- total approved credit
- utilized
- reserved
- available
- due
- overdue
- utilization by restaurant
- payment behavior.

### 23A.44 SUP-SETTLE-01 — Settlements

Show:
- settlement date
- gross commercial value
- commission
- adjustments
- net settlement
- status
- associated supplier orders.

### 23A.45 SUP-PERF-01 — Performance

Show:
- acceptance rate
- timeout rate
- cancellation rate
- fill rate
- on-time performance
- ratings
- order volume
- GMV.

Purpose is supplier improvement, not punitive gamification.

### 23A.46 Global UI States

Every API-driven screen must implement:

**Loading**
- skeletons for structured content
- spinner only for small/indeterminate actions
- never block the whole screen unnecessarily.

**Empty**
- explain what is empty
- explain why it may be empty
- provide the most useful next action.

**Error**
- human-readable message
- retry
- preserve safe user input.

**Offline**
- clear offline banner/state
- allow safe read-only cached interactions
- queue only explicitly safe draft operations
- never claim order/payment success offline.

**Stale**
- indicate stale information when material
- refetch on reconnect/resume
- never silently present stale delivery/payment state as authoritative.

**Long-running**
- show progress/status
- provide retry/support where appropriate
- never leave infinite loading.

### 23A.47 Navigation and State Rules

- Navigation state is client-owned.
- Business state is server-owned.
- Deep links must resolve through authoritative API state.
- Back navigation must not accidentally resubmit mutations.
- Android back gesture/button must preserve draft data where safe.
- Unsaved destructive changes require confirmation.
- After mutation success, invalidate/refetch affected queries.
- After reconnect, refresh active orders, payment states, credit balances and delivery states.

### 23A.48 Accessibility

Minimum:
- semantic labels for icons
- sufficient text contrast
- minimum touch target
- dynamic text support
- screen-reader meaningful ordering
- no information conveyed only by color
- accessible error messages
- accessible loading announcements for major state changes
- test IDs for automated tests.

### 23A.49 Animation and Interaction

Use motion to communicate state, not decoration.

Required examples:
- subtle search/result transitions
- cart quantity feedback
- order status transition
- delivery reassignment
- skeleton-to-content transition
- bottom-sheet presentation

Animations must respect reduced-motion/accessibility settings.

### 23A.50 Mobile Technical Structure

Recommended structure for `costonomy-mp-mobile`:

```text
src/
  app/
  navigation/
  screens/
    auth/
    restaurant/
    supplier/
  components/
    common/
    product/
    supplier/
    procurement/
    order/
    delivery/
    credit/
  services/
    api/
    auth/
    payments/
    notifications/
    realtime/
  store/
  hooks/
  models/
  utils/
  theme/
  analytics/
  assets/
  tests/
```

The exact structure may follow existing `costonomy-mobile-app` conventions if they are materially better, but responsibilities must remain separated.

### 23A.51 API/UI Data Boundary

The mobile app must:
- consume server DTOs through typed API models
- map DTOs to presentation models where necessary
- never calculate authoritative money/state
- never embed business rules that belong on the server
- centralize API error mapping
- centralize authentication handling
- centralize query invalidation/retry behavior.

### 23A.52 UI Testing

Each screen requires:
- rendering test
- primary interaction test
- permission/role test where applicable
- loading test
- empty test
- error/retry test
- offline/stale test where applicable
- navigation/deep-link test where applicable.

Critical E2E flows must cover:
- search → product → supplier comparison → cart → checkout
- recommendation → procurement
- supplier acceptance timeout
- partial acceptance and alternatives
- payment failure/recovery
- delivery tracking/reassignment
- receiving/dispute
- credit request/approval/order/payment.

### 23A.53 UI Analytics

At minimum track:
- screen_view
- product_search
- search_result_view
- product_view
- supplier_comparison
- recommendation_view
- recommendation_accept
- requirement_created
- checkout_started
- checkout_validation_failed
- procurement_submitted
- approval_requested
- approval_completed
- payment_started
- payment_success
- payment_failed
- alternative_view
- alternative_selected
- supplier_accept
- supplier_partial_accept
- supplier_reject
- supplier_timeout
- delivery_assigned
- delivery_reassigned
- delivery_delayed
- order_received
- dispute_created
- rating_submitted
- credit_requested
- credit_approved
- credit_rejected.

Analytics must not include secrets, OTPs or unnecessary PII.

### 23A.54 Branding Rename Safety

All visible “Mandi” strings must be centralized through app configuration/localization resources wherever practical.

Do not scatter the product name through:
- repository names
- database identifiers
- service class names
- environment variable prefixes
- package names
- API infrastructure names.

UI component names such as `MandiButton` may be retained for now, but the architecture should make a future component-prefix rename straightforward.


## 23. Mobile UX

Modern, premium, fast, image-rich, scannable and operationally clear.

Reusable components include:
`MandiButton`, `MandiCard`, `MandiSearchBar`, `MandiProductCard`, `MandiSupplierCard`, `MandiPrice`, `MandiStatusChip`, `MandiTimeline`, `MandiSkeleton`, `MandiEmptyState`, `MandiErrorState`, `MandiBottomSheet`, `MandiQuantityStepper`, `MandiMap`, `MandiDeliveryHeader`.

These are UI component names and may be renamed later if the product brand changes; they must not force a repository/package/database rename.

### 24. Offline, Security and Observability

- Preserve unsent drafts where safe.
- Never assume an offline order succeeded.
- Never show payment success solely from a client callback.
- Refresh authoritative state after reconnect/resume/cold start.
- HTTPS outside local development.
- Secure token storage.
- Server-side authorization.
- Validation and throttling.
- Webhook signature verification.
- Secrets management.
- Audit logging.
- PII minimization.
- Never log OTPs, tokens or secrets.

Every request includes:
- request ID
- actor
- latency
- status
- error code

### 25. Catalog Import

CSV/XLSX:
**Upload → Parse → Map → Validate → Preview → Confirm → Import → Summary**

Reject malformed/invalid rows explicitly. No silent corruption.

### 26. Events and Background Jobs

Domain events include:
RequirementCreated, ProcurementSubmitted, SupplierOrderCreated, SupplierOrderAccepted, SupplierOrderPartiallyAccepted, SupplierOrderRejected, SupplierOrderExpired, SupplierOrderPreparing, SupplierOrderReady, PaymentAuthorized, PaymentCaptured, PaymentFailed, RefundCompleted, CreditReserved, CreditUtilized, CreditReleased, CreditOverdue, DeliveryRequested, DeliveryProviderSelected, DriverAssigned, DeliveryPickedUp, DeliveryLocationUpdated, DeliveryReassigned, DeliveryDelivered, ReceivingCompleted, DisputeCreated, RatingSubmitted.

Jobs include:
- supplier timeout
- payment reconciliation
- credit due/overdue
- credit auto-suspension
- stale delivery detection
- search indexing retry
- notification retry
- settlement generation
- idempotency cleanup

All jobs must be retryable, idempotent and observable.

### 27. Operations APIs

No Operations mobile UI.

Backend APIs must support:
- supplier verification/suspension/reactivation
- store/catalog inspection
- order inspection/timeline
- payments/refunds
- delivery/provider inspection/reassignment
- credit
- settlement/reconciliation
- disputes
- audit

Elevated permissions and audit logs required.

### 28. Costonomy Integration Boundary

No initial runtime dependency on Costonomy APIs.

Future adapters:
- Costonomy Inventory → Mandi Requirements
- Costonomy Consumption Intelligence → Mandi Recommendations
- Costonomy Restaurant Identity → Mandi Identity Mapping
- Costonomy Supplier Network → Mandi Supplier Mapping

Do not couple Mandi domain models to future Costonomy DTOs.

### 29. E2E Scenarios

1. Full prepaid order.
2. Supplier timeout → alternatives.
3. Partial fulfilment → remaining requirement → alternative.
4. Payment failure → supplier gets nothing.
5. Payment callback lost → server recovery without duplicate charge.
6. Delivery provider/driver failure → reassignment.
7. Credit request → modified approval → reserve → utilize → invoice → due → payment → reconcile.
8. Receiving discrepancy → dispute.

### 30. Traceability

Create:

`docs/IMPLEMENTATION_TRACEABILITY.md`

Columns:
`Requirement ID | Description | Backend | Mobile | DB Migration | API | Tests | Status`

No requirement is complete without evidence.

### 31. Claude Code Execution Sequence

1. Workspace discovery.
2. Backend foundation in **`costonomy-mp-api`**.
3. Mobile foundation in **`costonomy-mp-mobile`**.
4. Authentication.
5. Organizations and authorization.
6. Supplier onboarding.
7. Catalog.
8. Search/discovery.
9. Recommendations.
10. Requirements/procurement.
11. Cart/checkout.
12. Approval.
13. Supplier acceptance.
14. Payments.
15. Credit.
16. Delivery.
17. Realtime tracking.
18. Receiving/disputes/ratings.
19. Notifications.
20. Operations APIs.
21. Hardening.
22. Product/engineering audit.

After every phase:
- compile
- test
- fix
- document
- update traceability.

### 32. Final Guardrails

Claude Code must:
1. Never invent specified business rules.
2. Never remove failure cases.
3. Never trust mobile financial values.
4. Never trust client state transitions.
5. Never make search indexing authoritative.
6. Never make Redis authoritative for money/order/credit.
7. Isolate providers.
8. Never expose delivery bidding.
9. Never use commission in organic ranking.
10. Never create a Costonomy API dependency in MVP.
11. Never create Operations mobile UI.
12. Keep supplier SLA configurable.
13. Never silently reprice.
14. Never silently drop unmet requirements.
15. Never duplicate financial transactions.
16. Never claim unconfirmed delivery states.
17. Never fake driver movement.
18. Never leave infinite loading.
19. Never mark completion without failure-path tests.
20. Never rename the repositories to `mandi-api` or `mandi-mobile` unless explicitly instructed.
21. Keep the current display name “Mandi” isolated from stable technical identifiers so the product can be renamed later with minimal engineering impact.


### 33. UI Implementation Definition of Done

The React Native implementation is not considered complete if a screen merely renders the happy-path data.

For every specified screen:
- the defined hierarchy is implemented;
- reusable components are used instead of duplicated patterns;
- API dependencies are wired;
- authorization is enforced by the backend and reflected in UI;
- loading, empty, error, retry, offline and stale states are implemented where applicable;
- mutation buttons have disabled/submitting states;
- navigation and back behavior are implemented;
- analytics events are emitted;
- accessibility requirements are implemented;
- automated tests cover the required states;
- no hard-coded business or financial truth exists in the UI;
- screenshots/design review can be performed against the PRD without inventing major missing interaction patterns.

Claude Code must treat §23A as a binding UI specification, not as optional design guidance.
