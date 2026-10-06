# Database bottlenecks and caching candidates

Audit date: 2026-10-06, from reading the code at API `4ca8d2b` (two read-only reviews, read paths and write paths/jobs).
Nothing here was measured on a loaded system. File references are to `src/main/java/com/costonomy/mp/` (written `J/`) and
`src/main/resources/db/migration/` (`M/`). Line numbers may drift. Use the `db-bottleneck-review` skill to re-check.

## Setup facts that drive most findings
- Hikari pool is **10** connections (`DB_POOL_MAX`), no max-lifetime or leak detection. The scheduler runs **8** threads
  and Tomcat up to 200 request threads, all on those 10 connections. `open-in-view=false`.
- **No Spring cache at all** (no `@Cacheable`, no Caffeine). Only `AppConfigService` (a per-JVM snapshot) and
  `RolePermissionCatalog` cache anything. Redis is a dependency but used only for realtime broadcast.
- 22 `@Scheduled` jobs, all behind ShedLock. `hibernate.jdbc.batch_size=50` is set but every entity uses
  AUTO_INCREMENT keys, so Hibernate cannot batch inserts (one INSERT per row).
- No `hibernate.default_batch_fetch_size`, so a stray lazy load is an N+1.

## Fix first (high)
| # | Where | Problem | Fix |
|---|---|---|---|
| 1 | Pool, many `REQUIRES_NEW` stores (`OrderNumberGenerator`, `InvoiceNumberGenerator`, `IdempotencyStore`, notification/realtime stores, `PaymentWebhookStore`) | An outer transaction holds one connection and waits for a second. With 10 connections and enough concurrent order creations or jobs, every connection is held waiting for another: starvation until the 30 s timeout. | Raise the pool (40-50), add `max-lifetime` and `leak-detection-threshold`, cap job concurrency, take number-generator rows before the outer transaction or inside it. |
| 2 | `AccessControlService:60-76,161`, `ScopeResolver:43-52` | Every permission check runs 3 queries, one of which loads **every role** (`roleRepository.findAll()`). Most endpoints check twice. | Cache `roleCodesById` for the process (roles are seeded), cache outlet/store parent in Caffeine (5-10 min). **Never cache user grants.** |
| 3 | `DeliveryJobs:40-95` (30 s) | One `@Transactional` loads all active deliveries and makes 1-2 carrier HTTP calls each inside it. 500 deliveries is about 5 min, longer than the lock; a second instance then runs the same sweep. | No class-level transaction, page by `last_provider_update_at`, short per-delivery transactions, bounded executor. |
| 4 | `DeliveryEventService:54-100`, `:101-127` | Each poll replays the provider's full event history; every old event is a failing INSERT (own transaction, duplicate key). Each location update also writes an `outbox_event` and fan-out rows. | Skip events already stored; send live location straight to realtime without persistence; sample `delivery_location`. |
| 5 | `OutboxPublisher:38-58` (2 s, batch 100) + relays | Drain is one transaction; each relay write is its own `REQUIRES_NEW` commit, serial. Lock can expire mid-drain. `ix_outbox_dispatch` does not serve the claim query; published rows are never purged. | Claim rows (`SKIP LOCKED`), commit, dispatch outside the transaction, batch fan-out inserts, add `(status, id)` index and a purge. |
| 6 | `SupplierOrderMapper:45-100` via `SupplierOrderService` inbox/history | About 8-10 queries **per order**, in a stream. History is unbounded. | Add a batch `toResponses(List)`; page `historyForStore`. |
| 7 | `StorefrontService` `searchSkus`, `searchSuppliers`, `STOCKS_MATCHING` | `lower(col) like '%term%'` across offer, sku, product, brand: no index can serve it. `searchSuppliers` runs correlated `count(*)` per store and pages **in Java** after loading every store. `ORDER BY ... LIMIT` runs before the serviceability filter. | Search `normalized_name`/aliases first, then join offers by product id; cache product counts per store; apply serviceability before the limit; FULLTEXT (ngram) later. |
| 8 | `OrderDerivedPerformanceProvider:44-205` | Five all-time aggregates (orders, items, deliveries, ratings) recomputed on every search, carousel, detail view. Cost grows with total history. | Cache per store (5 min); better, a `supplier_store_metrics` table refreshed by a job. |
| 9 | `PopularSupplierService:93-229` | Full grouped query over every active store for a small carousel, then sorting and filtering in Java. | Cache per outlet geo-cell + category (60-120 s); geo bounding box in SQL. |
| 10 | `SupplierOfferRepository.findBySupplierSkuIdAndStatus`, `M/V6:163` | The hottest lookup has no `(supplier_sku_id, status)` index and offers are one row per SKU per price change, never purged. Nothing enforces one ACTIVE offer per SKU. | Add the index; enforce uniqueness with a generated key (as V42/V69 do) or a SKU row lock; archive old SUPERSEDED rows. |
| 11 | `NotificationDispatcher:64-120` | Permanently failed rows keep matching `findDue` and are re-sent every 10 s; the whole dispatch is one transaction around outbound calls. | Terminal DEAD status or capped attempts; no transaction around sends. |
| 12 | `SettlementService:73-110` (hourly) | One transaction for all stores; re-runs the full-window query per store; no `(status, updated_at)` index on `supplier_order`. | Add the index, query once and group, one transaction per store, a watermark. |
| 13 | `IdempotencyStore:126` | `deleteExpired` exists and **nothing calls it**. A row per mutating request, forever. | Scheduled batched purge (`DELETE ... LIMIT 5000`) and a reaper for stale IN_PROGRESS. |
| 14 | `CatalogImportService:240-330` | One transaction for a whole import file, row-by-row inserts. | Chunks of 100-500 rows, progress in `catalog_import`, JDBC batch. |
| 15 | `OperationsDashboardService:57-190` | About 30 `count(*)`/`sum` over the largest tables with no global `created_at` indexes, on every refresh. | Indexes on `supplier_order(created_at)`, `payment(created_at/captured_at)`, `dispute(created_at/resolved_at)`; cache 30-60 s. |

## Next (medium)
- `SearchService.suggest:62` loads **all aliases** (`findAll()`) per keystroke. Use a prefix query on `ix_alias_normalized` or cache in memory.
- `CatalogQueryService:244-258` runs alias and category lookups per product (up to 200 extra queries per page); `listProducts` pays a `count(*)` (`Page`) per request and `findByStatus` has no status-leading index.
- `SkuDetailService` loads all reviews unbounded, queries an outlet name per review, no `created_at` in `ix_sku_review_sku`.
- Three near-duplicate store-info directories (`DiscoveryDirectory`, `CatalogDirectory`, `ProcurementDirectory`) re-read and re-parse JSON (hours, pincodes) in nearly every call. One shared cache of the immutable parts, `openNow` computed at read time.
- `IntentService.list` loads an outlet's whole history; `IntentResponder.forStore` applies its `limit` after loading everything.
- `WalletHistoryService` can run about 480 small queries per page (a loop of up to 240 months, each with `hasAnything`), and month totals are recomputed every page. One `group by`; cache closed months.
- `ChatService.forOutlet/forStore` is N+1 per thread on every poll (pair lookup with two `EXISTS`, read state, unread count).
- Refund jobs: `lateSuccessOpen`, `reversedToAudit`, `countFailuresSince` have no matching index; `processRefunds` loads every REQUESTED/FAILED refund each 30 s, including exhausted ones.
- `PaymentJobs.findStale` orders by `coalesce(reconciled_at, created_at)` (filesort); held payments can starve new ones. A `next_check_at` column.
- `IntentExpiryJob` loads whole backlogs as entities; select ids with a limit.
- `RealtimeJobs` derived `deleteBy...` loads then deletes row by row. Use `@Modifying` delete with a limit.
- `SubscriptionJobs` (6 passes each evening) locks every subscription to find nothing to do; pre-filter in SQL.
- `audit_log` has 4 secondary indexes on an append-only table, and `searchAudit` uses `(? is null or col = ?)` which defeats them.
- `SettlementReconciliationService` re-locks and rewrites every APPROVED settlement hourly with a giant `IN` list.

## Lower
Rate-sheet and `ProcurementService.validate` per-row lookups; `QuickScanPaymentRepository.findSettleable` OR predicate; abandoned wallet top-ups re-polled; `GET /wallet` writes a row on first read; `otp_verification` and `refresh_token` never purged; `withdrawableByPayment` aggregates the whole refund history while holding the wallet lock.

## Tables with no purge or retention
`idempotency_record` (purge exists, never called), `outbox_event`, `audit_log` (append-only by policy), `notification`,
`notification_delivery`, `analytics_event`, `payment_webhook_event` (raw JSON), `delivery_event`, `delivery_location`,
`delivery_ledger`, `delivery_quote`, `delivery_provider_attempt`, `supplier_offer` (superseded history),
`refresh_token`, `otp_verification`, `chat_message`. Financial and order tables are kept by design but several jobs scan
them (see above). Only `realtime_event` (7 days) and `realtime_ticket` are purged.

## Caching plan
Add `spring-boot-starter-cache` and Caffeine for per-process reference data, Redis (already a dependency) only for what
must be shared or invalidated across instances. Set `hibernate.default_batch_fetch_size` (about 50) as a guard.

| Safe to cache | Where | TTL | Evict on |
|---|---|---|---|
| `app_config` | `AppConfigService` (already a snapshot) | add a 60 s reload or Redis pub/sub | `AdminConfigService:130` |
| Role code map, role-permission map | `AccessControlService:161` | process / 10 min | role edits |
| Outlet and store parent ids | `ScopeResolver` | 10 min | reparenting (rare) |
| Categories, brands | `CatalogQueryService:46-60` | 10-30 min | admin edits, new brand |
| Alias set and category names (typeahead) | `SearchService:62` | 10 min | alias or product edits |
| Outlet facts | the three Directory classes, `ScopeResolver` | 5 min | outlet update |
| Store, organisation and delivery-policy facts with parsed hours and pincodes | the three Directory classes | 60 s | store, org, policy or hours update (suspension must evict at once) |
| Supplier performance metrics | `OrderDerivedPerformanceProvider` | 5 min | rating publish, receiving, order terminal state |
| Per-store `sku_count`, top categories | popular, storefront | 5 min | offer or SKU change |
| Delivery slots per store | `SupplierOrderMapper:53` | 5 min | slot edits |
| Closed-month wallet totals, `availableMonths` | `WalletHistoryService` | hours | a new ledger row in that month |
| Admin dashboard aggregates | `OperationsDashboardService` | 30-60 s | TTL only |

**Never cache:** user grants or any authorization decision (revocation must apply on the next request), wallet balances,
credit availability, draft basket prices (live by design), payment state, order status.

## Quick wins (small, low risk)

**Status:** items 1 to 5 below were done in D-148 (V76 indexes; nightly retention for idempotency, outbox, live location, refresh tokens and OTP; realtime single-statement delete; typeahead prefix query; role-code cache; pool 30 with max-lifetime and leak detection). Not done: purges for `notification*` and `payment_webhook_event` (retention decisions for their owners), caching of categories, brands and store facts (needs an eviction design), and everything under "Fix first".
1. Indexes: `supplier_offer(supplier_sku_id, status)`, `supplier_order(status, updated_at)`, `supplier_order(created_at)`,
   `payment(created_at)`, `payment(captured_at)`, `dispute(created_at)`, `dispute(resolved_at)`,
   `refund(late_success_at, late_success_resolved_at)`, `refund(status, reversed_at, id)`, `outbox_event(status, id)`,
   `wallet_top_up(outlet_id, status, created_at)`, `sku_review(supplier_sku_id, moderation_status, created_at)`,
   `canonical_product(status, name)`.
2. Call the existing idempotency purge from a scheduled job; add purges for `outbox_event` (published), `notification*`,
   `delivery_location`, `refresh_token`, `otp_verification`, `payment_webhook_event` payloads.
3. Replace derived `deleteBy...` with limited `@Modifying` deletes; replace `aliases.findAll()`; switch `Page` to `Slice`
   where the total is not shown.
4. Raise the pool and add `max-lifetime` and `leak-detection-threshold`.
5. Cache roles, categories, brands, and the shared store/outlet info.

## Not covered
`SupplierCatalogService.toResponse`, bill-status joins in `WalletHistoryService`, delivery and settlement list services, and
`JwtAuthenticationFilter` per-request lookups were not inspected in depth. Lock ordering between refund, payment and
wallet was checked only at the call sites listed in the review, not across all paths.
