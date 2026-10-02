# Changelog

All notable changes to the Costonomy MP (Mandi) Delivery & Logistics Platform across all PRs are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [feat/shiprocket-provider] - Shiprocket Delivery Provider Integration
### Added
- Multi-carrier delivery provider integration for **Shiprocket** alongside Pidge, Borzo, Shadowfax, and Porter.
- Database migration `V44__delivery_provider_shiprocket.sql` seeding `SHIPROCKET` row into `delivery_provider` table (seeded disabled, `enabled = 0`).
- Dual-gate activation architecture:
  - Spring Boot application property gate: `costonomy.mp.shiprocket.enabled` (defaults to `false`).
  - Database registry gate: `delivery_provider.enabled = 1`.
- `ShiprocketDeliveryProvider` adapter implementing `DeliveryProvider` SPI.
- `ShiprocketApiClient` HTTP client for Shiprocket Logistics API:
  - Token authentication via `Authorization: Bearer <token>` (direct API token or cached from `POST /v1/external/auth/login`).
  - Rate limiting with 20 RPS local token-bucket protection.
  - Hard 30 km intra-city radius ceiling enforcement (D-101).
  - Quoting / Serviceability via `GET /v1/external/courier/serviceability/` selecting the cheapest available courier rate from `data.available_courier_companies`.
  - Adhoc order creation via `POST /v1/external/orders/create/adhoc` with validated customer, address, contact, and item payloads, returning `shipment_id` as `providerDeliveryId`.
  - Tracking & Status polling via `GET /v1/external/courier/track/shipment/{shipment_id}` parsing `current_status` and activities.
  - Timeline events synthesis ensuring `PICKED_UP` precedes `DELIVERED` newest-first so `DeliveryOrderBridge` transitions `supplier_order` through `OUT_FOR_DELIVERY` to `DELIVERED`.
  - Cancellation via `POST /v1/external/orders/cancel` with `ids: [shipment_id]`.
- `ShiprocketStatusMapper` mapping Shiprocket status strings to domain `DeliveryStatus`.
- Test suites:
  - `ShiprocketStatusMappingTest` (2 tests).
  - `ShiprocketApiClientContractTest` WireMock tests (9 tests).
  - `ShiprocketDeliveryFlowIT` multi-carrier Testcontainers MySQL 8 integration tests (4 tests).
- Architecture decision recorded in `docs/DECISIONS.md` (D-103) and requirement traceability in `docs/specs/IMPLEMENTATION_TRACEABILITY.md` (DEL-009).

## [feat/porter-provider] - Fail-closed carrier fares (D-102)
### Fixed
- Shadowfax and Porter no longer invent a fare or ETA from a configured rate card. Quotes decline with a reason; booking refuses before any HTTP call and the auction fails over to the next carrier.
- Shadowfax serviceability check fails closed: it checks each pincode's `Regular` service, and an unreachable or malformed answer is a recorded failure, never "serviceable".
- Shadowfax and Porter booking payloads use our own city, state, pincode, weight and goods value, and reject missing contact data instead of sending placeholder names, phone numbers, Bengaluru/Karnataka or Rs 500.
- Porter `customer.name` is "Costonomy" (was "Costonomy Mandi").
### Removed
- `costonomy.mp.shadowfax.base-fee` / `per-km-fee` and `costonomy.mp.porter.base-fee` / `per-km-fee`.
### Changed
- `DeliveryProvider.BookingRequest` gains `pickupLocality`, `dropLocality` and `goodsValue` (shorter constructors kept for existing callers); new `DeliveryProvider.Locality`.
- `DeliveryDirectory` gains `pickupLocality`, `dropLocality` and `goodsValue`; `DeliveryBookingService` passes them to the carrier.
### Tests
- `ShadowfaxApiClientContractTest` (16), `PorterApiClientContractTest` (9), `DeliveryBookingServiceTest` (+3), new `CarrierFareFailClosedIT` (2).

## [feat/porter-provider] - Porter Delivery Provider Integration
### Added
- Multi-carrier delivery provider integration for **Porter** alongside Pidge, Borzo, and Shadowfax.
- Database migration `V43__delivery_provider_porter.sql` seeding `PORTER` row into `delivery_provider` table (seeded disabled, `enabled = 0`).
- Dual-gate activation architecture:
  - Spring Boot application property gate: `costonomy.mp.porter.enabled` (defaults to `false`).
  - Database registry gate: `delivery_provider.enabled = 1`.
- `PorterDeliveryProvider` adapter implementing `DeliveryProvider` SPI.
- `PorterApiClient` HTTP client for Porter Logistics API:
  - Authentication headers: `x-api-key: {apiKey}` and `Authorization: Bearer {apiKey}`.
  - Rate limiting with 20 RPS local token-bucket protection.
  - Quoting via `POST /v1/orders/cost` with vehicle category mapping (superseded by D-102: quoting now declines until a carrier fare is verified) (`TWO_WHEELER` -> `2_wheeler`, `THREE_WHEELER` -> `three_wheeler`, `FOUR_WHEELER_TRUCK` -> `tata_ace`).
  - Order creation via `POST /v1/orders/create` with structured pickup/drop addresses, contacts, coordinates, and idempotency request ID, returning Porter `order_id` as `providerDeliveryId`.
  - Tracking & Status polling via `GET /v1/orders/{order_id}` with partner/driver details parsing (`name`, `mobile`, `vehicle_number`).
  - Timeline events synthesis ensuring `PICKED_UP` precedes `DELIVERED` newest-first so `DeliveryOrderBridge` transitions `supplier_order` through `OUT_FOR_DELIVERY` to `DELIVERED`.
  - Cancellation via `POST /v1/orders/{order_id}/cancel`.
- `PorterStatusMapper` mapping Porter status strings (`created`, `allocating`, `assigned`, `driver_arrived`, `started`, `picked_up`, `in_transit`, `arrived_at_destination`, `delivered`, `cancelled`) to domain `DeliveryStatus`.
- Test suites:
  - `PorterStatusMappingTest` (2 tests).
  - `PorterApiClientContractTest` WireMock tests (8 tests including 30 km boundary rejection).
  - `PorterDeliveryFlowIT` multi-carrier Testcontainers MySQL 8 integration tests (4 tests).
- Architecture decisions recorded in `docs/DECISIONS.md` (D-100) and requirement traceability in `docs/specs/IMPLEMENTATION_TRACEABILITY.md` (DEL-007).
- Intra-City 30 km Radius Limit & Dynamic Tiered Deadlines (D-101):
  - Hard 30 km maximum radius ceiling enforced across `DeliveryFeeQuoteService` (pre-order quoting), `DeliveryQuotingService` (multi-carrier auction gathering), and provider HTTP clients (`PorterApiClient`, `BorzoApiClient`, `ShadowfaxApiClient`).
  - Dynamic assignment deadlines in `DeliveryBookingService`: 3 minutes for two-wheelers (`costonomy.mp.delivery.bike-assignment-timeout=PT3M`), 12 minutes for three-wheelers and mini-trucks (`costonomy.mp.delivery.truck-assignment-timeout=PT12M`).
  - Unit test coverage in `DeliveryBookingServiceTest` (4 tests), `DeliveryQuotingServiceTest` (2 tests), and `DeliveryFeeQuoteServiceTest` (2 tests).

---

## [feat/shadowfax-provider] - Shadowfax Delivery Provider Integration
### Added
- Multi-carrier delivery provider integration for **Shadowfax** alongside Pidge and Borzo.
- Database migration `V42__delivery_provider_shadowfax.sql` seeding `SHADOWFAX` row into `delivery_provider` table (seeded disabled, `is_active = 0`).
- Dual-gate activation architecture:
  - Spring Boot application property gate: `costonomy.mp.shadowfax.enabled` (defaults to `false`).
  - Database registry gate: `delivery_provider.is_active = 1`.
- `ShadowfaxDeliveryProvider` adapter implementing `DeliveryProvider` SPI.
- `ShadowfaxApiClient` HTTP client for Shadowfax Unified API (Forward Integrations):
  - Token authentication via `Authorization: Token <token>`.
  - Serviceability verification via `GET /v1/clients/serviceability/?service=Regular&pincodes={pincode}` with distance-based quoting and ETA calculation.
  - Consignment booking via `POST /v3/clients/orders/` (`order_type: "marketplace"`), returning `awb_number` as `providerDeliveryId`.
  - Status tracking & Polling via `GET /v4/clients/orders/{awb_number}/track/`, parsing current status and `tracking_details` history.
  - Deterministic event synthesis (`sfx_evt_{awb}_{status}_{timestamp}`) ensuring `PICKED_UP` precedes `DELIVERED` newest-first so `DeliveryOrderBridge` transitions `supplier_order` through `OUT_FOR_DELIVERY` to `DELIVERED`.
  - Cancellation via `POST /v3/clients/orders/cancel/` with `request_id: <awb_number>`.
- `ShadowfaxStatusMapper` mapping Shadowfax statuses (`allocating`, `assigned`, `arrived`, `picked_up`, `out_for_delivery`, `delivered`, `cancelled`) to domain `DeliveryStatus`.
- Test suites:
  - `ShadowfaxStatusMappingTest` (2 tests).
  - `ShadowfaxApiClientContractTest` WireMock tests (6 tests).
  - `ShadowfaxDeliveryFlowIT` multi-carrier Testcontainers MySQL 8 integration tests (4 tests).
- Architecture decisions recorded in `docs/DECISIONS.md` (D-099) and requirement traceability in `docs/specs/IMPLEMENTATION_TRACEABILITY.md` (DEL-005).

---

## [feat/borzo-provider-wip] - Borzo Delivery Provider Integration
### Added
- Multi-carrier delivery provider integration for **Borzo** alongside Pidge.
- Database migration `V41__delivery_provider_borzo.sql` seeding `BORZO` row into `delivery_provider` table (seeded disabled, `is_active = 0`).
- Dual-gate activation architecture:
  - Spring Boot application property gate: `costonomy.mp.borzo.enabled` (defaults to `false`).
  - Database registry gate: `delivery_provider.is_active = 1`.
- `BorzoDeliveryProvider` adapter implementing `DeliveryProvider` SPI.
- `BorzoApiClient` HTTP client for Borzo API (`calculate-order`, `create-order`, `orders`, `cancel-order`).
  - Extended `QuoteRequest` with `pickupAddress` and `dropAddress`.
  - Deterministic synthetic polling events (`borzo_evt_{id}_{status}`) ensuring `PICKED_UP` precedes `DELIVERED` newest-first for orderly state advancement through `DeliveryJobs.pollActiveDeliveries()`.
  - Deduplication via `uk_delivery_event_provider`.
- `BorzoStatusMapper` mapping Borzo order statuses to domain `DeliveryStatus`.
- Test suites:
  - `BorzoStatusMappingTest` (4 tests).
  - `BorzoApiClientContractTest` (12 tests).
  - `BorzoPidgeCoexistenceTest` (3 tests).
  - `BorzoDeliveryFlowIT` Testcontainers MySQL 8 integration tests (4 tests).
- Architecture decisions recorded in `docs/DECISIONS.md` (D-098) and requirement traceability in `docs/specs/IMPLEMENTATION_TRACEABILITY.md` (DEL-003).

---

## [feat/pidge-16-outlet-active-deliveries-and-arrival-radar] - PR 16
### Added
- Outlet Delivery Radar endpoint: `GET /api/v1/outlets/{outletId}/deliveries/radar`.
  - Answers *“Which one is approaching the kitchen?”* by ranking active deliveries by arrival urgency (`AT_KITCHEN_DOOR` first, then `APPROACHING` by shortest ETA).
  - Answers *“Is this supplier still on schedule?”* with real-time `ScheduleStatus` (`ON_SCHEDULE`, `RUNNING_LATE`, `CRITICALLY_DELAYED`) and exact `minutesOverdue`.
  - Answers *“Did the driver call? Is there a problem?”* via `DriverInfo` contacts, `ProblemDetails` (`hasProblem`, `problemType`, carrier exceptions, stale GPS detection).
  - Answers *“Which orders should I check in, receive, or escalate?”* with an actionable `KitchenAction` classifier (`CHECK_IN`, `MEET_DRIVER`, `PREPARE_DOCK`, `CALL_DRIVER`, `ESCALATE`, `MONITOR`).
  - Aggregates `RadarSummaryResponse` counts (`totalActive`, `atDoorCount`, `approachingCount`, `delayedCount`, `pendingCheckInCount`, `requiresEscalationCount`).
- Outlet Delivery Search & Pagination endpoint: `GET /api/v1/outlets/{outletId}/deliveries?page=0&size=10&status=...`.
  - Scoped to outlet with `Permissions.ORDER_VIEW` access control and tenant isolation.
- `OutletDeliveryRadarService` situational engine and comprehensive unit test suite `OutletDeliveryRadarServiceTest`.

---

## [feat/pidge-15-admin-delivery-search-and-late-tracking] - PR 15
### Added
- Operations Delivery Search & Listing endpoint: `GET /api/v1/admin/deliveries?page=0&size=10&status=...&providerCode=...&supplierStoreId=...`.
  - Lists last 10 deliveries by default (newest first) with full pagination metadata (`PagedResponse`).
  - Joins order number (`supplier_order`), outlet name (`outlet`), supplier store name (`supplier_store`), supplier org name (`supplier_organization`), and delivery provider name (`delivery_provider`).
- Dedicated Missed ETA Tracking endpoint: `GET /api/v1/admin/deliveries/late?liveOnly=true&page=0&size=10`.
  - Computes `minutesOverdue` live using `estimated_arrival_at` for active deliveries or historical SLA overruns for completed deliveries.
  - Sorts automatically by urgency (highest `minutesOverdue` first).
- `AdminDeliverySummaryResponse` and `PagedResponse` DTO records.
- Unit test coverage in `AdminDeliveryServiceTest`.

---

## [feat/pidge-14-webhook-signature-verification] - PR 14
### Added
- Environment-injected webhook secret binding: `costonomy.mp.delivery.webhook-secret=${DELIVERY_WEBHOOK_SECRET:${PIDGE_WEBHOOK_SECRET:}}`.
- Unit test suite (`PidgeWebhookServiceTest`) testing HMAC-SHA256 verification, forged signatures, payload tampering, and local-dev pass-through.
### Security
- Constant-time HMAC comparison using `MessageDigest.isEqual` to prevent side-channel timing attacks.
- Outbox audit logging (`PIDGE_WEBHOOK_REJECTED`) when signature fails.

---

## [feat/pidge-13-bulk-delivery-export] - PR 13
### Added
- Admin streaming bulk delivery export endpoint: `GET /api/v1/admin/deliveries/export?format=csv|json&since=YYYY-MM-DD&status=...`.
- `DeliveryExportRow` DTO containing flattened delivery journey, vehicle categorization, fees, timestamps, and failure codes.
- `AdminDeliveryExportService` streaming service respecting `costonomy.mp.admin.export.max-rows=10000` memory guard.
- RFC 4180 CSV escaping and unit test suite (`AdminDeliveryExportServiceTest`).

---

## [feat/pidge-12-provider-metrics] - PR 12
### Added
- Database migration `V27__delivery_provider_metrics.sql` for 2-hour rolling performance snapshots.
- `DeliveryProviderMetrics` JPA entity tracking average latency (`avg_latency_ms`), 95th percentile latency (`p95_latency_ms`), total cost (`total_cost_inr`), and order count.
- `DeliveryMetricsAggregationService` computing latencies from `DRIVER_ASSIGNED` to `DELIVERED` and ledger sums.
- `DeliveryMetricsJob` running every 2 hours with ShedLock protection (`0 0 0/2 * * *`).
- Admin endpoints: `GET /api/v1/admin/deliveries/providers/metrics` and `GET /api/v1/admin/deliveries/providers/{code}/metrics`.
- `DeliveryMetricsAggregationServiceTest` unit tests verifying P95 percentile calculation.

---

## [feat/pidge-11-delivery-rate-limiting] - PR 11
### Added
- Per-endpoint rate limits for delivery API:
  - `delivery-request`: `POST /api/v1/supplier-orders/**` (10/min per user)
  - `delivery-reassign`: `POST /api/v1/deliveries/**` (5/min per user, covers cancels + reassignments)
  - `delivery-read`: `GET /api/v1/deliveries/**` (60/min per user for live tracking polls)
- Environment-configurable variables in `application.properties`:
  - `costonomy.mp.ratelimit.delivery-request=10`
  - `costonomy.mp.ratelimit.delivery-reassign=5`
  - `costonomy.mp.ratelimit.delivery-cancel=5`
  - `costonomy.mp.ratelimit.delivery-read=60`
- `DeliveryRateLimitPoliciesTest` unit suite verifying path routing and zero-limit test bypass.

---

## [feat/pidge-10-provider-stats] - PR 10
### Added
- Database migration `V26__delivery_provider_stats.sql` for nightly provider reliability aggregation.
- `DeliveryProviderStats` JPA entity with derived rates: `cancellationRate()`, `etaBreachRate()`, `overallFailureRate()`.
- `DeliveryStatsAggregationService` combining `delivery`, `delivery_event`, `delivery_quote`, and `delivery_ledger`.
- `DeliveryStatsJob` scheduled at 02:00 UTC daily under ShedLock.
- Admin endpoints: `GET /providers/stats`, `GET /providers/{code}/stats`, and `POST /providers/stats/aggregate`.
- Unit tests (`DeliveryProviderStatsTest`) for derived reliability percentages.

---

## [feat/pidge-09-docs-and-specs] - PR 09
### Added
- Architecture documentation updates in `docs/specs/06-delivery.md` detailing Pidge Smart Dispatch, waterfall cascade sequence, weight thresholds, and ledger schema.
- Event-driven waterfall timer explanation and zero-polling architecture guidelines.

---

## [feat/pidge-08-integration-tests] - PR 08
### Added
- `PidgeDeliveryFlowIT` Testcontainers integration test suite using MySQL 8.
- End-to-end testing of webhook ingestion, rider details parsing, live tracking URL extraction, signature verification, and admin financial ledger inspection.

---

## [feat/pidge-07-ops-reassign] - PR 07
### Added
- Operations delivery inspection and manual waterfall escalation endpoints:
  - `GET /api/v1/admin/deliveries/{id}/ledger`
  - `POST /api/v1/admin/deliveries/{id}/force-waterfall`
- `AdminDeliveryController` and `AdminDeliveryService` with `DELIVERY_INSPECT` and `DELIVERY_OPERATE` permission enforcement.

---

## [feat/pidge-06-tracking-notifications] - PR 06
### Added
- Migration `V25__delivery_tracking_url.sql` adding `tracking_url` column to `delivery` table.
- Notification outbox integration: includes live tracking URL in customer and restaurant SMS/push notifications.

---

## [feat/pidge-05-dispatch-triggers] - PR 05
### Added
- `DeliveryDispatchListener` listening to domain events:
  - `ORDER_ACCEPTED` triggers provider quoting.
  - `PREPARING` locks vehicle classification.
  - `READY_FOR_PICKUP` triggers auto-dispatch booking if enabled (`costonomy.mp.delivery.auto-dispatch.enabled=true`).

---

## [feat/pidge-04-ledger-and-waterfall] - PR 04
### Added
- Migration `V24__delivery_vehicle_and_weight.sql` adding `delivery_ledger` table.
- `DeliveryWaterfallService`: event-driven timeout handling without polling loops; cascades unassigned bookings to next best carrier quote.
- Central ledger audit entries (`DeliveryLedgerEntry`) recording `QUOTED`, `BOOKED`, `ADJUSTED`, and `REFUNDED` entries.

---

## [feat/pidge-03-webhooks-and-security] - PR 03
### Added
- `/api/v1/webhooks/delivery/pidge` public webhook ingest endpoint.
- `PidgeWebhookService`: HMAC-SHA256 signature verification, deduplication on provider event ID, and safe state machine transitions.

---

## [feat/pidge-02-provider-adapter] - PR 02
### Added
- `PidgeDeliveryProvider` adapter implementing domain `DeliveryProvider` port.
- Resilient `PidgeApiClient` with token bucket rate limiting (20 RPS) and circuit breaker guardrails.
- `PidgeProperties` with timeout and API credentials.

---

## [feat/pidge-01-schema-and-vehicle-sizing] - PR 01
### Added
- `VehicleType` enum: `TWO_WHEELER` (<= 20 kg), `THREE_WHEELER` (20-100 kg), `FOUR_WHEELER_TRUCK` (> 100 kg).
- Dynamic payload calculation aggregating order line items: $\sum (\text{item.quantity} \times \text{sku.effectiveWeightKg})$.
- Added `weight_kg`, `volume_cbm`, and `vehicle_type` to `delivery` and `delivery_quote` schemas.
