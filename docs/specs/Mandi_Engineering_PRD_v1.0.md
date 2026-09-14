# Mandi by Costonomy --- Engineering PRD v1.0

**Implementation-grade Engineering Product Requirements Document**\
**Database:** `costonomy_mp`\
**Mobile package:** `com.costonomy.mp`\
**Language:** English initially\
**Deployment infrastructure:** Out of scope for this version

## 1. Purpose and Claude Code Contract

This document is the implementation contract for building Mandi as a
standalone restaurant procurement marketplace. Claude Code should use it
to create a production-quality backend repository and mobile repository,
including domain models, APIs, database migrations, UI, integrations,
mocks, tests, seed data, failure recovery and operational APIs.

A feature is complete only when its happy path, validation, permissions,
asynchronous behavior, concurrency, financial effects, mobile states,
analytics and failure paths are implemented and tested.

## 2. Non-Negotiable Architecture

### Backend

Create a separate `mandi-api` repository. Use the same Java/Spring Boot
versions, dependencies, package conventions, security patterns,
exception handling, logging, migration conventions and testing
conventions already used in the current `costonomy-api` codebase. Claude
Code must inspect that repository first when it is available in the VS
Code workspace.

### Mobile

Create a separate `mandi-mobile` repository. Use the same React
Native/Expo approach and proven conventions used in `costonomy-jobs`.
Package: `com.costonomy.mp`. One application contains Restaurant and
Supplier experiences.

### Database

MySQL database: `costonomy_mp`. Use Flyway migrations, foreign keys,
unique constraints, indexes, transaction boundaries and optimistic
locking where appropriate.

### Search

MySQL is authoritative. OpenSearch is a denormalized search/read index.
Search failure must not corrupt transactional workflows.

### Redis

Use Redis for cache, short-lived coordination, rate limiting,
distributed locks where necessary and realtime support. Redis is never
authoritative for money, orders or credit.

### External provider abstraction

All external services must use interfaces/adapters:

-   `PaymentProvider` → Razorpay
-   `OtpProvider` → MSG91 + Mock
-   `DeliveryProvider` → Porter, Rapido, Shadowfax + Mock
-   `MapProvider` → Google Maps Platform
-   `NotificationProvider` → Push provider

No external provider-specific model should leak into the core domain.

### Deployment

Do not implement production AWS/deployment infrastructure in this
version. Local development must work with mocks and documented
environment configuration.

## 3. Product Contract

### Core promise

> Mandi knows what a restaurant needs and connects that need to the best
> available supplier, payment/credit option and delivery option.

### Core journey

`Need → Discover → Compare → Decide → Procure → Supplier accepts → Prepare → Ready → Delivery → Receive → Complete`

### Product evolution

`Marketplace → Intelligent Procurement → Automation → Procurement Agent`

Initial product is recommendation-led. Restaurant remains in control.

## 4. Actors

### Restaurant

Owner, Admin, Purchase Manager, Store Manager, Procurement Staff,
Receiving Staff, Finance Staff.

### Supplier

Owner, Admin, Store Manager, Salesperson, Operations Staff, Finance
Staff.

### Internal Mandi Operations

Supplier verification, marketplace operations, moderation, support,
finance, delivery operations, credit visibility, dispute investigation
and audit inspection.

No operations mobile UI is required now. Backend APIs must support a
future Mandi Operations website.

## 5. Domain Model

Core entities:

-   Restaurant
-   Outlet
-   User
-   Role
-   Permission
-   UserRole
-   ProcurementPolicy
-   SupplierOrganization
-   SupplierStore
-   SupplierVerification
-   ProductCategory
-   Brand
-   CanonicalProduct
-   CanonicalProductAlias
-   SupplierSku
-   SupplierOffer
-   Requirement
-   RequirementItem
-   ProcurementRequest
-   ProcurementItem
-   SupplierOrder
-   SupplierOrderItem
-   Delivery
-   DeliveryProvider
-   DeliveryQuote
-   DeliveryEvent
-   Payment
-   PaymentTransaction
-   Refund
-   CreditAgreement
-   CreditReservation
-   CreditTransaction
-   Invoice
-   Settlement
-   CommissionConfiguration
-   Rating
-   Dispute
-   Device
-   Notification
-   AuditLog
-   IdempotencyRecord

### Ownership

**Costonomy/Mandi platform owns:** canonical products, categories,
canonical images, marketplace ranking, restaurant records, supplier
organization/store records, order orchestration, payment orchestration,
credit workflow/ledger, delivery orchestration, settlements, ratings,
disputes, notifications and audit.

**Supplier owns:** actual SKU identity, SKU image, price, availability,
store operations, own-delivery configuration and credit decisions.

## 6. Database Specification

### Restaurant and outlet

`restaurant`: id, legal_name, display_name, gstin nullable, status,
created_at, updated_at, version.

`outlet`: id, restaurant_id, name, address_line_1, address_line_2,
landmark, city, state, pincode, latitude, longitude, google_place_id,
contact_name, contact_phone, delivery_instructions, status, created_at,
updated_at, version.

Indexes: restaurant_id, pincode, status, geolocation as supported.

### Users and authorization

`user`: id, mobile_number, mobile_verified_at, email, name, status,
last_login_at, created_at, updated_at, version.

Membership tables connect users to restaurant organizations/outlets and
supplier organizations/stores.

`role`, `permission`, `role_permission`, `user_role`.

Authorization model:

`Role → Permission → Policy → Approval`

Backend is authoritative.

### Supplier

`supplier_organization`: id, legal_name, display_name, gstin,
verification_status, lifecycle_status, created_at, updated_at, version.

`supplier_store`: id, supplier_organization_id, name, address, city,
state, pincode, latitude, longitude, contact fields,
operating_hours_json, status, max_delivery_radius_km,
own_delivery_min_order_value, own_delivery_enabled,
costonomy_delivery_enabled, response_sla_seconds.

Supplier lifecycle:

`INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE`

Possible operational states: `SUSPENDED`, `OFFLINE`.

### Product

`canonical_product`: id, category_id, name, normalized_name,
description, default_unit, default_pack_size, primary_image_url, status,
search_version, timestamps.

`canonical_product_alias`: canonical_product_id, alias,
normalized_alias.

`brand`: id, name, status.

### Supplier SKU and offer

`supplier_sku`: id, supplier_store_id, canonical_product_id,
supplier_sku_code, name, brand_id, pack_size, unit, image_url, status,
timestamps, version.

Unique: supplier_store_id + supplier_sku_code.

`supplier_offer`: id, supplier_sku_id, selling_price, gst_rate,
availability_status, available_quantity nullable, effective_from,
effective_to, status, version.

Availability initially: `AVAILABLE`, `OUT_OF_STOCK`.

### Requirement

`requirement`: id, outlet_id, created_by, status, source, requested_at,
fulfilled_at, timestamps, version.

`requirement_item`: id, requirement_id, canonical_product_id,
requested_quantity, unit, notes, status, fulfilled_quantity,
remaining_quantity.

Requirement states:

`OPEN`, `PARTIALLY_FULFILLED`, `FULFILLED`, `CANCELLED`, `EXPIRED`.

### Procurement and supplier orders

`procurement_request`: id, outlet_id, requirement_id nullable,
created_by, status, approval_status, payment_status, total_item_value,
total_gst, total_delivery_fee, total_amount, timestamps, version.

`procurement_item`: id, procurement_request_id, requirement_item_id,
canonical_product_id, requested_quantity, selected_supplier_store_id,
selected_supplier_sku_id, price_snapshot, gst_snapshot,
delivery_fee_allocation, status.

`supplier_order`: id, procurement_request_id, supplier_store_id,
human_order_number, status, response_deadline_at, accepted_at,
rejected_at, rejection_reason, subtotal, gst, delivery_fee, total,
payment_status, credit_status, delivery_mode, timestamps, version.

Human order number example: `MP-260914-001284`.

`supplier_order_item`: id, supplier_order_id, procurement_item_id,
supplier_sku_id, requested_quantity, accepted_quantity,
fulfilled_quantity, unit_price_snapshot, gst_snapshot, line_total,
status, rejection_reason.

### Delivery

`delivery`: id, supplier_order_id, mode, status, provider_id,
current_latitude, current_longitude, eta_at, pickup_eta_at,
delivered_at, proof_type, proof_reference, timestamps, version.

`delivery_quote`: id, delivery_id, provider_id, quoted_price,
estimated_pickup_at, estimated_delivery_at, status, received_at.

`delivery_event`: id, delivery_id, event_type, event_time, latitude,
longitude, provider_event_id, metadata_json, created_at.

### Payments

`payment`: id, procurement_request_id, payment_method, status,
authorized_amount, captured_amount, refunded_amount, provider,
provider_payment_id, timestamps, version.

`payment_transaction`: id, payment_id, transaction_type, amount,
currency, provider_reference, status, idempotency_key, created_at.

`refund`: id, payment_id, amount, reason, status, provider_reference,
created_at, completed_at.

### Credit

`credit_agreement`: id, restaurant_id, outlet_id, supplier_store_id,
requested_limit, approved_limit, credit_period_days, grace_period_days,
max_single_order_credit, max_overdue_amount, auto_suspend_enabled,
status, effective_from, review_at, approved_by, timestamps, version.

`credit_reservation`: id, agreement_id, supplier_order_id, amount,
status, expires_at.

`credit_transaction`: id, agreement_id, supplier_order_id, invoice_id,
type, amount, balance_after, due_at, transaction_reference, created_at.

Credit formula:

`Available = Approved Limit - Reserved - Utilized`

Do not allow available credit below zero.

### Invoice and settlement

`invoice`: id, supplier_order_id, supplier_invoice_number, invoice_date,
taxable_amount, cgst, sgst, igst, total_amount, document_url,
reconciliation_status, timestamps.

`settlement`: id, supplier_store_id, settlement_reference, period_start,
period_end, gross_amount, commission_amount, adjustments, net_amount,
status, scheduled_at, settled_at, created_at.

### Rating/dispute/notification/audit

`rating`: order, restaurant, supplier store, product_quality,
quantity_accuracy, packaging, delivery, overall, comment, created_at.

`dispute`: supplier_order_id, supplier_order_item_id, raised_by,
category, description, status, evidence_url, supplier_response,
resolution_code, timestamps.

`device`: user_id, platform, push_token, app_version, last_seen_at,
status.

`notification`: user_id, event_type, title, body, deep_link, read_at,
created_at.

`audit_log`: actor, entity, action, before_json, after_json, request_id,
created_at.

`idempotency_record`: idempotency_key, actor, operation, request_hash,
response_status, response_body, timestamps, expiry.

## 7. State Machines

### Supplier order

`DRAFT → PENDING_ACCEPTANCE → CONFIRMED / PARTIALLY_ACCEPTED / REJECTED / EXPIRED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED → COMPLETED`

Conditional cancellation applies before pickup according to policy.

Dispute is separate and does not change `DELIVERED`.

### Payment

`INITIATED → AUTHORIZED → CAPTURED → REFUND_PENDING → REFUNDED`

Failure branches: `AUTHORIZATION_FAILED`, `REFUND_FAILED`.

### Credit

`REQUESTED → UNDER_REVIEW → APPROVED / REJECTED → ACTIVE → RESERVED → UTILIZED → INVOICED → DUE → PAID / OVERDUE`

### Delivery

`DELIVERY_REQUESTED → QUOTE_RECEIVED → PROVIDER_SELECTED → DRIVER_ASSIGNED → DRIVER_AT_PICKUP → PICKED_UP → IN_TRANSIT → ARRIVED_AT_DESTINATION → DELIVERED`

Failure branches:

`QUOTE_FAILED`, `PROVIDER_UNAVAILABLE`, `DRIVER_CANCELLED`,
`PICKUP_FAILED`, `DELIVERY_FAILED`, `CANCELLED`.

Recovery should be attempted before terminal failure where possible.

## 8. Authentication

Primary flow:

`Mobile → OTP request → MSG91 → Verify → JWT/session → Secure storage → Load user/org/role`

Use the implementation conventions from `costonomy-jobs`.

Requirements:

-   OTP expiry
-   attempt limits
-   resend cooldown
-   request throttling
-   token refresh
-   logout
-   device registration
-   mock OTP provider.

## 9. Search and OpenSearch

Search must support:

-   exact
-   prefix
-   fuzzy/typo tolerance
-   aliases
-   brand
-   category
-   pack size
-   supplier SKU
-   canonical product.

Index purchasable supplier offers, with denormalized fields such as:

``` json
{
  "offerId": "...",
  "canonicalProductId": "...",
  "canonicalName": "Paneer 1kg",
  "aliases": ["paneer"],
  "category": "Dairy",
  "brand": "Amul",
  "supplierStoreId": "...",
  "supplierName": "ABC Foods",
  "price": 410,
  "gstRate": 5,
  "availability": "AVAILABLE",
  "deliveryEtaMinutes": 90,
  "rating": 4.5
}
```

If OpenSearch fails, transactional operations continue and indexing
retries asynchronously.

## 10. Recommendation

Default objective: **Best Value / Recommended**.

Signals:

-   price
-   availability
-   delivery time
-   fill rate
-   on-time performance
-   cancellation
-   quality
-   rating.

Commission is never a ranking input.

If historical data is insufficient, use deterministic baseline ranking
and never fabricate metrics.

## 11. Requirements and Procurement

Core hierarchy:

`Requirement → Procurement Request → Supplier Order(s) → Fulfilment → Delivery → Receiving → Completion`

One procurement request can create multiple supplier orders.

Unfulfilled quantities remain attached to the requirement.

Restaurant manually chooses alternative suppliers initially.

## 12. Cart and Checkout

Cart shows:

-   product
-   quantity
-   supplier
-   price
-   GST
-   delivery
-   payment/credit
-   total.

Checkout must revalidate:

-   supplier active status
-   price
-   availability
-   serviceability
-   credit
-   delivery availability.

If price changes, require explicit confirmation.

Never trust mobile totals.

## 13. Supplier Acceptance

Default SLA: **60 seconds**.

SLA is configurable, at minimum globally and per supplier store.

Store:

`response_sla_seconds`

Order stores:

`response_deadline_at`

Countdown is based on server deadline, not device clock.

Acceptance and timeout must be concurrency-safe.

A supplier cannot accept an expired order.

Explicit rejection and timeout are separate analytics/business outcomes.

## 14. Partial Fulfilment

Supplier accepts quantity per line.

Example:

``` text
Paneer 20 requested → 20 accepted
Cream 10 requested → 6 accepted
Cheese 5 requested → 0 accepted
```

Restaurant can:

1.  accept partial;
2.  find supplier for remaining;
3.  find another supplier for the full requirement.

Restaurant is never forced to accept partial fulfilment.

## 15. Alternative Supplier Recovery

On supplier rejection, timeout, availability failure or insufficient
quantity:

-   preserve unmet requirement;
-   present alternatives;
-   avoid requiring requirement recreation;
-   link replacement supplier order to original requirement/procurement
    item.

## 16. Delivery

### Supplier Own Delivery

-   supplier-controlled;
-   configurable max radius;
-   configurable minimum order;
-   no live tracking initially;
-   restaurant can choose it;
-   Costonomy is not responsible for supplier-managed logistics.

### Costonomy Delivery

-   restaurant pays delivery;
-   Costonomy requests provider quotes;
-   Costonomy internally selects provider;
-   restaurant does not see bidding;
-   live tracking where provider supports.

Restaurant sees final price only, e.g.:

`Costonomy Delivery — ₹84`

### Provider architecture

-   Porter
-   Rapido
-   Shadowfax
-   future Costonomy Fleet
-   Mock Delivery Provider

### Google Maps

Use Google Maps Platform for:

-   maps
-   geocoding
-   place search
-   route visualization
-   driver/restaurant markers.

## 17. Delivery UI

The tracking screen is a flagship experience inspired by the clarity and
polish of Swiggy/Zomato.

Before driver assignment:

> Finding the best delivery partner...

After assignment:

-   map
-   driver marker
-   restaurant marker
-   route
-   concrete arrival time
-   current state
-   timeline
-   driver details where supported
-   call action where supported
-   support action.

Prefer:

> Arriving by 3:30 PM

over only:

> 30--40 min

Timeline:

`Confirmed → Preparing → Ready → Partner assigned → On the way → Near restaurant → Delivered`

No fake movement when GPS is stale.

## 18. Delivery Realtime

Use:

`WebSocket + Push + Polling fallback`

On tracking screen:

1.  fetch authoritative state;
2.  connect realtime;
3.  process events;
4.  reconnect on failure;
5.  refetch after reconnect;
6.  refetch on app resume;
7.  restore state after cold start.

## 19. Mock Delivery Provider

Mandatory for development.

Simulate:

-   quote
-   driver assignment
-   GPS movement
-   delay
-   driver cancellation
-   provider failure
-   reassignment
-   delivered.

Internal/dev endpoints can advance/fail mock deliveries but must be
protected.

## 20. Delivery Proof

Support where provider capabilities permit:

-   pickup OTP
-   delivery OTP
-   timestamp
-   GPS
-   recipient
-   photo
-   signature.

Never require unsupported provider capabilities.

## 21. Payments

Razorpay initially, behind `PaymentProvider`.

Flow:

`Order → Payment authorization → Supplier acceptance → Capture accepted value → Fulfilment → Delivery → Settlement`

If payment fails, supplier receives nothing.

Server calculates:

-   price
-   GST
-   delivery
-   total
-   commission
-   credit.

### Webhooks

Verify signature, persist provider references, make handlers idempotent,
reconcile unexpected states and never blindly mutate order state.

## 22. Refunds

Support full and partial refunds.

Reasons:

-   rejection
-   partial acceptance
-   cancellation
-   delivery failure
-   approved dispute
-   duplicate payment
-   provider reversal.

State:

`REQUESTED → PROCESSING → COMPLETED`

or `FAILED`.

Refunds must be idempotent.

## 23. Commission and Settlement

Default commission:

**1% of item value + GST**, paid by supplier.

Delivery excluded.

Partial fulfilment commission applies only to accepted commercial value.

Default settlement:

**T+2**, configurable per supplier/store.

Settlement must reconcile to orders, payment transactions, commissions
and adjustments.

## 24. Supplier Credit

Store-specific supplier credit.

Restaurant requests:

-   limit
-   period
-   note.

Supplier can:

-   approve
-   reject
-   modify
-   request information.

Supplier owns credit risk. Mandi does not fund, guarantee, own
receivables or absorb losses.

Credit controls:

-   grace period
-   maximum overdue amount
-   auto suspension
-   limit reduction
-   maximum single-order credit
-   review date.

Changes require audit and restaurant notification.

## 25. Receiving

Receiving is separate from delivery.

Example:

``` text
Ordered: 20
Accepted: 18
Received: 17
```

Restaurant records actual quantity.

Issue types:

-   short
-   damaged
-   wrong item
-   expired
-   other.

Photos supported.

## 26. Disputes

Categories:

-   wrong product
-   short quantity
-   damaged
-   expired
-   quality
-   incorrect invoice
-   other.

Primary resolution relationship is Restaurant ↔ Supplier.

Mandi stores evidence, supplier response, status and resolution
information.

Dispute does not change Delivered order state.

## 27. Ratings

After receiving/completion:

-   product quality
-   quantity accuracy
-   packaging
-   delivery
-   overall
-   comment.

Supplier rating is public to restaurants.

## 28. Permissions and Approvals

Authorization:

`Role → Permission → Policy → Approval`

Policy can depend on:

-   order value
-   supplier
-   category
-   outlet
-   payment method
-   requester role
-   combinations.

Example:

`order.total > ₹20,000 → Owner approval`

Server-side enforcement is mandatory.

## 29. Notifications

Initial channels:

-   push
-   in-app.

Events:

-   order placed
-   accepted
-   partial accepted
-   rejected
-   timeout
-   payment success/failure
-   preparing
-   ready
-   delivery assigned
-   delivery delayed
-   delivered
-   alternative available
-   credit approved
-   credit changed
-   due
-   overdue.

Every notification should support idempotency and deep linking.

## 30. Mobile Screen Specification

Every screen must specify:

-   Screen ID
-   purpose
-   entry points
-   navigation
-   information hierarchy
-   components
-   API dependencies
-   realtime dependencies
-   permissions
-   primary CTA
-   secondary actions
-   loading
-   empty
-   error
-   offline
-   stale
-   retry
-   analytics
-   accessibility
-   edge cases.

### Restaurant screen catalogue

`REST-AUTH-01` Splash\
`REST-AUTH-02` Login\
`REST-AUTH-03` OTP Verification\
`REST-ONB-01` Restaurant/Outlet Setup\
`REST-HOME-01` Home\
`REST-SEARCH-01` Search\
`REST-SEARCH-02` Search Results\
`REST-PROD-01` Product Detail\
`REST-SUP-01` Supplier Comparison\
`REST-REQ-01` Requirements\
`REST-REC-01` Recommended Procurement\
`REST-CART-01` Cart\
`REST-CHECKOUT-01` Checkout\
`REST-APPROVAL-01` Approval\
`REST-PAY-01` Payment\
`REST-ORDERS-01` Orders\
`REST-ORDER-TRACK-01` Order/Delivery Tracking\
`REST-RECEIVE-01` Receiving\
`REST-RATING-01` Rating\
`REST-CREDIT-01` Credit Overview\
`REST-CREDIT-02` Credit Request\
`REST-DISPUTE-01` Dispute\
`REST-NOTIF-01` Notifications\
`REST-ACCOUNT-01` Account

### Supplier screen catalogue

`SUP-AUTH-01` Authentication\
`SUP-ONB-01` Supplier Registration\
`SUP-ONB-02` Verification\
`SUP-HOME-01` Supplier Home\
`SUP-ORD-01` New Order\
`SUP-ORD-02` Partial Acceptance\
`SUP-ORD-03` Reject\
`SUP-ORD-04` Preparing\
`SUP-ORD-05` Ready for Pickup\
`SUP-CATALOG-01` Catalog\
`SUP-CATALOG-02` SKU Editor\
`SUP-CATALOG-03` Bulk Import\
`SUP-CREDIT-01` Credit Requests\
`SUP-CREDIT-02` Credit Portfolio\
`SUP-SETTLE-01` Settlements\
`SUP-PERF-01` Performance

## 31. Restaurant UI Requirements

### Home

Order:

1.  outlet selector
2.  prominent product search
3.  recommended procurement
4.  pending approvals
5.  buy again
6.  open requirements
7.  active orders
8.  categories
9.  recommended suppliers.

### Search

Fast suggestions, recent searches, typo tolerance and category/product
hints.

### Product Detail

Canonical image, product information, brand/pack size, supplier offers,
price, GST, availability, delivery time and trust signals.

### Supplier Comparison

Show price, availability, delivery time, rating and reliability.
Recommended option clearly labelled.

### Requirements

Open, partial, fulfilled. Add/edit/cancel/find suppliers/procure.

### Cart/Checkout

Unified restaurant experience even when backend splits into multiple
supplier orders.

### Order Tracking

Consumer-grade map/timeline/realtime experience.

### Receiving

Ordered vs accepted vs received with issue capture.

## 32. Supplier UI Requirements

### Supplier Home

New orders, response countdown, preparing orders, ready orders,
settlement summary, credit requests and alerts.

### New Order

High-priority 60-second countdown, restaurant/outlet, items, quantities,
value, delivery mode and actions.

### Partial Acceptance

Per-item quantity entry from 0 through requested quantity.

### Catalog

Supplier SKU search/filter/edit.

### Bulk Import

`Upload → Parse → Map → Validate → Preview → Confirm → Import → Summary`

Provide row-level errors and retry.

### Credit

Requests, approval/modification/rejection, portfolio and outstanding
exposure.

### Settlement

Gross, commission, adjustments, net and status.

### Performance

Acceptance, timeout, rejection, fill rate, cancellation, on-time and
rating.

## 33. Mobile Design System

Create a reusable Mandi design system inspired by the strengths of
Swiggy/Zomato and synchronized with the existing Costonomy visual
language.

Do not copy proprietary UI.

Define reusable tokens/components for:

-   typography
-   spacing
-   radius
-   elevation
-   colors
-   buttons
-   cards
-   search
-   bottom sheets
-   status chips
-   skeletons
-   banners
-   empty/error states
-   timelines
-   maps
-   animations.

Exact Costonomy visual tokens should be derived from the current
Costonomy mobile implementation/reference rather than guessed.

## 34. Mobile Reliability

Every data-driven screen must handle:

`Loading → Loaded → Empty → Error → Offline → Unauthorized → Stale → Retrying`

Every mutation:

`Idle → Submitting → Success / Validation Error / Business Error / Network Error → Retry`

No infinite spinners.

Critical buttons must prevent duplicate taps but server idempotency
remains mandatory.

## 35. REST API Catalogue

Use existing `costonomy-api` conventions and OpenAPI.

### Auth

``` text
POST /api/v1/auth/otp/request
POST /api/v1/auth/otp/verify
POST /api/v1/auth/refresh
POST /api/v1/auth/logout
GET  /api/v1/auth/me
POST /api/v1/devices
DELETE /api/v1/devices/{id}
```

### Restaurant/outlet

``` text
POST /api/v1/restaurants
GET  /api/v1/restaurants/{id}
POST /api/v1/restaurants/{id}/outlets
GET  /api/v1/outlets/{id}
PATCH /api/v1/outlets/{id}
GET  /api/v1/outlets/{id}/users
```

### Supplier

``` text
POST /api/v1/suppliers
POST /api/v1/suppliers/{id}/stores
GET  /api/v1/supplier-stores/{id}
PATCH /api/v1/supplier-stores/{id}
POST /api/v1/suppliers/{id}/verification
```

### Catalog

``` text
GET  /api/v1/products
GET  /api/v1/products/{id}
GET  /api/v1/products/{id}/offers
POST /api/v1/supplier-stores/{id}/skus
PATCH /api/v1/supplier-skus/{id}
POST /api/v1/supplier-stores/{id}/catalog/import
GET  /api/v1/catalog/imports/{id}
```

### Search

``` text
GET /api/v1/search/products?q=
GET /api/v1/search/suppliers?q=
GET /api/v1/search/suggestions?q=
```

### Requirements

``` text
POST /api/v1/outlets/{id}/requirements
GET  /api/v1/outlets/{id}/requirements
GET  /api/v1/requirements/{id}
PATCH /api/v1/requirements/{id}
POST /api/v1/requirements/{id}/find-suppliers
POST /api/v1/requirements/{id}/procure
```

### Procurement

``` text
POST /api/v1/procurements
GET  /api/v1/procurements/{id}
POST /api/v1/procurements/{id}/validate
POST /api/v1/procurements/{id}/submit
POST /api/v1/procurements/{id}/cancel
POST /api/v1/procurements/{id}/approve
POST /api/v1/procurements/{id}/reject
```

### Supplier orders

``` text
GET  /api/v1/supplier-orders/{id}
POST /api/v1/supplier-orders/{id}/accept
POST /api/v1/supplier-orders/{id}/partial-accept
POST /api/v1/supplier-orders/{id}/reject
POST /api/v1/supplier-orders/{id}/preparing
POST /api/v1/supplier-orders/{id}/ready
POST /api/v1/supplier-orders/{id}/cancel
```

### Delivery

``` text
POST /api/v1/supplier-orders/{id}/delivery/quote
POST /api/v1/deliveries/{id}/book
GET  /api/v1/deliveries/{id}
GET  /api/v1/deliveries/{id}/events
POST /api/v1/deliveries/{id}/cancel
POST /api/v1/deliveries/{id}/reassign
```

### Receiving/disputes

``` text
POST /api/v1/supplier-orders/{id}/receive
GET  /api/v1/supplier-orders/{id}/receiving
POST /api/v1/supplier-orders/{id}/disputes
GET  /api/v1/disputes/{id}
POST /api/v1/disputes/{id}/response
```

### Credit

``` text
POST /api/v1/credit/requests
GET  /api/v1/credit/agreements
GET  /api/v1/credit/agreements/{id}
POST /api/v1/credit/agreements/{id}/approve
POST /api/v1/credit/agreements/{id}/reject
POST /api/v1/credit/agreements/{id}/modify
GET  /api/v1/credit/agreements/{id}/ledger
```

### Payments

``` text
POST /api/v1/payments
GET  /api/v1/payments/{id}
POST /api/v1/payments/{id}/confirm
POST /api/v1/payments/{id}/refund
POST /api/v1/webhooks/razorpay
```

### Notifications

``` text
GET  /api/v1/notifications
POST /api/v1/notifications/{id}/read
POST /api/v1/notifications/read-all
```

Exact DTO naming and URL conventions must be aligned with
`costonomy-api` before implementation.

## 36. API Error Contract

Use the established Costonomy API error envelope where available.

At minimum:

``` json
{
  "code": "SUPPLIER_ORDER_EXPIRED",
  "message": "This order can no longer be accepted.",
  "requestId": "..."
}
```

No stack traces or sensitive provider data.

## 37. Idempotency and Concurrency

Idempotency is mandatory for:

-   procurement creation/submission
-   supplier acceptance/partial acceptance/rejection
-   payment creation/confirmation
-   refunds
-   credit reservation/utilization
-   delivery booking/reassignment
-   receiving
-   dispute creation.

Client sends `Idempotency-Key`.

Same key + same payload → original response.

Same key + different payload → `IDEMPOTENCY_KEY_REUSE`.

Concurrency protection is mandatory for:

-   acceptance vs timeout
-   duplicate acceptance
-   cancellation vs acceptance
-   duplicate payment
-   duplicate credit reservation
-   duplicate delivery booking.

## 38. Background Jobs

Implement retryable, idempotent, observable jobs for:

-   supplier timeout
-   payment reconciliation
-   credit due/overdue evaluation
-   credit suspension
-   delivery stale-state detection
-   search indexing retry
-   notification retry
-   settlement generation
-   expired idempotency cleanup.

## 39. Operations APIs

No operations mobile UI.

Provide secure backend APIs for future website:

### Supplier operations

Search, verify, approve, suspend, reactivate, inspect stores/catalog.

### Order operations

Search, inspect state history, payment, delivery, credit.

### Delivery operations

Inspect providers/quotes/state, reassign and intervene where authorized.

### Finance

Payment inspection, refund inspection, settlement/reconciliation.

### Credit

Agreement, ledger, exposure inspection.

### Disputes

Search, evidence, supplier response, operational resolution.

### Audit

Search by actor/entity/action/date.

Every operational mutation is permission-protected and audited.

## 40. Catalog Import

Support CSV and XLSX.

Flow:

`Upload → Parse → Map columns → Validate → Preview → Confirm → Import → Summary`

Validation must identify:

-   missing required fields
-   invalid product mapping
-   invalid price
-   invalid GST
-   invalid availability
-   duplicate SKU
-   malformed row.

Import should not partially corrupt the catalogue. Use transaction/batch
strategy with clear row-level errors.

## 41. Address and Serviceability

Outlet address includes Google Place ID, formatted address, latitude,
longitude, landmark, contact and delivery instructions.

Supplier store configures:

-   max radius
-   own-delivery minimum order
-   optional serviceable pincodes/areas
-   operating hours.

Serviceability should primarily use geographic distance plus supplier
configuration, with optional pincode/area overrides.

## 42. Analytics

Core events:

``` text
app_open
login_started
login_success
product_search
search_result_view
product_view
supplier_view
recommendation_view
recommendation_accept
requirement_created
supplier_comparison
checkout_started
checkout_validation_failed
procurement_submitted
approval_requested
approval_completed
supplier_accept
supplier_partial_accept
supplier_reject
supplier_timeout
alternative_view
alternative_selected
payment_started
payment_success
payment_failed
delivery_requested
delivery_assigned
delivery_delayed
delivery_reassigned
order_received
dispute_created
rating_submitted
credit_requested
credit_approved
credit_rejected
```

## 43. Observability

Every request:

-   request ID
-   actor
-   latency
-   status
-   error code.

Critical logs include order/procurement/payment/delivery/credit
identifiers.

Never log OTPs, access tokens, secrets or unnecessary sensitive PII.

## 44. Security

Mandatory:

-   HTTPS outside local
-   JWT
-   secure mobile token storage
-   server-side authorization
-   validation
-   rate limiting
-   OTP throttling
-   webhook signature verification
-   secrets management
-   audit logging
-   PII minimization
-   client values never trusted for money/state.

## 45. Business Rules

1.  Mandi is standalone at launch.
2.  No Costonomy API dependency initially.
3.  Restaurant procurement is outlet-level.
4.  Canonical products are platform-owned.
5.  Supplier actual SKUs are supplier-owned.
6.  Supplier offers map to canonical products.
7.  Organic ranking is Best Value/Recommended.
8.  Commission cannot influence ranking.
9.  Supplier response SLA defaults to 60 seconds but is configurable.
10. Server deadline is authoritative.
11. Timeout and rejection are distinct.
12. Partial acceptance is per item.
13. Restaurant is never forced to accept partial fulfilment.
14. Unfulfilled quantities remain recoverable.
15. Restaurant chooses alternatives initially.
16. Payment failure prevents supplier submission.
17. Restaurant pays only accepted commercial value.
18. Commission defaults to 1% of item + GST.
19. Delivery is excluded from commission.
20. Credit is supplier/store-specific.
21. Supplier owns credit risk.
22. Credit reservation precedes utilization.
23. Delivery booking starts after Ready for Pickup.
24. Costonomy internally selects provider.
25. Provider bidding is hidden.
26. Own Delivery has no live tracking initially.
27. Receiving is separate from delivery.
28. Dispute does not alter Delivered state.
29. Backend is authoritative.
30. Financial operations require reconciliation.
31. Critical mutations require idempotency.
32. Critical state transitions are concurrency-safe.
33. Business-critical mutations are auditable.

## 46. Edge Cases

### Order

-   duplicate Place Order → idempotent single procurement;
-   supplier acceptance vs timeout → atomic winner;
-   acceptance after expiry → reject;
-   supplier unavailable at checkout → revalidate + alternatives;
-   price changes → explicit confirmation;
-   partial acceptance → preserve remaining requirement;
-   supplier suspension with active order → existing order follows
    operational policy; new orders blocked.

### Payment

-   client timeout after successful payment → server state recovery;
-   duplicate webhook → idempotent;
-   out-of-order webhook → reconcile;
-   captured payment followed by rejection → appropriate refund/release;
-   duplicate refund request → idempotent.

### Credit

-   credit changes during checkout → revalidate;
-   duplicate reservation → unique constraint + transaction;
-   limit reduced below existing exposure → preserve existing debt;
    block new exposure according to policy.

### Delivery

-   quote timeout → alternate provider;
-   driver cancellation → reassignment;
-   provider outage → alternate provider;
-   supplier not ready → no false pickup;
-   realtime disconnect → reconnect + authoritative refetch;
-   app killed → restore state;
-   stale GPS → show freshness, no fake movement;
-   delivered but not received → keep Delivered + issue workflow.

### Authorization

-   permission revoked while screen open → server rejects and client
    refreshes;
-   outlet switched → reload outlet-scoped data and permissions.

### Search

-   OpenSearch unavailable → controlled degradation; transactions
    continue;
-   stale index → checkout revalidates authoritative MySQL data.

## 47. E2E Test Scenarios

### E2E-01 Full prepaid order

Restaurant login → search → compare → cart → checkout → payment →
supplier accept → prepare → ready → delivery → receiving → rating.

### E2E-02 Supplier timeout

Submit → no response → configurable 60-second default expires →
alternatives → alternative supplier.

### E2E-03 Partial fulfilment

20 requested → 12 accepted → restaurant accepts partial → remaining 8 →
alternative supplier.

### E2E-04 Credit

Request → supplier modifies → restaurant notified → agreement active →
reservation → acceptance → utilization → invoice → due → payment →
ledger reconciliation.

### E2E-05 Provider failure

Ready → quote → provider → driver → driver cancellation → alternative
provider → delivery.

### E2E-06 Payment failure

Checkout → payment failure → supplier receives nothing → cart remains
recoverable.

## 48. Seed Data

Local environment must contain:

### Restaurant

One organization, two outlets, multiple role-based users.

### Suppliers

Supplier A: Hyderabad + Secunderabad. Supplier B: Hyderabad.

### Products

Paneer, chicken, oil, rice, cream, cheese, flour, sugar, cleaning
liquid, packaging.

Include multiple brands, prices, availability, ratings and historical
fulfilment signals.

### Credit

At least two active supplier agreements.

### Orders

Pending acceptance, preparing, ready, delivery, delivered, disputed and
completed examples.

## 49. Mock Integrations

### Mock OTP

Configurable development OTP.

### Mock payment

Success, failure, timeout, duplicate webhook and refund.

### Mock delivery

Quote, assignment, GPS movement, delay, cancellation, provider failure,
reassignment and completion.

### Mock notifications

Persist notification payloads and optionally log them.

## 50. Testing Standard

### Backend

Unit, repository, controller/API, integration, state-machine, financial,
credit-ledger, webhook and concurrency tests.

### Mobile

Component, navigation, state, API, offline/reconnect, background/resume
and critical journey tests.

### Concurrency tests

Mandatory for acceptance/timeout, duplicate acceptance, payment, credit
reservation and delivery booking.

### Definition

> A feature is not complete until its failure paths are tested.

## 51. Claude Code Execution Sequence

### Phase 0 --- Workspace discovery

Inspect current `costonomy-api` and `costonomy-jobs` before coding.
Record versions, conventions, security, auth, migration, navigation,
state management and API patterns.

### Phase 1 --- Backend foundation

Project, modules, database, Flyway, Redis, OpenSearch abstraction,
OpenAPI, security, exceptions, logging, tests.

### Phase 2 --- Mobile foundation

React Native/Expo, navigation, theme, design system, API client, auth
state, secure storage, mock environment.

### Phase 3 --- Authentication

OTP, MSG91 adapter, mock OTP, JWT/session, logout, device.

### Phase 4 --- Organizations and authorization

Restaurant/outlets, supplier organization/store, roles, permissions,
policies.

### Phase 5 --- Catalog

Canonical products, supplier SKUs, offers, images, import, indexing.

### Phase 6 --- Search and discovery

Search, suggestions, product detail, comparison, ranking,
recommendation.

### Phase 7 --- Requirements and procurement

Requirements, procurement, supplier orders, cart, checkout,
revalidation, alternatives.

### Phase 8 --- Supplier response

Configurable SLA, acceptance, partial acceptance, rejection, timeout,
concurrency.

### Phase 9 --- Payments

Razorpay adapter, payment state, webhooks, refunds, reconciliation,
mock.

### Phase 10 --- Credit

Requests, agreements, reservations, utilization, ledger, invoices,
due/overdue, suspension.

### Phase 11 --- Delivery

Provider abstraction, quotes, selection, booking, Google Maps, realtime,
mock provider, recovery and proof.

### Phase 12 --- Receiving/disputes/ratings

Complete post-delivery lifecycle.

### Phase 13 --- Notifications

Events, push, in-app, deep links, retry.

### Phase 14 --- Operations APIs

Future operations website backend capabilities.

### Phase 15 --- Hardening

All tests, concurrency, webhooks, provider failures, offline/reconnect
and reconciliation.

### Phase 16 --- Product audit

Produce a traceability checklist:

`Requirement → Implemented → Tested → Edge case covered → Open issue`

Do not claim completion without evidence.

## 52. Definition of Done

A feature is done only if:

-   migration exists;
-   backend domain/service exists;
-   API exists;
-   OpenAPI documentation exists;
-   authorization exists;
-   mobile UI exists where applicable;
-   loading/empty/error/offline states exist;
-   analytics exists;
-   audit exists where required;
-   idempotency exists where required;
-   concurrency is addressed;
-   mock provider exists where external dependency exists;
-   automated tests exist;
-   seed data exists where useful;
-   defined edge cases are covered.

## 53. Final Guardrails

Claude Code must:

1.  never invent specified business rules;
2.  never remove failure cases;
3.  never trust mobile financial values;
4.  never trust client state transitions;
5.  never make OpenSearch authoritative;
6.  never make Redis authoritative for money/order/credit;
7.  isolate external providers;
8.  never expose delivery bidding;
9.  never use commission in organic ranking;
10. never create Costonomy API dependency;
11. never build operations mobile UI;
12. keep supplier SLA configurable;
13. never silently reprice;
14. never silently drop unmet requirements;
15. never duplicate financial transactions;
16. never claim unconfirmed delivery states;
17. never fake driver movement;
18. never leave infinite loading;
19. never mark a feature complete without failure-path tests.

## 54. Future Costonomy Integration Boundary

Mandi has no initial Costonomy API dependency.

Future adapters may connect:

`Costonomy Inventory → Mandi Requirements`

`Costonomy Consumption Intelligence → Mandi Recommendations`

`Costonomy Restaurant Identity → Mandi Identity Mapping`

`Costonomy Supplier Network → Mandi Supplier Mapping`

These must use explicit adapters/mappers rather than coupling Mandi
domain objects to Costonomy API DTOs.

## 55. Final Product/Engineering Contract

The target experience is:

> **Need → Discover → Decide → Procure → Track → Receive**

The frontend should have the polish, clarity and delivery confidence of
a leading consumer food-delivery application. The backend must provide
the transactional correctness of a serious procurement system.

The product must be beautiful on the happy path and trustworthy on the
failure path.
