# costonomy-mp-api

Spring Boot backend for **Mandi** — Costonomy's restaurant procurement
marketplace. Modular monolith, MySQL `costonomy_mp`.

Mobile client lives in `costonomy-mp-mobile` (sibling repo).

> **Status: Phases 1, 3–13 complete** — foundation, authentication, organisations
> with authorization, catalog, search with Best Value recommendations,
> requirements through to submitted orders, supplier acceptance with timeout,
> payments, supplier credit, delivery, realtime, and receiving/disputes/ratings.
> Next is Phase 14, notifications. Build sequence: `docs/specs/00-README.md` §8.
>
> **There are no open decisions.** OPEN-004 closed as D-020: a supplier order is
> created `DRAFT` and released only once funding is secured.

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

**Build with JDK 17.** Maven picks up whatever `JAVA_HOME` points at, and on a
newer JDK Lombok stops processing *silently* — the build then fails with several
hundred "cannot find symbol" errors on generated getters and constructors, none
of which point at the real cause. If you see that, check `mvn -v` before changing
any code:

```
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Integration tests need Docker running (Testcontainers, real MySQL 8).

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
trust/          receiving, disputes and ratings — what happens after goods arrive
  domain/       Receiving, ReceivingItem, Dispute, DisputeItem, DisputeMessage,
                DisputeEvidence, Rating, and their lifecycles
  service/      ReceivingService, DisputeService, RatingService, TrustDirectory
realtime/       live updates over WebSocket, with a polling fallback
  domain/       RealtimeChannel, RealtimeEvent, RealtimeTicket
  repository/   RealtimeEventStore, RealtimeTicketStore (the atomic claims)
  service/      RealtimeEventRelay, RealtimeRouter, RealtimeEntitlements,
                RealtimeSessionRegistry, RealtimeBroadcaster (Local/Redis),
                RealtimeTicketService, RealtimeQueryService, RealtimeJobs
delivery/       provider-agnostic delivery, tracking and reassignment
  domain/       Delivery, DeliveryStatus, DeliveryMode, DeliverySelection,
                DeliveryQuote, DeliveryProviderAttempt, DeliveryEvent,
                DeliveryLocation
  provider/     DeliveryProvider port + two configured mocks
  service/      DeliveryService, DeliveryQuotingService, DeliveryBookingService,
                DeliveryEventService, DeliveryOrderBridge, DeliveryTimeline,
                DeliverySimulationService, DeliveryJobs
credit/         supplier-funded credit: requests, agreements, ledger, invoices
  domain/       CreditAgreement, CreditRequest, CreditReservation, CreditInvoice,
                CreditPayment, CreditTransaction, CreditLimitHistory,
                SupplierCreditPolicy, CreditExposure
  repository/   CreditExposureStore (the conditional UPDATEs), InvoiceNumberGenerator
  service/      CreditAgreementService, CreditLedgerService, CreditLedger,
                CreditInvoiceService, CreditPolicyService, CreditJobs,
                CreditFundingAdapter (implements procurement's OrderFundingPort)
payment/        authorization, capture, release, refunds, webhooks
  domain/       Payment, PaymentStatus, PaymentTransaction, Refund,
                RefundReason, RefundStatus, PaymentWebhookEvent
  provider/     PaymentProvider port + Razorpay and Mock adapters
  service/      PaymentService, RefundService, PaymentJobs,
                PaymentWebhookService + PaymentWebhookStore,
                OrderFundingAdapter (implements procurement's OrderFundingPort)
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
- Catching a constraint violation inside the transaction it poisoned — **three
  times now** (D-021 payments, D-031 realtime, and the delivery event store built
  to avoid it). The realtime case was the worst: the relay runs inside the outbox
  drain's transaction, so one duplicate projection would have rolled back a batch
  of up to a hundred unrelated events. The rule, stated once: **the insert goes in
  its own bean under `REQUIRES_NEW`, and it throws rather than catching** — a
  catch cannot un-doom a transaction that is already rolling back. The caller
  catches it afterwards.
- Sequencing several writes to one aggregate from a caller with no transaction.
  Each `@Transactional` method re-attached a **stale detached copy** of the
  delivery, so the second write silently reverted the first and the status never
  moved. Five tests failed, none of them about the thing that was broken. The
  sequencing now lives in `DeliverySimulationService` under one transaction
  (D-030) — the general rule being that writes which have to compose belong
  inside a transaction, not strung together by their caller.
- Reading a balance back through JPA after changing it with SQL. The conditional
  UPDATEs in `CreditExposureStore` are invisible to the entity manager, so a
  `findById` in the same transaction returns the **stale** first-level-cache
  instance — and a ledger row built from it records balances that were never true.
  `CreditExposureStore.read` goes straight to the row for exactly this reason.
- A **duplicate webhook** returned 500. The unique-constraint violation had
  already marked the transaction rollback-only, so catching it changed nothing
  and the commit threw afterwards — telling the provider to retry an event we
  already had, forever. Now in `PaymentWebhookStore`, and note the second half of
  the rule (D-021): the store **throws** the duplicate rather than catching it
  inside its own transaction, because a catch cannot un-doom a transaction. Its
  caller catches it, after that transaction has rolled back.

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

**A supplier never sees an order that is not funded.** Guardrail 16, doc 01 §14,
D-020. Submission creates supplier orders in `DRAFT` with **no acceptance
deadline**; `OrderReleaseService.releaseIfFunded` moves them to
`PENDING_ACCEPTANCE` and starts the clock at that moment, from whichever of the
confirm call, the webhook or the reconciliation job arrives first. Funding means
`PaymentStatus.fundsSecured()` — nothing else may decide it. Money is captured
only after acceptance and only for what was accepted; the remainder of a partial
acceptance is *released*, never refunded, so nothing reaches the restaurant's
statement that should not be there.

This applies identically to credit, which is why both go through
`OrderFundingPort` and `OrderFunding` routes between them. A credit order differs
only in timing: the reservation succeeds or fails inside the submission, so the
order releases immediately and there is no intent for the client to complete. Add
a funding method by adding an `OrderFundingPort`, never by branching on the
payment method in procurement.

**Credit is supplier-funded and supplier-controlled.** Doc 01 §18. Every limit,
term, per-order cap and suspension is the supplier's; Mandi runs the workflow,
the ledger and the reconciliation and does **not** fund credit, guarantee or own
receivables, bear losses, or chase debts. Concretely, that means: never grant or
extend credit on a supplier's behalf (a store that has not configured a policy has
not opted in — absent is not "enabled with defaults"); never auto-suspend unless
they asked for it; and a repayment is *recorded by the supplier*, never by the
restaurant, because the money moved outside Mandi and only the party it reached
can confirm it arrived.

**Receiving adds to the order; it never rewrites it.** Doc 03 §11, D-035. The
accepted quantity stays exactly as the supplier committed to it — that is what was
paid for and what every dispute is argued from — and what arrived goes to
`fulfilled_quantity`. Every line must be answered and
`received + damaged + missing` must equal `accepted`; defaulting any of those
would reinstate the blind "Complete" button §23A.22 forbids. A receiving shortfall
does **not** re-open the requirement: it is a commercial dispute, and re-opening
would have the restaurant order the same goods twice.

**A dispute never touches the order.** Doc 01 §22, D-036. Not its status, not its
quantities, not its payment — and the response carries the order status so the app
can say so (§23A.26). Several disputes per order are allowed, because a delivery
can be short *and* damaged. A supplier answers and proposes; only the restaurant
resolves. **Mandi records disputes, it does not adjudicate them** — nothing here
issues a refund or a credit note on anyone's behalf.

**Ratings publish on write and are removed by moderation, never gated by it.**
D-037. Hiding one removes it from the public average *and* from ranking, which is
what makes moderation more than cosmetic. A store nobody has rated has no average,
and a blank dimension is excluded from that dimension's mean rather than counted
as neutral.

**Realtime is a projection of the outbox, never a second publisher.** D-031. A
new event type reaches phones because it is published to the outbox, not because
somebody remembered to call a broadcaster — so never add a `broadcast(...)` call
to a service. The socket, the polling endpoint and a reconnect all read
`realtime_event` by the same cursor (D-032), and **realtime is a prompt to
refresh, never the record**: authoritative state always comes from the resource's
own endpoint, which is why a client past the retention window is fine.

**A realtime channel is an authorization decision.** D-033. Channels are derived
from live grants at handshake and re-checked on every delivery; a client cannot
ask to join one, and there is no channel parameter on the polling endpoint. A
routing mistake here is a disclosure with no per-message audit trail to find it
afterwards, which is why `RealtimeRouter` drops anything it cannot route rather
than guessing.

**A delivery is one consignment, whatever goes wrong.** Doc 06 §7, D-026. A
driver cancelling, a provider refusing, a pickup failing — each appends a
`delivery_provider_attempt` and a `delivery_event` and leaves the delivery the
restaurant is watching where it was. Never create a second delivery row for the
same order; `uk_delivery_order` will stop you, and the reason is that two rows
make one retried delivery look like two deliveries, one of which failed.

**Never fabricate a driver, a position or an ETA.** Doc 06 §8. There is no driver
before assignment and the response says so; a position exists only because a
provider reported one, and `recorded_at` is *their* timestamp; a fix older than
the freshness threshold comes back with `locationStale` set rather than drawn as
current. An interpolated position is indistinguishable from a real one once it is
on a map, which is why the rule is absolute rather than a matter of degree.

**What the restaurant pays is one number; the auction behind it is ours.** Doc 06
§4 and §10, D-027. `DeliveryResponse` has no `providerCode` and there is no
endpoint returning `delivery_quote`. Failed and declined quotes are still stored,
because doc 06 §12 needs the quoting reconstructable and because otherwise a
forced fallback looks like a choice.

**On a partner delivery, only the partner's events move the order.** §23A.38,
D-028. `SupplierOrderStatus` gives the supplier nothing past `READY_FOR_PICKUP`,
so `OUT_FOR_DELIVERY` and `DELIVERED` come from the courier's events via
`DeliveryOrderBridge`. Supplier own delivery is a different mode with a different
source of truth, and the endpoints for it refuse a `COSTONOMY` delivery.

**`reserved` and `utilized` are written only by `CreditExposureStore`.** Every
method there is one conditional UPDATE that re-checks its precondition in the same
statement, so the credit reservation race is settled by the database and the loser
can be told what actually happened — "not enough credit", not
`CONCURRENT_MODIFICATION` (D-025, D-018). `available` is always derived, never
stored: a persisted copy is a second source of truth that drifts. And a limit can
never be cut below `reserved + utilized`, or doc 10 §3's identity stops holding
(D-024).

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
(`docs/specs/10-testing-cicd-seed-data.md` §2) — see
`SupplierAcceptanceIT$Concurrency` for the shape: two threads on a latch, then
assert the aggregate landed on exactly one outcome.

**And report a lost race as what happened** (D-018). When optimistic locking
rejects a write, re-read the aggregate and throw the error describing the outcome
that won — `SUPPLIER_ORDER_EXPIRED`, not `CONCURRENT_MODIFICATION`. The second is
accurate and tells the supplier nothing they can act on.

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
