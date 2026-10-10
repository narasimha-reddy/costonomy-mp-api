---
name: db-bottleneck-review
description: Find database bottlenecks and caching opportunities in costonomy-mp-api. Use when asked to review performance, find slow or N+1 queries, missing indexes, unbounded reads, long transactions, connection-pool or lock contention, job/outbox load, tables with no retention, or where caching would help; and before adding a new list endpoint, scheduled job or query to a hot path.
---

# DB bottleneck review (costonomy-mp-api)

Spring Boot 3.2, JPA plus `JdbcTemplate`, MySQL 8 InnoDB (REPEATABLE READ), Flyway, ShedLock. Read
`docs/performance/DB_BOTTLENECKS.md` first: it is the baseline audit (2026-10-06) with 15 high findings, the caching
plan and the quick wins. Do not re-report what it already lists unless you have new evidence; say "already in the
baseline" and move on.

## Facts that decide severity here
- Hikari pool is **10** connections. The scheduler runs **8** threads and Tomcat up to **200** request threads on them.
  Anything that holds a connection for long, or opens a second one (`REQUIRES_NEW` inside a transaction), is a risk.
- There is **no Spring cache**. Only `AppConfigService` (per-JVM snapshot) and `RolePermissionCatalog` cache anything.
- IDENTITY keys, so Hibernate cannot batch inserts (`saveAll` is one INSERT per row).
- No `hibernate.default_batch_fetch_size`, so a stray lazy load is an N+1.
- Hot read paths: discovery and search (`StorefrontService`, `SearchService`, `CatalogQueryService`,
  `RecommendationService`, `PopularSupplierService`, `SkuDetailService`), basket and intent reads, order lists,
  permission checks (`AccessControlService`), chat and notification polling.

## Procedure
1. **Scope it.** A feature, a package, or the whole API. For the whole API, work hot read paths first, then jobs and
   write paths.
2. **Run the scan** from the repo root. It prints leads, capped per check; a hit is something to read, not a verdict:
   `.claude/skills/db-bottleneck-review/scripts/audit.sh` (or `audit.sh nplus1|unbounded|like|jobs|tx|locks|deletes|batching|indexes|purge`;
   `CAP=60` for more lines). Pipe through `head` if needed.
3. **Read each lead in context** and classify it using the checklist below. Count queries per request by reading the
   call chain, including what `AccessControlService.requireScoped` adds (about 3 queries per call).
4. **Check indexes against the predicate.** List a table's indexes from the migrations
   (`grep -n "<table>" src/main/resources/db/migration/*.sql`), then confirm with the real optimizer:
   run `EXPLAIN` on the local database (credentials are the `MYSQL_*` defaults in `application.properties`; the local
   database is `costonomy_mp`). A good plan uses an index with a small `rows` estimate and no `Using filesort` on a large
   set. A `type: ALL` scan of a table that grows is a finding.
5. **Measure when it matters.** To count statements for one request, set
   `logging.level.org.hibernate.SQL=DEBUG` (and `org.springframework.jdbc.core.JdbcTemplate=DEBUG`) in a local run, or
   enable `spring.jpa.properties.hibernate.generate_statistics=true`, and make the call. Report the number.
6. **Report** in the format below. Do not change code unless asked; if asked to fix, follow "When you fix".

## Checklist (what to look for)
**Reads**
- N+1: a repository or `JdbcTemplate` call inside `stream().map`, a `for` loop, or a mapper called once per row.
  Fix: one `IN (...)` query and a map, or a batch mapper (`toResponses(List)`).
- `findAll()` then filter in Java; `List` returns with no `Pageable`; a `limit` applied **after** loading everything.
- `Page<>` where the total is not shown: it adds a `count(*)` per request. Use `Slice`.
- `lower(col) like '%x%'` or any leading wildcard: no index can serve it. The columns are `utf8mb4_0900_ai_ci`, already
  case-insensitive, so `lower()` is wasted too. Fix: prefix search on a normalized column, resolve ids first, FULLTEXT.
- `(? is null or col = ?)` filters: the optimizer cannot use the index. Build the WHERE from the supplied filters.
- `ORDER BY ... LIMIT` before a filter that drops rows in Java (the page is then wrong as well as slow).
- Correlated `count(*)` per row; all-time aggregates recomputed per request (performance, ratings, product counts).
- JSON column parsed per row (operating hours, pincodes).
- Reference data re-read every request (roles, categories, brands, store and outlet facts, app config).

**Writes, jobs, locks**
- A `@Scheduled` method that is `@Transactional`, selects without a limit, or calls an external provider inside the
  transaction. Check: bounded? ordered by an indexed column? what happens after an outage backlog?
- Queue-like tables (outbox, notification delivery, realtime): does the claim query match an index, are poison rows
  removed from the batch, is dispatch outside the transaction?
- `REQUIRES_NEW` inside another transaction (needs two connections), and sequence or number-generator rows every order
  touches.
- Hot rows locked by every request (wallet, outlet, store, config) and locks held across a provider call. Lock order
  is documented as order, adjustment, wallet, payment: flag any path that reverses it.
- One transaction over a whole file or all stores (imports, settlement generation).
- Derived `deleteBy...` (SELECT then delete row by row); bulk deletes without `LIMIT`.
- Writes inside GET endpoints.
- Tables that only grow with **no purge**: check `grep -rn deleteExpired|purge|retention src/main/java`.
- Unique keys on random strings or JSON on append-only tables; many secondary indexes on a write-heavy table.

## Caching rules
Use Caffeine (add `spring-boot-starter-cache`) for per-process reference data and Redis only for what must be shared or
invalidated across instances. Always state the TTL and what evicts the entry.
- **Safe**: `app_config`, role maps, outlet/store parent ids, categories, brands, alias set, outlet and store facts
  (immutable parts only; compute `openNow` at read time), supplier performance metrics (about 5 min), per-store counts,
  delivery slots, closed-month wallet totals, admin dashboard aggregates (30-60 s).
- **Never cache**: user grants or any authorization decision (revocation must apply on the next request), wallet
  balances, credit availability, draft basket prices (live by design), payment state, order status.
- A store suspension must evict immediately.

## Severity
- **High**: can exhaust the 10-connection pool, holds a transaction across external calls or a whole backlog, scans a
  growing table on a hot or per-minute path, or grows without bound with no purge.
- **Medium**: constant-factor waste on a hot path (extra queries per request), or an unbounded list that grows per
  user.
- **Low**: admin or low-traffic screens, small tables, or a rare path.

## Report format
A ranked table: `# | file:line | what happens | why it matters at scale (numbers: queries per request, rows scanned) |
severity | index status (name the migration, or "missing") | fix`. Then: tables with no purge, caching candidates with
TTL and eviction, and "Not inspected". Facts and file references only; mark anything you did not verify by reading or
measuring as "from reading".

## When you fix
- A new index is a Flyway migration with the **next free version** (check the highest `V*.sql`). Name it `ix_<table>_<cols>`.
  Large tables: prefer `ALGORITHM=INPLACE, LOCK=NONE` and say so.
- Add a test that fails without the fix (for an N+1, assert a statement count or use the batch mapper's behaviour), and
  mutation-check it.
- Add a decision to `docs/DECISIONS.md` and a line to `CHANGELOG.md`.
- **Do not run `mvn clean` in the repo folder while a dev server is running from `target/`**: it breaks the server.
  Test in a temporary `git worktree` copy instead, and never remove the copy while a build is running in it.
- Update `docs/performance/DB_BOTTLENECKS.md` (mark the item fixed with the commit) so the baseline stays true.
