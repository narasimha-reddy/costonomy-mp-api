# Costonomy Marketplace: System Architecture & Endpoint Sequence Diagrams

This directory provides the definitive architectural sequence diagrams and runtime execution flows for every endpoint across all modules of the **Costonomy MP (Mandi)** platform.

All sequence diagrams are defined in standard [Mermaid.js](https://mermaid.js.org/) syntax and render automatically in GitHub and Markdown viewers.

---

## Architecture Modules & Sequence Diagram Index

| Module Index | Scope & Domain | Endpoints Documented | Link |
| :--- | :--- | :--- | :--- |
| **01. Payments & Wallet** | Order payment intents, Razorpay checkout confirmation, async webhooks, wallet balance, free test top-up, source-reversed withdrawals, and direct IMPS/NEFT bank payouts. | `GET /api/v1/supplier-orders/{orderId}/payment-intent`<br>`POST /api/v1/payments/{id}/confirm`<br>`GET /api/v1/payments/{id}`<br>`GET /api/v1/payments/{id}/refunds`<br>`POST /api/v1/webhooks/razorpay`<br>`GET /api/v1/outlets/{outletId}/wallet`<br>`POST /api/v1/outlets/{outletId}/wallet/top-up`<br>`POST /api/v1/outlets/{outletId}/wallet/withdraw`<br>`POST /api/v1/outlets/{outletId}/wallet/bank-payout` | [01-payment-and-wallet.md](01-payment-and-wallet.md) |
| **02. Delivery & Logistics** | 3PL carrier dispatch (Pidge, Borzo), multi-carrier waterfall escalation, carrier webhooks, kitchen arrival radar, and late-ETA tracking. | `POST /api/v1/supplier-orders/{orderId}/delivery`<br>`GET /api/v1/deliveries/{id}`<br>`GET /api/v1/deliveries/{id}/events`<br>`POST /api/v1/deliveries/{id}/dispatched`<br>`POST /api/v1/deliveries/{id}/delivered`<br>`POST /api/v1/webhooks/delivery/pidge`<br>`GET /api/v1/outlets/{outletId}/deliveries/radar`<br>`GET /api/v1/admin/deliveries/late`<br>`POST /api/v1/admin/deliveries/{id}/force-waterfall` | [02-delivery-and-logistics.md](02-delivery-and-logistics.md) |
| **03. Intent & Procurement** | Demand drafts, RFQ broadcasting, supplier quoting, order creation, order preparation/readiness, supplier cancellations, catch-weight re-weighing, and automated subscription fulfillment. | `POST /api/v1/outlets/{outletId}/intent-items`<br>`POST /api/v1/intents/{id}/send`<br>`POST /api/v1/intents/{id}/respond`<br>`POST /api/v1/intents/{id}/orders`<br>`POST /api/v1/supplier-orders/{id}/preparing`<br>`POST /api/v1/supplier-orders/{id}/ready`<br>`POST /api/v1/supplier-orders/{id}/supplier-cancel`<br>`POST /api/v1/supplier-orders/{id}/weights`<br>`POST /api/v1/supplier-stores/{storeId}/subscriptions/generate-orders` | [03-intent-and-procurement.md](03-intent-and-procurement.md) |
| **04. Trust, Disputes & Receiving** | Receiving check-in inspections, doorstep line rejections with instant wallet refund escrow, dispute threads, mutual resolutions, dispute refund requests, and supplier ratings. | `POST /api/v1/supplier-orders/{orderId}/receive`<br>`POST /api/v1/supplier-orders/{orderId}/disputes`<br>`POST /api/v1/disputes/{id}/messages`<br>`POST /api/v1/disputes/{id}/refund-request`<br>`POST /api/v1/dispute-refunds/{id}/approve`<br>`POST /api/v1/supplier-orders/{orderId}/rating` | [04-trust-and-disputes.md](04-trust-and-disputes.md) |
| **05. Credit & Settlement** | 2-phase credit reservation & utilization, repayments, supplier billing cycles, commission deductions, dispute withholdings, payout approvals, and statutory GST B2B tax invoicing. | `POST /api/v1/restaurants/{id}/credit/reserve`<br>`POST /api/v1/restaurants/{id}/credit/utilize`<br>`POST /api/v1/restaurants/{id}/credit/repay`<br>`GET /api/v1/supplier-stores/{storeId}/settlements`<br>`GET /api/v1/settlements/{id}`<br>`POST /api/v1/admin/settlements/{id}/approve`<br>`POST /api/v1/admin/settlements/{id}/paid`<br>`GET /api/v1/supplier-orders/{id}/tax-invoice` | [05-credit-and-settlement.md](05-credit-and-settlement.md) |
| **06. Identity & Organizations** | Passwordless mobile OTP authentication, JWT claims, outlet provisioning, and supplier KYB verification workflow. | `POST /api/v1/auth/otp/request`<br>`POST /api/v1/auth/otp/verify`<br>`POST /api/v1/restaurants/{id}/outlets`<br>`POST /api/v1/suppliers/{id}/verification`<br>`POST /api/v1/admin/suppliers/verifications/{id}/review` | [06-identity-and-orgs.md](06-identity-and-orgs.md) |
| **07. Discovery & Catalog** | Geospatial radius search, SKU discovery, supplier digital storefronts, and catalog inventory batch updates. | `GET /api/v1/search/skus`<br>`GET /api/v1/search/suppliers`<br>`GET /api/v1/supplier-stores/{storeId}/storefront`<br>`POST /api/v1/supplier-stores/{storeId}/skus`<br>`POST /api/v1/supplier-stores/{storeId}/skus/bulk` | [07-discovery-and-catalog.md](07-discovery-and-catalog.md) |
| **08. Realtime, Chat & Admin** | SSE event stream connections, authenticated one-time tickets, buyer-seller messaging, notifications, file uploads, and admin refund overrides. | `POST /api/v1/realtime/ticket`<br>`GET /api/v1/realtime/events`<br>`POST /api/v1/chats/{id}/messages`<br>`GET /api/v1/notifications`<br>`POST /api/v1/upload`<br>`GET /api/v1/admin/refunds/failures`<br>`POST /api/v1/admin/refunds/{id}/manual-override` | [08-communication-and-admin.md](08-communication-and-admin.md) |

---

## Global Cross-Cutting Standards

### 1. Client Idempotency
All mutating `POST` and `PUT` endpoints expect an `Idempotency-Key: <UUID>` header. Repeated submissions within a 24-hour window replay the exact cached response without re-executing side-effects.

### 2. Traceability & Correlation
Every request assigns or propagates an `X-Correlation-Id` header through Spring MDC and `TraceScope`, correlating HTTP controller logs with downstream asynchronous queue tasks and external webhook handlers.

### 3. Tenant & Permission Isolation
All queries strictly enforce tenant boundaries (e.g. `outlet_id` or `supplier_store_id`) and check authority roles through `@PreAuthorize("hasAuthority('...')")`.
