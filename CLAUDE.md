# costonomy-mp-api

Spring Boot backend for **Mandi** — Costonomy's restaurant procurement
marketplace. Modular monolith, MySQL `costonomy_mp`.

Mobile client lives in `costonomy-mp-mobile` (sibling repo).

> **Status: Phases 1, 3–7 complete** — foundation, authentication, organisations
> with authorization, catalog, search with Best Value recommendations, and
> requirements through to submitted supplier orders. Next is Phase 8, supplier
> acceptance. Build sequence: `docs/specs/00-README.md` §8.
>
> **Before Phase 9, read OPEN-004 in `docs/DECISIONS.md`:** payment is not yet
> enforced before an order reaches a supplier.

## Read before writing code

1. `docs/specs/00-README.md` — entry point to the specification
2. `docs/specs/02-domain-model-database.md` — domain model and DDL
3. `docs/specs/03-state-machines-permissions.md` — states, transitions, authorization
4. `docs/specs/04-api-specification.md` — REST contracts
5. `docs/DECISIONS.md` — decisions the specs don't settle, and the **open** ones

Where the specs disagree, `docs/DECISIONS.md` D-001 says which wins:
the numbered `00`–`10` docs and PRD v2.2 are authoritative;
`Mandi_Engineering_PRD_v1.0.md` is background only.

Decisions already made that you should not re-litigate: **D-010** a payment is
per supplier order; **D-011** responses use the `{ data, error, meta }` envelope;
**D-002** Flyway owns the schema; **D-009** enum columns are `VARCHAR` and need
`@JdbcTypeCode(SqlTypes.VARCHAR)` on every enum field.

## Stack

Follows `costonomy-api` (`~/Documents/GitHub/costonomy-api`) — inspect it before
inventing anything:

- Java 17, Spring Boot 3.2.x, Maven, `com.costonomy` groupId
- Spring Security + JJWT, Spring Data JPA, MySQL, Redis
- ShedLock for `@Scheduled` jobs across instances

Base Java package: `com.costonomy.mp`.

**One deliberate divergence:** schema uses **Flyway** (`src/main/resources/db/migration/`,
`ddl-auto=validate`), not `costonomy-api`'s hand-applied `migrations/*.sql`. See
`docs/DECISIONS.md` D-002 for why.

Deployment is out of scope for this version. Local development must work end to
end with mock providers and documented environment configuration.

## What exists

```
common/
  api/          ApiResponse — the { data, error, meta } envelope
  error/        ErrorCode catalogue, BusinessException, GlobalExceptionHandler
  web/          RequestIdFilter, RequestContext — correlation (doc 09 §15)
  idempotency/  IdempotencyService + IdempotencyStore (doc 04 §21)
  audit/        AuditService — append-only, redacts secrets (doc 09 §7)
  outbox/       OutboxService + OutboxPublisher (doc 02 §10)
  domain/       BaseEntity — id, timestamps, @Version
  config/       Security, Jackson, OpenAPI, ShedLock
```

Empty module packages (`identity`, `restaurant`, `supplier`, `catalog`,
`requirement`, `procurement`, `order`, `payment`, `credit`, `delivery`, `trust`,
`settlement`, `notification`, `admin`) are the agreed modular-monolith structure.
A module owns its entities, repositories, services and controllers; modules talk
through services, never by reaching into another module's repositories.
`common` is the shared kernel every module may depend on.

```
access/         authorization — the heart of tenant isolation
  domain/       ScopeType, Permissions, Roles, Role, Permission, UserRole
  service/      AccessControlService, RoleGrantService, MembershipService,
                RolePermissionCatalog, ScopeResolver
catalog/        canonical products, supplier SKUs and offers, bulk import
  domain/       CanonicalProduct, SupplierSku, SupplierOffer, Normalization,
                ImportValues
  service/      CatalogQueryService, SupplierCatalogService,
                CatalogImportService, CatalogFileParser
procurement/    requirements, cart, checkout, approval, supplier orders
  domain/       Requirement, Procurement, SupplierOrder, Pricing, and the
                three state machines
  service/      RequirementService, ProcurementService, ApprovalPolicyEvaluator,
                ProcurementSubmitter, ProcurementStateStore
discovery/      search, serviceability, Best Value ranking
  domain/       Serviceability, RankingWeights, ExplanationCode, SupplierPerformance
  service/      BestValueScorer, RecommendationService, SearchService,
                SupplierPerformanceProvider
restaurant/     Restaurant, Outlet, membership; RestaurantService
supplier/       SupplierOrganization, SupplierStore, verification; SupplierService
identity/
  domain/       User, OtpVerification, RefreshToken, Device
  provider/     OtpProvider port + MSG91 and Mock adapters
  service/      OtpService + OtpAttemptStore, JwtService,
                RefreshTokenService + RefreshTokenStore, AuthService,
                DeviceService, PhoneNumbers
  security/     AuthenticatedActor, ActorContext, JwtAuthenticationFilter
  web/          AuthController, DeviceController
```

### Three things that are easy to get wrong here

**`IdempotencyStore` is a separate bean from `IdempotencyService` on purpose.**
Spring's `@Transactional` works through a proxy, and a self-invocation bypasses
it silently. If the `REQUIRES_NEW` claim methods lived on `IdempotencyService`
and were called from its own `execute()`, the annotation would be ignored, the
claim would join the caller's transaction, and a concurrent duplicate would not
see it until that transaction committed — by which point both callers have
authorised a payment. Do not merge these two classes.

**`AuditService` and `OutboxService` join the caller's transaction, also on
purpose.** The audit row, the event, and the state change commit together or not
at all. An audit entry for a rolled-back suspension is worse than none.

**A side effect that must survive a thrown exception needs its own transaction,
in its own bean.** This is the same proxy rule as above, but it bites in a way
that looks correct from the outside, and it has now produced four real bugs here
(D-016):

- OTP attempt counting incremented inside the transaction that the
  `OTP_INVALID` throw then rolled back. The counter stayed at zero, so the
  attempt limit was decorative and a six-digit code was open to exhaustive
  guessing. Now in `OtpAttemptStore` (`REQUIRES_NEW`, atomic SQL increment).
- Refresh-token replay detection revoked every session and *then* threw. The
  throw rolled the revocation back, so the replay was rejected while the
  compromised session stayed live for another thirty days. Now in
  `RefreshTokenStore`.

- Procurement submission marked an order `FAILED` and then threw. The restaurant
  was told a supplier had gone offline while the order still read `READY`. Now in
  `ProcurementStateStore`.
- `doSubmit` was `@Transactional` but self-invoked from inside the idempotency
  lambda, so the entire submission would have run with no transaction. Now in
  `ProcurementSubmitter`.

Every one of these passed a test that checked the error response, and failed a
test that checked whether the thing the error described had actually happened.
**Assert the side effect, not the rejection** — `attemptsAreLimited` checks the
correct code stops working, `replayRevokesAllSessions` checks the *other* token
dies, `offlineBetweenValidateAndSubmit` checks the procurement really is `FAILED`.

## Rules that are not negotiable

**The backend is the authority.** Never trust a client-supplied price, total, or
state transition. The server calculates price, GST, delivery, total, commission
and credit — every time.

**Every scoped read and write goes through `AccessControlService`.** A permission
alone grants nothing; the check is always actor + permission + scope (doc 03 §16).

- Use `requireScoped(...)` when the resource might belong to another tenant. It
  reports denial as **404, not 403** — otherwise a caller can change an id in a
  URL and enumerate which outlets, stores and orders exist (doc 09 §3).
- Use `require(...)` only when the caller is already known to be inside the tenant.
- Grants are read live from `user_role` on every check. Only the role → permission
  catalogue is cached. Revoking access must take effect on the next request
  (doc 46), which is also why the JWT carries no permissions.
- A role grant endpoint must restrict which roles it can grant. `REST_*` on an
  outlet, `SUP_*` on a supplier. Without that, an org admin can grant themselves
  an internal role and leave their own tenant.
- **No internal (`OPS_*`) role may hold a tenant permission.** That property is
  what makes a `PLATFORM`-scoped grant safe to accept at any scope, and
  `PermissionCatalogIT` enforces it. Ops read-access needs its own `INTERNAL`
  permissions (`ORDER_SUPPORT` exists; add `SUPPLIER_INSPECT` and friends in
  Phase 14) rather than borrowing the tenant's.

**A price is never edited, only superseded.** Changing price, GST or availability
closes the current `supplier_offer` and opens a new one (D-012). In-place updates
break doc 02 §4 — historical values must survive a referencing transaction — and
destroy the price history doc 01 §26 needs. Compare money with
`BigDecimal.compareTo`, never `equals`: `410.00` and `410.0000` are the same price.

**Suppliers map onto canonical products; they never create them.** Canonical
products are platform-owned (doc 01 §7) and are the axis of comparison. If a
supplier could invent one, two suppliers' paneer would never appear side by side,
which is the marketplace failing at its only job. Brands are the opposite — created
on demand, because a regional brand missing from a list should not block an import.

**An import writes nothing before a human confirms it.** Doc 25 forbids a silent
partial import; the way to make an outcome not silent is to show it in full and
require someone to accept it. Invalid rows are reported per row and per field with
their line number, and skipped — never guessed at.

**Never fabricate a metric, and never show an unsupported explanation.** Doc 07 §4
and §5. A supplier with no order history has no fill rate — `SupplierPerformance`
makes every signal `Optional`, `BestValueScorer` redistributes an absent signal's
weight rather than substituting a value, and no explanation code is emitted unless
the figure behind it was actually measured (D-014). When you add a ranking signal,
handle its absence the same way.

**Commission is never a ranking input.** Guardrail 9. Not as a weight, not as a
tiebreak, not in a response. `BestValueScorerTest` asserts the component set.

**A requirement is credited on acceptance, never on submission.** Guardrail 14,
D-015. Placing an order is a hope; decrementing on hope makes a rejected order look
fulfilled and loses the need. Crediting on acceptance means the failure paths —
rejection, timeout, cancellation, partial acceptance — need no compensation at all.

**`Pricing` is the only place a line total is computed.** A second implementation
would eventually differ in the last paisa, and the restaurant would be the one to
notice. GST is computed on the *rounded* line value, so an invoice reconciles when
someone checks it with a calculator.

**Money.** `DECIMAL(19,4)`, never floating point. Rates `DECIMAL(9,4)`. Transaction
tables snapshot the commercial values needed to reconstruct them; never
reconstruct historical financial truth from the current catalog. Never
hard-delete a transactional record.

**Concurrency.** Acceptance vs timeout must have exactly one winner. Same for
duplicate acceptance, duplicate payment, credit reservation and delivery booking.
These need real concurrency tests, not a code review
(`docs/specs/10-testing-cicd-seed-data.md` §2).

**Idempotency** is mandatory on procurement submission, supplier state
transitions, payments, refunds, credit reservation, delivery booking, receiving
and disputes. Same key + same payload returns the original response; same key +
different payload returns `IDEMPOTENCY_KEY_REUSE`.

**Providers are isolated behind ports** — `PaymentProvider`, `OtpProvider`,
`DeliveryProvider`, `MapProvider`, `NotificationProvider`. No provider DTO reaches
a domain model. Every port has a mock implementation, and local development and
CI run entirely on mocks.

**Nothing external is authoritative.** MySQL is the transactional truth. Search
indexes are rebuildable projections; if search is down, transactions continue.
Redis is cache, locks and rate limiting — never money, orders or credit.

**Never**: expose delivery provider bidding to restaurants · let commission
influence organic ranking · accept an expired supplier order · silently reprice ·
silently drop unmet requirement quantities · create a runtime dependency on
Costonomy APIs · log an OTP, token or secret.

## Definition of done

A feature is not done at its happy path. `docs/specs/00-README.md` §9 lists the
full bar: migration, domain, service, API, authorization, mock provider,
idempotency, concurrency, unit + integration tests, edge cases, traceability
entry. Update `docs/specs/IMPLEMENTATION_TRACEABILITY.md` as you go — a
requirement without evidence is not complete.

## Naming

"Mandi" is a **working product name and is not final.** It belongs in user-facing
copy only — never in the repo name, Java package (`com.costonomy.mp`), database
(`costonomy_mp`), Docker image, environment variable prefix, or any
infrastructure identifier. A rename must stay a branding change, not a migration.
