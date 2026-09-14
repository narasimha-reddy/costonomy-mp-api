# API Specification — Costonomy MP

## 1. Base

Base path:

`/api/v1`

Admin/operations:

`/api/v1/admin`

All JSON unless explicitly stated.

## 2. Response envelope

Success:

```json
{
  "data": {},
  "error": null,
  "meta": {}
}
```

Failure:

```json
{
  "data": null,
  "error": {
    "code": "SUPPLIER_ORDER_EXPIRED",
    "message": "This order can no longer be accepted.",
    "details": {}
  },
  "meta": {
    "requestId": "..."
  }
}
```

Never return stack traces or provider secrets.

## 3. Authentication

JWT bearer authentication.

Endpoints:

- `POST /api/v1/auth/otp/request`
- `POST /api/v1/auth/otp/verify`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

Device:

- `POST /api/v1/devices`
- `DELETE /api/v1/devices/{id}`

OTP request:

```json
{
  "phone": "+919999999999",
  "purpose": "LOGIN"
}
```

OTP verification:

```json
{
  "phone": "+919999999999",
  "otp": "123456",
  "purpose": "LOGIN"
}
```

Response contains access token, refresh token/session metadata and user role context.

OTP rules:

- expiry
- attempt limit
- resend cooldown
- throttling
- mock provider in local/test environments

## 4. Taxonomy/catalog

- `GET /api/v1/categories`
- `GET /api/v1/brands`
- `GET /api/v1/products`
- `GET /api/v1/products/{id}`
- `GET /api/v1/products/{id}/offers`

Product list supports cursor pagination and filters.

## 5. Restaurant/outlet

- `POST /api/v1/restaurants`
- `GET /api/v1/restaurants/{id}`
- `PATCH /api/v1/restaurants/{id}`
- `POST /api/v1/restaurants/{id}/outlets`
- `GET /api/v1/outlets/{id}`
- `PATCH /api/v1/outlets/{id}`
- `GET /api/v1/outlets/{id}/users`
- `POST /api/v1/outlets/{id}/users`

Create outlet:

```json
{
  "name": "Banjara Hills Outlet",
  "addressLine1": "Road No 12",
  "city": "Hyderabad",
  "state": "Telangana",
  "pincode": "500034",
  "latitude": 17.4156,
  "longitude": 78.4347
}
```

## 6. Supplier

- `POST /api/v1/suppliers`
- `GET /api/v1/suppliers/{id}`
- `PATCH /api/v1/suppliers/{id}`
- `POST /api/v1/suppliers/{id}/stores`
- `GET /api/v1/supplier-stores/{id}`
- `PATCH /api/v1/supplier-stores/{id}`
- `POST /api/v1/suppliers/{id}/verification`
- `GET /api/v1/suppliers/{id}/verification`

Verification submission:

```json
{
  "verificationType": "GST",
  "gstin": "36ABCDE1234F1Z5",
  "legalName": "Example Foods Pvt Ltd"
}
```

## 7. Supplier catalog

- `GET /api/v1/supplier-stores/{id}/skus`
- `POST /api/v1/supplier-stores/{id}/skus`
- `PATCH /api/v1/supplier-skus/{id}`
- `POST /api/v1/supplier-stores/{id}/catalog/import`
- `GET /api/v1/catalog/imports/{id}`

SKU:

```json
{
  "canonicalProductId": 123,
  "skuCode": "RICE-25KG-001",
  "brandName": "Example",
  "packValue": 25,
  "packUnit": "KG",
  "price": 1450.00,
  "gstRate": 5.0,
  "availability": "AVAILABLE"
}
```

Import flow:

`UPLOAD → PARSE → MAP → VALIDATE → PREVIEW → CONFIRM → IMPORT → SUMMARY`

No silent row corruption.

## 8. Search

- `GET /api/v1/search/products?q=`
- `GET /api/v1/search/suppliers?q=`
- `GET /api/v1/search/suggestions?q=`

Search response must distinguish canonical products from supplier offers.

## 9. Requirements

- `POST /api/v1/outlets/{id}/requirements`
- `GET /api/v1/outlets/{id}/requirements`
- `GET /api/v1/requirements/{id}`
- `PATCH /api/v1/requirements/{id}`
- `POST /api/v1/requirements/{id}/find-suppliers`
- `POST /api/v1/requirements/{id}/procure`

Create:

```json
{
  "source": "MANUAL",
  "items": [
    {
      "canonicalProductId": 123,
      "quantity": 20,
      "unit": "KG"
    }
  ]
}
```

Finding suppliers must return:

- candidate supplier
- offer
- quantity available
- unit price
- GST
- estimated delivery
- recommendation score/explanation
- confidence/quality indicators where available

## 10. Procurement

- `POST /api/v1/procurements`
- `GET /api/v1/procurements/{id}`
- `POST /api/v1/procurements/{id}/validate`
- `POST /api/v1/procurements/{id}/submit`
- `POST /api/v1/procurements/{id}/cancel`
- `POST /api/v1/procurements/{id}/approve`
- `POST /api/v1/procurements/{id}/reject`

Creation must support multiple supplier splits.

Submit must:

1. revalidate
2. calculate authoritative commercial values
3. evaluate approval policy
4. authorize payment/credit
5. create supplier orders transactionally
6. persist events
7. return resulting state

## 11. Supplier orders

- `GET /api/v1/supplier-orders`
- `GET /api/v1/supplier-orders/{id}`
- `POST /api/v1/supplier-orders/{id}/accept`
- `POST /api/v1/supplier-orders/{id}/partial-accept`
- `POST /api/v1/supplier-orders/{id}/reject`
- `POST /api/v1/supplier-orders/{id}/preparing`
- `POST /api/v1/supplier-orders/{id}/ready`
- `POST /api/v1/supplier-orders/{id}/cancel`

Partial accept:

```json
{
  "items": [
    {
      "supplierOrderItemId": 1001,
      "acceptedQuantity": 8
    },
    {
      "supplierOrderItemId": 1002,
      "acceptedQuantity": 0
    }
  ],
  "reason": "STOCK_LIMIT"
}
```

Rules:

- quantities cannot exceed requested
- zero accepted quantity must be explicit
- resulting order total is recalculated
- accepted payment/credit amount only
- unmet requirement is preserved

## 12. Payments

- `POST /api/v1/payments`
- `GET /api/v1/payments/{id}`
- `POST /api/v1/payments/{id}/confirm`
- `POST /api/v1/payments/{id}/refund`
- `POST /api/v1/webhooks/razorpay`

Payment creation must not capture money before business state permits it.

Webhook endpoint must:

- verify signature
- persist raw event safely
- deduplicate provider event ID
- process out of order
- reconcile

## 13. Credit

- `POST /api/v1/credit/requests`
- `GET /api/v1/credit/agreements`
- `GET /api/v1/credit/agreements/{id}`
- `POST /api/v1/credit/agreements/{id}/approve`
- `POST /api/v1/credit/agreements/{id}/reject`
- `POST /api/v1/credit/agreements/{id}/modify`
- `GET /api/v1/credit/agreements/{id}/ledger`

Request:

```json
{
  "supplierStoreId": 55,
  "outletId": 10,
  "requestedLimit": 200000,
  "requestedDays": 30,
  "purpose": "PROCUREMENT"
}
```

Supplier modification must be explicit and versioned.

Credit summary must expose:

- approved limit
- reserved
- utilized
- due
- overdue
- available

## 14. Delivery

- `POST /api/v1/supplier-orders/{id}/delivery/quote`
- `POST /api/v1/deliveries/{id}/book`
- `GET /api/v1/deliveries/{id}`
- `GET /api/v1/deliveries/{id}/events`
- `POST /api/v1/deliveries/{id}/cancel`
- `POST /api/v1/deliveries/{id}/reassign`

Restaurant response must not expose provider bidding details.

Before assignment:

`Finding the best delivery partner…`

After assignment:

- driver details where supported
- route
- concrete ETA
- status timeline
- stale GPS indicator if location is old
- support/call actions where supported

## 15. Receiving

- `POST /api/v1/supplier-orders/{id}/receive`
- `GET /api/v1/supplier-orders/{id}/receiving`

Request:

```json
{
  "items": [
    {
      "supplierOrderItemId": 1001,
      "receivedQuantity": 8,
      "damagedQuantity": 1,
      "missingQuantity": 1
    }
  ],
  "notes": "One damaged bag"
}
```

## 16. Disputes

- `POST /api/v1/supplier-orders/{id}/disputes`
- `GET /api/v1/disputes/{id}`
- `POST /api/v1/disputes/{id}/response`

Categories:

`WRONG_PRODUCT, SHORT_QUANTITY, DAMAGED, EXPIRED, QUALITY, INCORRECT_INVOICE, OTHER`

## 17. Ratings

- `POST /api/v1/supplier-orders/{id}/rating`
- `GET /api/v1/supplier-stores/{id}/ratings`

## 18. Notifications

- `GET /api/v1/notifications`
- `POST /api/v1/notifications/{id}/read`
- `POST /api/v1/notifications/read-all`
- `GET /api/v1/notification-preferences`
- `PATCH /api/v1/notification-preferences`

## 19. Admin

Representative:

- `GET /api/v1/admin/suppliers`
- `POST /api/v1/admin/suppliers/{id}/verify`
- `POST /api/v1/admin/suppliers/{id}/suspend`
- `POST /api/v1/admin/catalog/products`
- `PATCH /api/v1/admin/catalog/products/{id}`
- `GET /api/v1/admin/orders`
- `GET /api/v1/admin/disputes`
- `POST /api/v1/admin/disputes/{id}/resolve`
- `GET /api/v1/admin/deliveries`
- `GET /api/v1/admin/settlements`
- `POST /api/v1/admin/settlements/{id}/approve`
- `GET /api/v1/admin/audit`
- `GET /api/v1/admin/config`
- `PATCH /api/v1/admin/config`

## 20. Pagination

Cursor-based pagination for feeds and large collections.

Response:

```json
{
  "data": [],
  "error": null,
  "meta": {
    "nextCursor": "...",
    "hasMore": true
  }
}
```

## 21. Idempotency

Header:

`Idempotency-Key: <client-generated key>`

Mandatory for:

- procurement creation/submission
- supplier state transitions
- payments
- refunds
- credit reservation/utilization
- delivery booking/reassignment
- receiving
- disputes

Same actor + operation + key + same payload → original response.

Same key with different payload → `IDEMPOTENCY_KEY_REUSE`.

## 22. Standard errors

Minimum codes:

- `VALIDATION_ERROR`
- `FORBIDDEN`
- `RESOURCE_NOT_FOUND`
- `INVALID_STATE_TRANSITION`
- `SUPPLIER_ORDER_EXPIRED`
- `SUPPLIER_ORDER_ALREADY_ACCEPTED`
- `PRICE_CHANGED`
- `SKU_UNAVAILABLE`
- `SUPPLIER_OFFLINE`
- `CREDIT_LIMIT_EXCEEDED`
- `CREDIT_SUSPENDED`
- `PAYMENT_FAILED`
- `PAYMENT_STATE_CONFLICT`
- `REFUND_ALREADY_REQUESTED`
- `DELIVERY_UNAVAILABLE`
- `DELIVERY_REASSIGNMENT_FAILED`
- `IDEMPOTENCY_KEY_REUSE`
- `CONCURRENT_MODIFICATION`

HTTP guidance:

- 400 malformed request
- 401 unauthenticated
- 403 unauthorized
- 404 missing
- 409 state/concurrency/idempotency conflict
- 422 business validation
- 429 throttled
- 500 unexpected server error

## 23. API implementation contract

For every endpoint define in code:

- request DTO
- response DTO
- validation
- authorization
- service method
- transaction boundary
- idempotency behavior
- state transition
- audit event
- domain event
- error mapping
- OpenAPI documentation
- integration test
