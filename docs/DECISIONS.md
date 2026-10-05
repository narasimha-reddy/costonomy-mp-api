# Decisions

Decisions that are **not** derivable from the spec set in `docs/specs/`, either
because the specs conflict or because they were settled after the specs were
written. Add to this file rather than re-arguing a decision in a PR.

Each entry: what was decided, when, and — most importantly — **why**, so a future
reader can tell whether the reason still holds.

---

## D-001 — Repository names are `costonomy-mp-api` and `costonomy-mp-mobile`
**2026-09-14 · Settled**

`Mandi_Engineering_PRD_v1.0.md` §2 says to create `mandi-api` and `mandi-mobile`.
Both `MANDI_Claude_Code_Implementation_PRD_v2.2.md` §1–2 and `00-README.md` §1
explicitly forbid that, and v2.2 guardrail 20 names it as a thing Claude Code
must never do.

**Decision:** v2.2 and the numbered `00`–`10` doc set are authoritative.
`Mandi_Engineering_PRD_v1.0.md` is retained in `docs/specs/` for background only
and is **superseded** wherever the two disagree.

**Why:** "Mandi" is a working product name that is not final (v2.2 §1). Baking it
into repository, package, database or infrastructure identifiers makes a later
rename a migration instead of a config change.

Consequences: repos are `costonomy-mp-*`, Java package `com.costonomy.mp`,
mobile package `com.costonomy.mp`, database `costonomy_mp`. Only user-facing
strings say "Mandi".

---

## D-002 — Flyway for schema migrations
**2026-09-14 · Settled**

The existing `costonomy-api` does **not** use Flyway: `spring.jpa.hibernate.ddl-auto=none`
with hand-written files in `src/main/resources/migrations/*.sql`, applied manually
per environment. All three Mandi PRDs specify Flyway; `02-domain-model-database.md`
§8 names the migration files down to `V1__identity_access.sql`.

**Decision:** Flyway, in `src/main/resources/db/migration/`, with
`spring.jpa.hibernate.ddl-auto=validate`.

**Why:** `10-testing-cicd-seed-data.md` §8 requires every migration to run from a
clean database in CI and requires testing an upgrade from the previous schema
version. Hand-applied SQL with no version table cannot satisfy either. With
several developers on the repo, ordering drift is a matter of time.

This is a deliberate divergence from `costonomy-api`. It does not change the
runtime stack, only how schema reaches a database.

---

## D-003 — Mobile mirrors costonomy-mobile-app's stack, version for version
**2026-09-14 · Settled**

**Decision:** Expo SDK 55, React Native 0.83.2, React 19.2.0, expo-router,
TanStack Query, expo-secure-store, Source Sans 3 — the same versions
`costonomy-mobile-app` runs today. Added for this domain: `react-native-maps`
(delivery tracking) and a WebSocket client (realtime).

**Why:** developers move between the two repos without relearning anything, and
the proven pieces port directly — in particular `costonomy-mobile-app/lib/api.ts`'s
refresh-token deduplication, which is subtle and already works.

Consequence: when `costonomy-mobile-app` upgrades Expo, this repo should follow
rather than diverge.

Two small, deliberate exceptions, both because `npx expo-doctor` requires them
for a clean SDK 55 project — `costonomy-mobile-app` is simply running slightly
behind what its own Expo version expects:

- `react-native` is `0.83.10`, not `0.83.2`
- `react-native-maps` is `1.27.2`, and `react-native-worklets` is present as a
  required peer of Reanimated 4

Everything else matches version for version. `npx expo-doctor` passes 20/20; keep
it that way.

---

## D-004 — Base palette identical to Costonomy, plus marketplace semantic tokens
**2026-09-14 · Settled**

**Decision:** `theme/colors.ts` copies `costonomy-mobile-app/constants/colors.ts`
verbatim — `#FF6000` primary, same neutrals, same semantics — and adds tokens for
concepts the Costonomy app has no equivalent of: supplier response countdown
(`countdown*`), supplier credit (`credit*`), best-value savings (`savings`,
`recommended`), live delivery (`delivery*`), and data freshness (`stale`, `offline`).

**Why:** Mandi should read as a Costonomy product, and the product name is not
final (D-001) so a distinct brand hue now would likely be re-picked later. The
new tokens are needed regardless of branding: they encode domain states, not
style.

See `theme/README.md` (mobile repo) for the rules that keep this holding.

---

## D-005 — Light mode only, with dark-ready tokens
**2026-09-14 · Settled**

**Decision:** ship light only, matching `costonomy-mobile-app`
(`app.json` pins `userInterfaceStyle: light`). But every colour goes through a
semantic token, and an ESLint rule (`eslint.config.js`) fails the build on any
colour literal outside `theme/`.

**Why:** dark mode is real work and doubles per-screen verification, which is not
where the effort belongs before a single screen exists. The lint rule is what
keeps "add dark later" a one-file change rather than an archaeology exercise —
without it the promise decays within a sprint.

---

## D-006 — Search: port and abstraction now, OpenSearch later
**2026-09-14 · Settled**

`Mandi_Engineering_PRD_v1.0.md` §9 mandates OpenSearch. `00-README.md` §2 says
only "Abstraction; do not make external search infrastructure authoritative", and
`07-search-recommendations.md` §12 says search infrastructure "can be introduced
for performance".

**Decision:** define a `ProductSearchPort` with a MySQL-backed implementation
first. An OpenSearch adapter implements the same port when result quality or
latency actually requires it.

**Why:** per D-001's reading, the numbered doc set wins. MySQL is the
transactional authority in every version of the spec, checkout revalidates
against it regardless, and running OpenSearch from day one adds operational
surface with no user-visible gain at launch volumes.


---

## D-007 — Integration tests run on real MySQL 8 via Testcontainers
**2026-09-14 · Settled**

**Decision:** database integration and migration tests run against a real MySQL 8
container. H2 in MySQL compatibility mode was rejected.

**Why:** `10-testing-cicd-seed-data.md` §8 requires every migration to run from a
clean database in CI and requires testing an upgrade from the previous schema
version. H2 does not faithfully reproduce MySQL 8 DDL, `utf8mb4_0900_ai_ci`
collation, `JSON` columns or foreign-key behaviour, so migrations verified
against H2 are verified against a database we never deploy to.

Practical shape:

- Unit tests are tagged plain and run under Surefire — no Docker, `mvn test`.
- Integration tests extend `AbstractIntegrationTest`, are tagged `integration`,
  and run under Failsafe — `mvn verify`.
- `AbstractIntegrationTest` skips itself when no Docker daemon is reachable, so a
  developer who has not started Docker gets a clean skip rather than a wall of
  initialisation errors. **CI asserts Docker is present** before running `verify`,
  because a silently skipped migration suite is the same as no migration suite.

### The Docker API version pin

`pom.xml` sets `-Dapi.version=1.44` for Failsafe. This is not optional on a
current Docker install, and the failure it prevents is very hard to diagnose:

Testcontainers 1.21.3 bundles docker-java 3.4.2, which negotiates a Docker Engine
API version below 1.44. Docker Engine 25+ (Docker Desktop 29 reports
`MinAPIVersion 1.44`) rejects those with an HTTP 400 whose body is an *empty*
`Info` struct. Testcontainers reports that as **"Could not find a valid Docker
environment"** — while `docker info` and `docker ps` work perfectly, which sends
you looking at sockets, contexts and permissions instead of at API versions.

Note the property is `api.version` (a docker-java **system property**). The
`DOCKER_API_VERSION` environment variable is *not* consulted here, so exporting
it has no effect. Override for an engine older than Docker 25 with
`-Ddocker.api.version=1.41`.

---

## D-008 — Platform tables migrate before the domain tables
**2026-09-14 · Settled**

`02-domain-model-database.md` §8 sketches idempotency, audit, outbox and config as
part of `V12__notifications_audit_analytics.sql`.

**Decision:** they move to `V2__platform.sql`, and the domain migrations shift one
number later:

```
V1  identity_access             V8  orders_fulfillment
V2  platform             ← new  V9  payments
V3  restaurant_outlet           V10 credit
V4  supplier                    V11 delivery
V5  seed_roles_permissions ← new V12 receiving_disputes_ratings
V6  catalog                     V13 settlement_commission
V7  requirements_procurement    V14 notifications_analytics
```

`V5` moved forward for the same reason as `V2`: authorization cannot work without
seeded roles and permissions, and every endpoint from Phase 4 on is authorized.
Doc 02 §8 put seed data last, which only works if nothing before it needs seeding.

**Why:** idempotency and audit are required by the *first* mutating endpoint, not
the last. Doc 02 §8 presents its list as "suggested", and the grouping is
preserved — only the order changed.

---

## D-009 — Enum columns are VARCHAR, annotated per field
**2026-09-14 · Settled**

Hibernate 6.4 maps `@Enumerated(EnumType.STRING)` on MySQL to a **native
`ENUM`** column. Our migrations use `VARCHAR(32)`, which doc 02 §6 specifies for
every `status` column, so `ddl-auto=validate` refuses to start.

**Decision:** keep `VARCHAR` in the schema and add `@JdbcTypeCode(SqlTypes.VARCHAR)`
to every enum field.

**Why VARCHAR rather than a native ENUM:** these are state machines that will
gain states — a new delivery failure branch, a new dispute resolution code. With a
native `ENUM` each new state is an `ALTER TABLE` on a large table. With `VARCHAR`
it is a code change.

**Why per-field rather than globally:** the global setting
(`hibernate.type.preferred_enum_jdbc_type`) only exists from **Hibernate 6.5**,
and Spring Boot 3.2.1 ships 6.4.4 — setting it there is silently ignored, which is
worse than not setting it. Revisit when Spring Boot brings 6.5+.

**This applies to every enum column added from here on.** `ddl-auto=validate`
will catch an omission at startup, but the error message points at a column type
rather than a missing annotation, so it is worth knowing up front.

---

## D-010 — A payment is per supplier order
**Raised 2026-09-14 · Settled 2026-09-14** (was OPEN-001)

The specs disagree:

- `Mandi_Engineering_PRD_v1.0.md` §6: `payment.procurement_request_id` — one
  payment per checkout.
- `02-domain-model-database.md` §9 DDL: `payment.supplier_order_id` — one payment
  per supplier order.

This is not cosmetic. One procurement can split across several supplier orders
(`01-product-requirements.md` §9), each accepted, partially accepted, rejected or
expired **independently**, and §14 requires capturing only the accepted
commercial value per order.

- *Per procurement*: one Razorpay authorisation, one restaurant-facing charge —
  but partial captures against a single authorisation must be reconciled across
  several orders resolving at different times, and Razorpay's partial-capture
  semantics constrain what is possible.
- *Per supplier order*: capture and refund map one-to-one onto the thing being
  accepted, which is far simpler to reconcile — but the restaurant sees several
  authorisations for one checkout, and a multi-supplier cart could partially fail
  at authorisation time.

**Decision: per supplier order**, following `02`'s DDL
(`payment.supplier_order_id`).

**Why:** capture and refund then map one-to-one onto the thing actually being
accepted. Partial acceptance captures the accepted value on that order's own
payment; a rejection releases that order's authorisation and touches nothing
else. Reconciliation (doc 09 §11, doc 10 §3 `captured <= authorized`) stays a
per-order property rather than a cross-order allocation problem.

**Accepted cost:** a restaurant checking out across three suppliers sees three
authorisations rather than one, and a multi-supplier cart can partially fail at
authorisation time. The mobile client must present these as one checkout even
though the backend holds several payments — the same aggregation it already does
for supplier orders (doc 05 §11: "Unified restaurant experience even when backend
splits into multiple supplier orders").

---

## D-011 — API responses use the `{ data, error, meta }` envelope
**Raised 2026-09-14 · Settled 2026-09-14** (was OPEN-002)

`04-api-specification.md` §2 specifies `{ data, error, meta }` with
`error.code` / `error.message` / `error.details`. The existing `costonomy-api`
does something different: an `ERR_NNNN` code catalogue
(`com.costonomy.exception.ErrorCode`) returned in `X-Error-Code` /
`X-Error-Message` headers, with ad-hoc body shapes per handler.

`00-README.md` §4 says to preserve existing Costonomy conventions; `04` §2 gives
an explicit and different contract.

**Decision: doc 04's envelope.** Implemented in
`com.costonomy.mp.common.api.ApiResponse`, with the code catalogue in
`com.costonomy.mp.common.error.ErrorCode`.

**Why:** it is the newer and more explicit contract; the mobile client is new so
nothing depends on the old shape; and the codes doc 04 §22 lists are *domain*
codes (`SUPPLIER_ORDER_EXPIRED`, `PRICE_CHANGED`, `CREDIT_LIMIT_EXCEEDED`) that a
numeric `ERR_NNNN` catalogue cannot express as readably.

Two consequences worth knowing:

- `error.code` is a **stable public string**. Clients branch on it. Never rename
  or repurpose one — add a new code.
- Spring Security rejects before the dispatcher runs, so `SecurityConfig`
  installs its own entry point and access-denied handler to emit the same
  envelope. Without that, an expired token would return Spring's HTML error page
  and the mobile client's central error mapping would have nothing to parse.

---

## D-012 — SKU and offer are separate tables; a price is never edited
**2026-09-14 · Settled**

`02-domain-model-database.md` contradicts itself. §3 and §4 list `supplier_offer`
as its own table and describe it as "current purchasable offer … historical
commercial values must not be overwritten when they are referenced by a
transaction". §9's representative DDL puts `price`, `gst_rate` and `availability`
directly on `supplier_sku`.

**Decision:** two tables.

- `supplier_sku` is **identity** — the supplier's code, brand, pack, and the
  canonical product it maps to.
- `supplier_offer` is **commercial terms**, effective-dated. Changing price, GST
  or availability closes the current row (`effective_to` = now, status
  `SUPERSEDED`) and inserts a new one. Nothing is ever updated in place.

**Why:** §9's DDL is explicitly "representative", and §9 itself says to expand it
into complete DDL "for every table listed above" — so the table list in §3 wins
over the illustrative DDL. More substantively, in-place price updates break two
requirements at once: doc 02 §4's rule that historical values survive a
referencing transaction, and doc 01 §26's pricing intelligence, which needs the
history to exist.

Orders still snapshot their own commercial values (doc 02 §5). The offer table is
the *catalog's* record of what was offered, not the *order's* record of what was
agreed. Both are needed: an order explains itself, and the catalog explains how
prices moved.

Two consequences worth knowing:

- Editing only identity fields (a rename, a new image) deliberately leaves the
  current offer untouched, so it does not manufacture a price-history entry.
- Comparison uses `BigDecimal.compareTo`, not `equals`. A supplier re-uploading a
  weekly price list where `410.00` becomes `410.0000` has not changed their price,
  and must not accumulate a fake price change every week.

---

## D-013 — Best Value normalises by ratio-to-best, not min-max
**2026-09-14 · Settled**

Doc 07 §4 requires each ranking component to be normalised, without saying how.
The obvious choice — min-max, `1 - (v-min)/(max-min)` — is wrong here, and the
failure is quiet.

Min-max stretches whatever spread exists to fill [0,1]. With two candidates the
cheaper always scores 1 and the dearer always scores 0, **whether the gap is ₹1 or
₹10,000**. Price then dominates every comparison, and a supplier ₹1 cheaper
outranks one that is materially more reliable. "Best Value" degenerates into a
price sort while still being described to the restaurant as best value.

**Decision:** ratio-to-best. An offer scores `best ÷ itsValue` on dimensions where
lower is better. 10% dearer scores 0.9; twice the price scores 0.5; all equal
scores 1 for everyone.

Differences stay proportionate to their real size, so a small price premium is a
small penalty that a genuinely better supplier can earn back.

This was found by the test doc 07 §14 names first — "cheapest is not recommended
when reliability is materially worse". Under min-max it failed. Worth remembering
if the scoring is ever revisited: that scenario is the one that tells you whether
the normalisation is sound.

---

## D-014 — Missing performance signals redistribute their weight
**2026-09-14 · Settled**

Doc 07 §4 and Engineering PRD §10: *"If historical data is insufficient, use
deterministic baseline ranking and never fabricate metrics."* Doc 07 §6 adds that
new suppliers must not be penalised indefinitely.

There are no completed orders until Phase 8, so **every** supplier is currently
unrated. The two easy ways to handle that are both wrong:

- Substitute 0 — marks every new supplier as having failed deliveries they never
  made.
- Substitute 1 — hands them the standing a supplier earned over 200 orders, and
  then "Reliable supplier" appears on screen supported by nothing, which doc 07 §5
  forbids outright.

**Decision:** an absent signal is dropped from the score and its weight shared
among the components that do have data. A candidate scored on three of seven
components is still scored out of 1, so it stays comparable and a good new
supplier can still reach the top.

Two things follow, and both matter:

- **The cold-start policy is not a special case.** A supplier with no history is
  ranked on price, ETA and availability — neither penalised nor flattered. Doc 07
  §6 falls out of the same rule rather than being bolted on.
- **Thin history is ignored, not weighted down.** Below
  `ranking.explain.minOrdersForTrust` the signals are dropped entirely: a 100%
  fill rate over three deliveries is noise, and ranking on noise is worse than
  ranking on price alone.

The same rule covers an unknown ETA, which happens when a store has not been
geocoded. Absent, not slow — otherwise a data-entry gap buries a good supplier.

`SupplierPerformanceProvider` is a port whose only implementation today reports no
history. Phase 8 adds an order-derived one; nothing in the scorer changes, because
it already handles absence correctly rather than acquiring that behaviour later.

---

## D-015 — A requirement is credited on acceptance, never on submission
**2026-09-14 · Settled**

Guardrail 14 forbids silently dropping unmet requirement quantities, and doc 01
§9–10 require the shortfall from a rejection, timeout or partial acceptance to
stay sourceable without the restaurant retyping anything.

**Decision:** `requirement_item.fulfilled_quantity` increases only when a supplier
**accepts**. Placing an order changes the requirement's status to `SOURCING` and
credits nothing.

**Why:** placing an order is a hope, not a fulfilment. Decrementing on submission
is the obvious implementation and quietly loses the need — a rejected order would
leave the requirement looking satisfied, and the shortfall would have to be
reconstructed by compensating on every failure path (rejection, timeout,
cancellation, partial acceptance, payment failure). Crediting on acceptance means
there is nothing to compensate: the failure paths do nothing at all.

Two consequences:

- `remaining_quantity` is derived (`requested − fulfilled`), never stored, so the
  header cannot drift from the lines.
- A `CHECK (fulfilled_quantity <= requested_quantity)` constraint enforces doc 10
  §3's invariant in the database rather than by discipline.

The requirements a procurement serves are derived from its **lines**, not from a
header field. A cart is built one offer at a time, each line linked to the need it
serves, and one cart can serve several requirements — a single `requirement_id`
would silently miss all but one.

---

## D-016 — A side effect that must survive its own exception needs its own bean
**2026-09-14 · Settled**

The same mistake has now produced four real bugs in this codebase, each of which
looked correct from the outside:

1. **OTP attempt counting** incremented inside the transaction that the
   `OTP_INVALID` throw rolled back. The counter never left zero, so the attempt
   limit was decorative and a six-digit code was open to exhaustive guessing.
2. **Refresh-token replay detection** revoked every session and then threw. The
   revocation was rolled back; the replay was rejected while the compromised
   session stayed live for another thirty days.
3. **Procurement submission failure** marked the order `FAILED` and then threw.
   The restaurant was told a supplier had gone offline while the order still read
   `READY`.
4. (And the same proxy rule, in a different guise) **`doSubmit` was
   `@Transactional` but self-invoked** from inside the idempotency lambda, so the
   whole submission — several supplier orders and their items — would have run
   with no transaction at all.

**The rule:** if a side effect has to outlive the exception that reports it, it
belongs in a `REQUIRES_NEW` method on a **separate bean**. Separate, because
Spring's `@Transactional` is proxy-based and a method invoked through `this` —
including from inside a lambda, which is easy to miss — never reaches the proxy
and the annotation is silently ignored.

The four such beans are `IdempotencyStore`, `OtpAttemptStore`,
`RefreshTokenStore` and `ProcurementStateStore`. Do not merge any of them into
their callers.

**And the testing rule that catches it:** assert the *side effect*, not the
rejection. Every one of these bugs passed a test that checked the error response.
They failed tests that checked whether the thing the error described had actually
happened.

---

## D-018 — A lost race is reported as what happened, not as a race
**2026-09-15 · Settled**

Doc 03 §5 and doc 10 §2 require acceptance and timeout to resolve to exactly one
outcome. Optimistic locking on `supplier_order` does that: both read the same
version, the second write throws.

The decision is what happens **next**. The natural handling is to let
`OptimisticLockingFailureException` surface as `CONCURRENT_MODIFICATION`, which is
accurate and useless — a supplier who accepted a moment too late learns nothing
they can act on, and their screen cannot render the expired state §23A.34 defines.

**Decision:** on a lock failure, re-read the order and throw the error matching the
outcome that actually won — `SUPPLIER_ORDER_EXPIRED`,
`SUPPLIER_ORDER_ALREADY_ACCEPTED`, or a cancellation. `CONCURRENT_MODIFICATION`
remains only for a genuinely unexplained conflict.

The re-read is deliberate: the entity in hand lost the race and is stale by
definition, so nothing on it can be trusted to describe the winner.

Related: **the deadline, not the timeout job, is the authority on expiry.**
`assertRespondable` refuses an acceptance past `acceptance_deadline` whether or
not the job has swept, so doc 13's "a supplier cannot accept an expired order" is
true at every instant rather than eventually. The job exists to move the state so
the restaurant is told and can source elsewhere.

---

## D-019 — Only performance signals that actually exist are reported
**2026-09-15 · Settled**

`OrderDerivedPerformanceProvider` replaces `NoHistoryPerformanceProvider` now that
orders exist. Nothing in `BestValueScorer` changed, which was the point of D-014.

It returns **acceptance rate** and **cancellation rate**, computed from supplier
orders. It leaves **fill rate**, **on-time rate** and **rating** empty, because
those need delivered quantities (Phase 12), delivery timestamps (Phase 11) and
ratings (Phase 12) — none of which exist yet. Approximating them from what is to
hand would be a fabrication with a plausible face, which doc 07 §4 forbids.

Two denominators worth knowing, because both are easy to get subtly wrong:

- **Acceptance rate counts only orders the supplier answered.** Including orders
  still pending would make a supplier's rate fall because an order arrived a
  second ago.
- **Cancellation rate is against orders they committed to**, not all orders.
  Against all orders, a supplier who rejects frequently would look *more* reliable,
  because rejections would dilute the denominator.

Computed on demand rather than materialised. A grouped count over an indexed
column is cheap at current volumes, and a materialised table is one more thing
that can be stale while a restaurant is looking at a ranking. Revisit when the
query is slow, not when the theory says it might be.

---

## D-020 — An order is funded before a supplier can see it
**Raised 2026-09-14 · Settled 2026-09-15** (was OPEN-004)

Doc 01 §14 and guardrail 16: a payment failure means the supplier never sees the
order. Until Phase 9 a submitted order carried `payment_status = PENDING` and
moved to `PENDING_ACCEPTANCE` anyway — a real gap in an unreleased system rather
than a design decision, which is why it was recorded as open.

**Decision: a supplier order is created `DRAFT` and released only once funding is
secured.** `ProcurementSubmitter` creates the orders, asks `OrderFundingPort` to
arrange funding, and returns a payment intent per order. `OrderReleaseService`
moves `DRAFT → PENDING_ACCEPTANCE` when the money is held — from the client's
confirm call, from the provider's webhook, or from the reconciliation job,
whichever arrives first. Payment failure abandons the order instead.

Three consequences worth stating, because each one is a thing that would
otherwise be got wrong later:

- **The acceptance deadline starts at release, not at submission.** Set at
  submission, it would run down while the customer was still typing a card
  number, and a supplier could be handed an order that had already expired.
  `OrderReleaseService` stamps `acceptance_deadline = now + responseSlaSeconds`
  at the moment the order becomes visible.
- **Funding means authorized, not captured.** `PaymentStatus.fundsSecured()` is
  the single predicate — `AUTHORIZED`, `CAPTURE_PENDING` or `CAPTURED` — and
  nothing else may decide it. The money is only taken once the supplier accepts,
  and only for what they accepted; the remainder of a partial acceptance is
  *released*, not refunded, so nothing appears on the restaurant's statement.
- **`CREDIT` is refused, not quietly allowed.** An unfunded credit order would be
  the same guardrail-16 violation this decision exists to remove, so submission
  rejects it with `CREDIT_AGREEMENT_NOT_ACTIVE` until credit funding lands in
  Phase 10.

`ProcurementIT$Submission.splitsBySupplier` now asserts the orders come back
`DRAFT` with no deadline and acquire both only after payment, and
`PaymentFlowIT$FundingGate` asserts the supplier's inbox is empty until then.

---

## D-021 — A webhook's three steps commit separately
**2026-09-15 · Settled**

`PaymentWebhookService.handle` is deliberately **not** `@Transactional`. It
stores the event, reconciles the payment, then writes back the outcome, and each
step commits on its own. Two failures forced this, both found by
`PaymentFlowIT$Recovery` and both returning **500 to the provider** — which means
the provider retries an event we already handled, forever.

- **A duplicate poisoned the caller's transaction.** `uk_webhook_provider_event`
  rejects a retry, and in MySQL a constraint violation marks the whole
  transaction rollback-only. Catching the exception looked like it worked; the
  commit afterwards threw `UnexpectedRollbackException`. The insert now happens
  in `PaymentWebhookStore` under `REQUIRES_NEW` — and, importantly, the
  exception is **thrown out of that method rather than caught inside it**, since
  a catch inside cannot unmark a transaction that is already doomed.
- **Writing the outcome deadlocked against the payment.** With one enclosing
  transaction, the store's `REQUIRES_NEW` write of the event's result waited on
  the `payment` row that same transaction had just locked and not yet committed.
  The provider got its 500 fifty seconds later, when InnoDB's lock wait expired.

This is the D-016 rule again, with a second edge: a side effect that must survive
its caller needs its own bean *and* its caller must not be holding locks it wants.
`PaymentJobs.reconcileStale` reaches the same state through the same calls with
no enclosing transaction, which is the shape to copy.

---

## D-022 — A credit request and its agreement are created together
**2026-09-15 · Settled**

Doc 02 §3 lists `credit_request` and `credit_agreement` as separate tables, but
doc 04 §13 puts the approval on the *agreement* (`POST /credit/agreements/{id}/approve`)
while creation is on the *request* (`POST /credit/requests`). Something has to
reconcile the two.

**Decision: asking creates both.** Doc 03 §8 starts the agreement's lifecycle at
`REQUESTED`, so the agreement is the subject of the request rather than its
result. `credit_request` records what was asked and what was said back, over what
may be several rounds; `credit_agreement` is the commercial instrument those
rounds are about.

**Why it matters later:** `uk_credit_agreement_pair` means one live credit line
per (outlet, supplier store). Without that, a second request would create a second
limit, and the restaurant's exposure would be the sum of two numbers nobody
separately agreed to.

---

## D-023 — Terms the supplier changed are not credit until the restaurant accepts
**2026-09-15 · Settled**

Doc 03 §8 has `APPROVED → ACTIVE` as its own transition and doc 05 §20 lists
"approved with modified terms" as a status distinct from "approved". Both imply a
step between the supplier's answer and usable credit, and someone has to take it.

**Decision: approving *as asked* activates immediately; changing any term leaves
the agreement `APPROVED` until the restaurant accepts.** `CreditAgreementStatus.canFund()`
is true only for `ACTIVE`, so an unaccepted modification funds nothing.

**Why:** a supplier who halves the limit and halves the period has made a
different offer, and the restaurant may not want it. Activating it for them means
orders get placed against an arrangement nobody agreed to — and the first the
restaurant hears of the new terms is an invoice due a fortnight early. Doc 10 §1's
seventh scenario is "credit request → **modified approval** → reserve → …", which
only has a path through it if the modification can be accepted.

---

## D-024 — A credit limit cannot be cut below what is already committed
**2026-09-15 · Settled**

Doc 01 §18 lets a supplier adjust a limit, and also says `available` cannot go
negative. Doc 10 §3 states the identity `approved = reserved + utilized +
available`. A limit below current exposure cannot satisfy both.

**Decision: the floor is `reserved + utilized`, and the error names it.** A
supplier can cut to exactly what they have already extended, and no further.

**Why:** reservations and utilization are credit already extended — an order a
restaurant has placed and a supplier may already be packing. The alternatives are
worse in both directions: clamping `available` to zero and letting the limit go
lower breaks the identity, so the four numbers on the restaurant's Credit Overview
stop adding up; clawing the reservation back cancels an order the supplier
themselves accepted.

A supplier who wants to stop lending immediately wants **suspension**, which
exists and does exactly that: no new orders, commitments untouched. The error
message says so, because the supplier's actual intent is nearly always that.

---

## D-025 — Exposure moves by conditional UPDATE, never read-modify-write
**2026-09-15 · Settled**

`reserved_amount` and `utilized_amount` are written only by `CreditExposureStore`,
and every method there is a single UPDATE that re-checks its own precondition in
the same statement — `… where status = 'ACTIVE' and approved_limit - reserved_amount
- utilized_amount >= ?`.

**Why not the obvious implementation?** Load the agreement, compute `available`,
compare, add to `reserved`, save. Two concurrent orders for 60% of the limit both
read the same row, both find the whole limit available, and both save. `@Version`
catches it — the outcome is correct — but the loser is told
`CONCURRENT_MODIFICATION`, which by D-018 is the wrong thing to say: the
restaurant's actual problem is that there is not enough credit, and that is what
they can act on. With the conditional UPDATE, InnoDB serialises the two statements
and the second one's `WHERE` clause simply does not match, so the caller can
report the real reason.

The status check is in the same `WHERE` clause for the same reason: an agreement
suspended between a caller's read and its write must not be drawn on, and the only
way to be sure is to make the suspension and the reservation contend for one row.

The database also carries `ck_credit_available`. The UPDATE makes the failure
*informative*; the CHECK makes it *impossible*, including for code written later
that forgets this class exists.

---

## D-026 — One delivery per order, for the life of that order
**2026-09-15 · Settled**

Doc 06 §7 says a reassignment "must not create a second logical delivery", and
doc 03 §10 repeats it. Everything about the delivery schema follows from taking
that literally.

**Decision: `uk_delivery_order`, and every failure appends rather than replaces.**
A driver cancelling, a provider refusing, a pickup going wrong — each writes a
`delivery_provider_attempt` and a `delivery_event`, increments
`delivery.attempt_count`, and leaves the delivery the restaurant is watching
exactly where it was.

**Why:** a second row would show two journeys for one consignment on the tracking
screen, and it would make "how often do deliveries fail" unanswerable — the
retries would be counted as separate deliveries, one failed and one succeeded,
rather than as one delivery that took two goes. The attempt table is what makes
the retry visible without splitting the thing being retried.

---

## D-027 — Provider bidding never reaches a restaurant
**2026-09-15 · Settled**

Doc 06 §4: "internal provider quotes are never shown to restaurant". Doc 06 §10:
"restaurant sees only final applicable fee".

**Decision: there is no DTO for `delivery_quote` and no endpoint returns one.**
`DeliveryResponse` carries a fee and deliberately carries no `providerCode`. The
test asserts this against the **serialised response body**, not against the DTO's
type — a field added later would pass a type-level check and still leak.

**Why:** who bid what is our commercial position and the couriers'. An API that
returns it hands a supplier's and a provider's pricing to everyone who places an
order. The fee the restaurant pays is the fact they need; the auction behind it
is not.

Failed and declined quotes are still *stored*, because doc 06 §12 requires the
quoting to be reconstructable — and because without them a delivery that fell
back to the only courier left looks like a choice somebody made.

---

## D-028 — On a partner delivery, only the partner's events move the order
**2026-09-15 · Settled**

`SupplierOrderStatus` gives a supplier no transition past `READY_FOR_PICKUP`, so
`OUT_FOR_DELIVERY` and `DELIVERED` cannot be set by any human. `DeliveryOrderBridge`
sets them from the courier's `PICKED_UP` and `DELIVERED` events.

**Why:** §23A.38 is explicit that a supplier must not claim a pickup or a delivery
a courier performed. "Just mark it delivered" is the shortcut that turns a
delivery record into an assertion nobody checked, and it is the one that would
make receiving disputes unanswerable.

**Supplier own delivery is the exception, and is a different mode.** There the
supplier *is* the courier, so they report their own progress through
`/deliveries/{id}/dispatched` and `/delivered`, and those endpoints refuse a
`COSTONOMY` delivery outright. Two modes, two sources of truth, no overlap.

The bridge writes across a module edge with a guarded `UPDATE` rather than
importing procurement's aggregate — the same boundary rule as the directories,
and the guard (`where status = ?`) means a replayed event cannot skip a state.

---

## D-029 — Two mock delivery providers, not one
**2026-09-15 · Settled**

Doc 06 §11 requires a mock provider. We register **two**, differing the way real
couriers do: one faster, dearer and wider-ranging, one cheaper, slower and with a
smaller service area.

**Why:** doc 06 §4's rule is "lowest cost meeting the required ETA and
serviceability". With a single candidate every selection strategy produces the
same answer, so a selection bug — picking the dearest, ignoring the ETA, ignoring
serviceability — is invisible until a second real provider is added in
production. Two mocks make the rule testable, and make failover testable too: one
can be armed to fail while the other still answers.

`DeliverySelection` is pure and static for the same reason — the rule can be
tested without a database, a provider or a delivery, including the tie-breaks that
make selection deterministic rather than "either answer is fine".

---

## D-030 — Sequencing writes to one aggregate needs one transaction
**2026-09-15 · Settled**

A simulated provider event touches a delivery several times: the driver, the
status, the position, the ETA. Each of those is a `@Transactional` method on
`DeliveryEventService` taking the `Delivery` entity.

Called in sequence from a **controller with no transaction**, each call
re-attaches a *stale detached copy*: the second write is built on the entity as it
was before the first, and silently reverts it. The delivery never left
`PROVIDER_SELECTED`, so nothing downstream applied — five tests failed, none of
them about the thing that was broken.

**Decision: that sequencing lives in `DeliverySimulationService`, under one
transaction, with one managed entity.**

This is a cousin of D-025's lesson and the ledger's stale-read bug, and the
general rule is worth stating once: **when several writes to one aggregate have to
compose, they belong inside a single transaction**, not strung together by a
caller. The polling job was always correct because it is `@Transactional`; the
controller was not, and nothing in the type system said so.

---

## D-031 — Realtime is a projection of the outbox, not a second publisher
**2026-09-15 · Settled**

`RealtimeEventRelay` listens to the outbox's `DomainEventEnvelope` and projects
each event onto the channels it concerns. No service calls a `broadcast(...)`
method.

**Why:** every state change already publishes to the outbox — that is what it is
for. A parallel publishing call in procurement, payment, credit and delivery
would be a second mechanism to keep in step with the first, and it would be the
one people forget: a new event type would simply never reach a phone, with
nothing failing to say so.

Two properties come free. Realtime inherits the outbox's **transactional
guarantee** — an event exists only if the state change committed, so a client
cannot be shown a rolled-back order. And it inherits **at-least-once** delivery,
which is why `realtime_event` is deduplicated on `(event_id, channel)`.

The projection is per **(event, channel)**, not per event: a supplier order
concerns the restaurant that placed it and the store filling it, and each sees it
on their own channel.

---

## D-032 — All three transports read the same rows
**2026-09-15 · Settled**

Doc 06 §9 wants WebSocket, push and polling. `realtime_event.id` is the cursor
all of them share: the socket pushes rows, `GET /realtime/events?cursor=` walks
them, and a reconnecting client resumes from the last id it saw.

**Why:** the alternative is a socket that carries something polling cannot
produce — a fact that exists only while you are connected. Doc 05 §16 requires a
client to refresh authoritative state on reconnect and cold start, which is only
possible if the two agree on what it missed.

Consequences worth stating:

- **There is no channel parameter on the polling endpoint.** A caller asks for
  "my events"; what that means is the server's decision, derived from grants. The
  same reason `/auth/me` takes no user id.
- **A fresh client starts at the current cursor, not at zero.** Opening the app
  should not replay a week the user already saw elsewhere.
- **Events expire (7 days) and that is safe.** A client past the window gets
  nothing from its cursor and refreshes state instead — which doc 05 §16 has it
  doing on cold start anyway. Realtime is a prompt to refresh, never the record.

---

## D-033 — The socket is authenticated by a single-use ticket
**2026-09-15 · Settled**

`POST /realtime/ticket` returns a short-lived, single-use credential; the
handshake spends it atomically and derives the session's channels from live
grants.

**Why not the access token?** A browser's WebSocket API cannot set headers, so a
token would have to travel in the query string — and query strings end up in
access logs, proxy logs and error reports. Doc 09 forbids logging a token, and a
URL is the one place that promise cannot be kept. A ticket that lives thirty
seconds and works once is a far smaller thing to lose.

The rest follows: stored **hashed** like a refresh token, so the table is not a
list of working credentials; claimed by an **atomic conditional UPDATE** in
`RealtimeTicketStore`, because a read-then-write would let two simultaneous
handshakes with one stolen ticket both succeed; and spent, expired and
never-issued all give the **same answer**, so a caller cannot probe which.

**Channels are never requested, only derived.** A client cannot ask to join
`outlet:99`; the server decides from grants at handshake time and re-checks
membership on every delivery. That removes the entire class of bug where a client
asks for someone else's channel and the check has a hole in it — and the
re-derivation is what makes doc 46's "revocation takes effect on the next
request" true of the longest-lived connection in the system.

---

## D-034 — Redis carries the cross-instance hop, and only an id
**2026-09-15 · Settled**

`RealtimeBroadcaster` has two implementations: `LOCAL` (default) fans out within
the JVM, `REDIS` publishes over pub/sub.

**Why it is not optional on more than one instance:** the outbox drain runs under
a `@SchedulerLock`, so exactly one instance produces events — and it is
emphatically not the instance holding most of the sockets. Without a hop,
realtime would work perfectly in development and deliver to a fraction of users
in production, with nothing failing.

**Only the event id crosses Redis.** Each instance re-reads the row from
`realtime_event` before delivering, so Redis never carries tenant data and a
message lost in transit costs nothing — the durable copy is in MySQL and the
client's cursor will find it. That keeps Redis inside guardrail 6: cache,
coordination and hints, never the record. Redis being down degrades realtime to
polling, which is the designed fallback rather than an outage.

---

## D-035 — Receiving adds to the order; it never rewrites it
**2026-09-15 · Settled**

Doc 03 §11: "receiving does not rewrite the supplier order to erase delivered
quantities". Taken literally: `accepted_quantity` stays exactly as the supplier
committed to it, and what arrived is written to `fulfilled_quantity` — the column
V10 left null for this moment.

**Why:** the accepted quantity is what was paid for and what every dispute is
argued from. Overwriting it would leave a restaurant complaining about a shortfall
against an order that no longer records the larger number they were promised.

**The three quantities partition the accepted one**:
`received + damaged + missing = accepted`, and a mismatch is refused with the
arithmetic in the message. Two consequences, both deliberate:

- **No blind completion.** §23A.22 forbids a "Complete" button that records a
  perfect delivery nobody counted; requiring all three numbers on every line is
  what actually prevents it, and defaulting any of them would reinstate it.
- **Over-delivery is refused, not absorbed.** The restaurant paid for the accepted
  quantity. Quietly recording twelve when ten were bought puts stock on the books
  that nobody priced. The usual cause is a typo, which the message says.

**A shortfall does not re-open the requirement.** Guardrail 14 credited it on
acceptance; re-opening on a receiving discrepancy would have the restaurant order
the same goods twice while a dispute about the first lot is still running. The
shortfall is a commercial dispute, which is what disputes are for.

---

## D-036 — A dispute never touches the order
**2026-09-15 · Settled**

Doc 01 §22 and doc 03 §12. Nothing in `DisputeService` changes a supplier order's
status, quantities or payment, and `DisputeResponse` carries
`supplierOrderStatus` so the app can state that plainly — §23A.26 requires it to.

**Why:** an order status that moved on a complaint would make a restaurant's own
record of what arrived depend on whether they complained about it. They would be
choosing between having the delivery recorded and disputing it.

Two smaller decisions inside this one:

- **Several disputes per order.** A delivery can be short *and* damaged, and doc
  01 §23 lists seven distinct categories. One dispute per order would force a
  restaurant to pick which problem to report.
- **A supplier's response does not close a dispute.** They can answer and propose
  a resolution; only the restaurant resolves, and only the supplier rejects.
  Letting a supplier close it by replying would end a conversation the other party
  has not agreed to.

**Mandi records; it does not adjudicate.** Doc 01 §23: disputes exist for
intelligence and audit. A resolution is what the two parties agreed, written down.
No money moves here, and nothing in this module issues a refund or a credit note
on anyone's behalf.

---

## D-037 — Ratings are published on write and removed by moderation
**2026-09-15 · Settled**

Doc 01 §24: "ratings are public to the marketplace subject to moderation". Read as
moderation that **removes**, not moderation that **gates**: a rating is visible the
moment it is written, and `RATING_MODERATE` can hide it afterwards with a reason
and an audit entry.

**Why:** pre-moderation means no rating appears until someone reviews it. A
marketplace whose ratings lag by a working day effectively has none, and the
supplier whose rating is held in a queue is penalised for their reviewer's
backlog rather than for anything they did.

Hiding a rating removes it from the public average **and from ranking** — the
summary and `OrderDerivedPerformanceProvider` both read `PUBLISHED` only. Without
that, moderation would be cosmetic.

**An absent rating stays absent.** A store nobody has rated has no average, not a
default of three (doc 07 §4), and a dimension left blank is excluded from that
dimension's mean rather than counted as neutral — one half-filled form should not
drag a store's packaging score toward the middle without anyone having said
anything about packaging.

---

## D-038 — Two permissions the spec's list omits
**2026-09-15 · Settled**

Doc 04 §16 requires `POST /disputes/{id}/response` and doc 09 §9 requires rating
moderation, but doc 03 §14's permission list contains neither. V15 adds
`DISPUTE_RESPOND` (supplier world) and `RATING_MODERATE` (internal).

**Why not reuse a neighbour?** The tempting shortcuts are both bad. Gating the
supplier's write behind `ORDER_VIEW` puts a write behind a read permission, which
is how an authorization model becomes impossible to reason about. And
`DISPUTE_MODERATE` is an *internal* permission — `PermissionCatalogIT` enforces
that no supplier role holds one, and reusing it would have broken that invariant
rather than bent it.

The seeded grants follow the existing shape: supplier roles that already own
orders can answer for them, and moderation goes to the roles that already
moderate.

---

## D-039 — The last three performance signals become real
**2026-09-15 · Settled** (closes the gap left by D-019)

`OrderDerivedPerformanceProvider` returned `Optional.empty()` for fill rate,
on-time rate and rating because the data did not exist. It does now, and all
three are computed — with **no change to `BestValueScorer`**, which is exactly
what D-014's weight redistribution was for.

Each denominator is chosen to avoid a plausible-looking lie:

- **Fill rate** is received ÷ accepted, over lines that have actually been checked
  in. Damaged stock arrived but is not usable, so it does not count as filled —
  otherwise a supplier with a packing problem looks identical to one without. A
  line with no `fulfilled_quantity` is excluded rather than counted as zero, or a
  supplier's rate would fall while their van is still on the road. Over-delivery
  is capped at 1.0, because above that the number stops meaning "share filled".
- **On-time** is measured against `estimated_arrival_at` — the courier's own
  estimate at booking, which is the number the restaurant was shown. Grading
  against a figure we computed ourselves would score a supplier on a promise
  nobody made to anybody. Own-delivery consignments are excluded: doc 06 §2 says
  Costonomy measures no provider SLA there.
- **Rating** is the mean of published ratings only.

All three stay empty where the denominator is zero. Doc 07 §4's rule has not
moved: a store with no deliveries has no fill rate, not a perfect one.

---

## D-040 — Notification rules are a catalogue, not calls
**2026-09-15 · Settled**

`NotificationRules` is a table of `(event type → audience, category, criticality,
channels, template)`. No service calls a `notify(…)` method. `NotificationRelay`
listens to the outbox, the same shape as the realtime relay (D-031) and for the
same reasons.

**Why data rather than calls:** the interesting question about notifications is
always "who gets told what", and it should be answerable by reading one file
rather than searching five modules for `notify(`. A per-service call is also the
one people forget — a new event type would simply never reach anybody, with
nothing failing to say so.

**Bodies are rendered from named fields, never from the payload.** Doc 08 §8
forbids logging an OTP, a card number or a provider secret, and a notification
goes further than a log: it lands on a lock screen and is mirrored to a watch.
A template that asks for `{orderNumber}` can only ever contain an order number;
interpolating a payload wholesale would make that a matter of hoping no producer
ever adds the wrong field. `unnamedFieldsCannotLeak` asserts it.

**Not every domain event is a notification.** Doc 08 §1 lists forty events for the
outbox; the catalogue maps the ones a person must act on. `DeliveryLocationUpdated`
arrives every few seconds and belongs on a map — pushing it would be the fastest
way to get the app's notifications turned off entirely.

---

## D-041 — Critical is doc 08 §4's list, and it overrides preferences
**2026-09-15 · Settled**

Doc 08 §5: "critical operational/financial notifications may be mandatory
according to policy". The policy here is that they are. `NotificationPreferences.allows`
returns true for a critical notification without reading a preference.

**Why:** a supplier who muted order notifications still needs to know an order is
counting down against them. The alternative is an order that expires beside a
silent phone and a restaurant that gets nothing — and the supplier did not intend
either when they turned off a toggle. The mute still applies to everything
non-critical in the same category.

**Preferences are opt-out.** A row exists only where something was turned off, so
absent means enabled and a new category or new user starts receiving. Opt-in fails
quietly and badly: a restaurant who never learns their order was rejected and
never knew there was a setting.

**SMS is narrower still**, and `smsIsRare` pins the list: order rejected, order
expired, payment failed, credit overdue. Each is a case where someone must act
today and a push may never be seen. An SMS for every status change trains people
to ignore SMS, which costs us the one that matters. `smsImpliesCritical` enforces
the converse — if it is worth an SMS it is worth being un-mutable, and if it is
mutable it is not worth an SMS.

---

## D-042 — In-app and outbound delivery are different lifecycles
**2026-09-15 · Settled**

`notification` is the inbox row; `notification_delivery` is one attempt to get it
to a device or a phone number, carrying doc 08 §6's
`CREATED → QUEUED → SENT → DELIVERED` with bounded backoff.

**Why not one table:** "did they read it" and "did the network take it" are
different questions, and collapsing them makes both unanswerable. It also implies
tracking whether we successfully wrote to our own database, which is what an
IN_APP delivery row would be.

Three consequences, each a way this goes wrong quietly:

- **SENT and DELIVERED are different facts.** SENT is "the provider accepted it";
  DELIVERED is "the device acknowledged it", which only some providers report.
  Treating acceptance as delivery makes every dashboard show perfect delivery
  regardless of what reached a phone.
- **A permanent failure is not retried.** An unregistered push token belongs to an
  uninstalled app; retrying it every minute for a day fills the queue with
  messages for phones that no longer exist and buries the transient failures that
  would have succeeded.
- **A failed send does not unsend the notification.** The inbox is the durable
  channel and push is best-effort on top. A dead token is not a reason to pretend
  nothing happened — and a user with no registered device gets no delivery row at
  all, rather than a permanent failure against a phone that does not exist.

---

## D-043 — Analytics strips secrets server-side
**2026-09-15 · Settled**

`AnalyticsService` drops any property whose **name** matches a forbidden fragment
(`otp`, `card`, `cvv`, `token`, `secret`, `auth`, …), caps property count and
value length, and discards nested structures.

**Why not trust the client:** doc 08 §8 forbids storing an OTP, a card number, a
CVV or a provider credential, and the client is the wrong place to enforce it. One
debugging property added in a hurry, or a third-party SDK that helpfully attaches
form state, and a card number is in a database that was never meant to hold one.
The rule is blunt and fails safe: a legitimately-named property being dropped
costs one analytics field; the opposite costs a compliance incident.

Nested objects are discarded rather than flattened, because an analytics table is
the easiest place in a system to accidentally store an entire object graph —
including the fields nobody audited.

Ingest is idempotent on the client's event id, and reports what it dropped. A
double-counted event quietly inflates every funnel metric doc 08 §9 is built from,
and a client that is double-sending deserves to be able to find out.

---

## D-044 — Event names are owned by the enum that raises them
**2026-09-15 · Settled**

`DeliveryStatus.eventName()` and `DisputeStatus.eventName()` name the domain event
each status raises. Callers ask; nobody derives a name inline.

**Why this became a decision:** writing the notification catalogue — the first
consumer that matches on event names — surfaced that delivery was publishing
`DeliveryDRIVER_ASSIGNED` (from `"Delivery" + name()`) in one path and
`DeliveryDriverAssigned` in another, and disputes were publishing
`DisputeRESOLVED`. Doc 08 §1 specifies `DriverAssigned`, `DeliveryDelivered` and
`DisputeResolved`. Three spellings of one event, none of them the documented one,
and a consumer matching the contract would have silently received nothing.

**An event name is a contract**, read by notifications, realtime and analytics. It
belongs somewhere single and testable, not assembled at each call site.

The same exercise found that **approval events were not published at all** —
doc 08 §1 lists `ProcurementApproved` and `ProcurementRejected`, and doc 08 §4
makes approval requests critical. A cart was waiting for an approver who was never
told, silently on both sides. `ProcurementApprovalRequested`, `ProcurementApproved`
and `ProcurementRejected` are now published.

Writing a consumer is the cheapest audit of a producer there is.

---

## D-045 — Operations gets its own permissions, not the tenant's
**2026-09-15 · Settled** (closes the note V5 left for this phase)

V5 granted `ORDER_VIEW` and `CREDIT_VIEW` to the OPS roles. Both are SHARED rather
than tenant permissions, so `PermissionCatalogIT` was satisfied — but a
PLATFORM-scoped grant satisfies a check at *any* outlet or store, so an operator
could read and act through the restaurant's and the supplier's own endpoints.

**Decision: V17 takes those grants off the OPS roles and adds INTERNAL inspection
permissions** — `SUPPLIER_INSPECT`, `ORDER_INSPECT`, `PAYMENT_INSPECT`,
`DELIVERY_INSPECT`, `DISPUTE_INSPECT`, `CONFIG_VIEW`. Operations reads through
`/api/v1/admin/**`, and no tenant permission appears anywhere in the admin module.

**Why it matters more than it looks:** doc 09 §17 says operations is a separate
consumer and the APIs should serve a future Operations web app "without changing
domain rules". An operator arriving through a tenant endpoint is subject to tenant
rules, gets tenant shapes, and is indistinguishable in the audit trail from the
restaurant itself. The support agent who "just looked at the order" and the
restaurant that looked at it should not be the same event.

The change had teeth: the delivery simulation endpoint read its result back
through the tenant endpoint and stopped working, which is exactly the shortcut
this removes. It now reads through the operations view.

---

## D-046 — Inspection and mutation are separate permissions
**2026-09-15 · Settled**

Doc 09 §13: "support users may inspect records without receiving unrestricted
mutation rights. Separate read and write permissions."

Until V17 there was no way to honour that. `DELIVERY_OPERATE` and
`PAYMENT_RECONCILE` are mutations, and they were the only permissions that
mentioned deliveries and payments — so granting someone the ability to *look* at a
delivery granted the ability to reassign it.

**Each mutation permission now has a read-only counterpart**, and `OPS_SUPPORT`
holds the whole inspection surface and none of the writes. `supportInspectsOnly`
asserts both halves in one test, because the property is only interesting as a
pair: a read that works and a write that does not.

Specialist roles stay narrow for the same reason — a delivery operator can inspect
deliveries and orders, and gets 403 on payments and credit. There is no operational
need for the person chasing a courier to read a credit ledger.

---

## D-047 — A configuration change supersedes; it never overwrites
**2026-09-15 · Settled**

`AdminConfigService.update` closes the current `app_config` row with an
`effective_to` and inserts a new version. Doc 09 §10: versioned, audited, and
effective-dated where financially relevant.

**Why:** doc 09 §11 requires settlement to be reproducible and commission to be
snapshotted into each calculation. Both are impossible if the rate that applied in
March can be edited in June. It is the same rule as D-012's "a price is never
edited, only superseded", applied to policy rather than to price.

Two smaller choices inside it:

- **An unknown key is refused, not created.** A typo would otherwise become a
  configuration value nothing reads, while the setting the operator meant to
  change stays exactly as it was — and they would have no reason to think it had
  not worked.
- **The cache is refreshed on change.** A configuration value that takes effect at
  the next restart has not changed.

---

## D-048 — Operations changes what is possible, never what a party decided
**2026-09-15 · Settled**

The line the admin module does not cross. Suspension stops new trade; moderation
hides content; configuration changes policy. Nothing in `AdminModerationService`
approves an order, accepts on a supplier's behalf, or moves money.

Three consequences:

- **Suspension is forward-looking.** A supplier suspended today still owes the
  deliveries they accepted yesterday; cancelling those would punish the
  restaurants rather than the supplier.
- **Disabling a SKU supersedes its offer rather than deleting it.** Doc 02 §4: an
  order placed last week was placed at a price, and deleting the SKU would make
  that order unreconstructable.
- **An operator resolving a dispute records an outcome, it does not impose one.**
  Doc 01 §23 is unchanged by operations being involved: Mandi does not move money
  between a restaurant and a supplier. The operator's note is stored as an
  internal message neither party sees (§23A.32); the resolution is what they read.

Every mutation requires a reason and is audited. An unexplained suspension is
indistinguishable from a mistake, and the supplier asking why deserves an answer
that exists.

---

## D-049 — An operational dashboard shows a dash, not a flattering number
**2026-09-15 · Settled**

Every rate in `OperationsDashboard` is null where its denominator is zero — the
same rule the ranking signals follow (doc 07 §4, D-039), and it matters more here
because a dashboard is read at a glance.

A fresh environment showing 100% acceptance and 100% on-time because nothing has
happened is worse than one showing a dash: an operator who learns to discount the
green numbers will discount the real ones too.

**Counts are absolute; rates are windowed.** "How many orders are in flight" is a
question about now. "What share were accepted" is meaningless without a period,
and a lifetime average hides this week entirely — which is the week an operations
dashboard exists to show.

---

## D-050 — Rate limits are per endpoint, per caller, and configurable to zero
**2026-09-15 · Settled**

Doc 09 §14 names seven things to limit: OTP request, OTP verification, login,
search, payment initiation, webhooks and admin mutations. `RateLimitPolicies` gives
each its own policy rather than applying one global limit.

**Why per endpoint:** the right number differs by two orders of magnitude between
them. A provider retrying a burst of webhooks and a script guessing six-digit OTPs
look identical to a single counter, and any limit low enough to stop the second
would break the first.

**Why per caller, and why the key differs:** an unauthenticated endpoint has no
user to key by, and an authenticated one keyed by IP throttles an entire
restaurant behind one office router for one person's enthusiasm. So pre-auth
endpoints key by IP and authenticated ones by user, falling back to IP when there
is no principal — without that fallback, anonymous traffic to a user-keyed
endpoint would share one bucket and one script could lock it for everyone.

**Why zero means off:** the test suite creates hundreds of users from one address,
and a suite throttled by its own fixtures tests the fixtures. More importantly, a
limit that cannot be tuned without a deploy is a limit that gets deleted the first
time it fires at the wrong moment. `RateLimitIT` turns them back on for itself with
`@TestPropertySource`, at the cost of a second application context — the price of
testing a cross-cutting concern honestly rather than around it.

**A 429 carries `Retry-After`.** A client without it can only guess, and one that
guesses wrong retries immediately and makes the problem worse.

**`X-Forwarded-For` is honoured, and that is a deliberate trade.** Behind a proxy
`getRemoteAddr` is the proxy — one key for the entire internet. Deployed *without*
a proxy that overwrites the header, a caller can change their own rate-limit key at
will. This is one layer among several: OTP attempt counting, idempotency and
authorization do not depend on it being unspoofable.

---

## D-051 — Redis is required for rate limiting on more than one instance
**2026-09-15 · Settled**

`InMemoryRateLimiter` is the default and counts in this JVM. `RedisRateLimiter`
counts in Redis, behind `costonomy.mp.ratelimit.backend=REDIS`.

**Why it is not optional at scale:** each instance counting separately means a
three-instance deployment enforces three times the limit, silently and with
nothing failing. This is the same shape as D-034's realtime broadcaster, and the
same guardrail-6 use of Redis: coordination, never the record.

**Redis being down allows the request.** A rate limiter that refuses everything
when its counter is unreachable turns a cache outage into a total outage — a far
worse failure than briefly permitting more traffic than intended.

**A fixed window, not a sliding one.** One counter and one timestamp per key; its
worst case is a caller sending two windows' worth across a boundary. For an OTP
endpoint that is the difference between ten and twenty attempts an hour, which is
not the difference that matters. A sliding window costs a data structure per key
to close a gap this small — and the keys include client IPs, so per-key cost is
exactly what needs bounding.

---

## D-052 — The error contract is tested, not conventional
**2026-09-15 · Settled**

`ErrorContractTest` asserts that every code doc 04 §22 names exists, that each
carries the HTTP status the doc assigns it, and that no message leaks an internal
detail.

**Why a test:** an error code is the part of an API a client writes a switch
statement against. A renamed code, a status quietly changed from 409 to 422, or a
deleted one is a breaking change that compiles cleanly on both sides and is found
by a user. The HTTP status in particular is what retry logic keys on — a 409 is
worth retrying after a refresh, a 422 is not, and a 429 means wait — so swapping
two of them silently changes how every client behaves.

The message check exists because these strings are returned to clients verbatim
(doc 09 §16, §4): a stack trace, a table name or a provider's own wording in one
would be a disclosure that no amount of log redaction catches.

---

## D-053 — A commission rate is snapshotted, never re-read
**2026-09-15 · Settled**

`commission_calculation` copies the rate onto the row and the settlement sums the
stored figures. Nothing recalculates commission from `commission_configuration`
at read time.

**Why:** doc 05 §33 — "historical settlement must not depend on current commission
configuration" — and doc 09 §11's requirement that settlement be reproducible.
Recomputing would make every past figure a function of today's table: a statement
printed twice would disagree with itself the moment a rate was renegotiated, and
a settlement replayed months later would not be a settlement but an estimate.

It is the same rule as D-012 (a price is never edited, only superseded) and D-047
(a configuration change supersedes), applied to money leaving the platform.

The base is **accepted item value plus GST, excluding delivery** (doc 01 §16).
Delivery is subtracted explicitly even though it is currently zero on every order,
so the calculation stays correct if the fee is ever folded into the order total.
A partial acceptance owes commission on what was supplied, not what was ordered —
the supplier was not paid for the rest.

Rates resolve most-specific-first: store, then organisation, then the platform
default. A negotiated rate is a row, not a code change.

---

## D-054 — A settlement freezes at approval; corrections are the next settlement's
**2026-09-15 · Settled**

`SettlementStatus.isMutable()` is true only for PENDING and CALCULATED.
Adjustments after approval are refused, with a message saying where the correction
belongs.

**Why:** an approved payout is a commitment somebody signed off. Changing the
figure afterwards means the supplier's copy of the statement and ours stop
matching, and neither party can tell which is right. A correction raised against
the next settlement carries its own reason and leaves both records intact — the
same reasoning as doc 09 §11's "credit ledger must be append-only; corrections use
adjustment transactions".

**Approval is a human step and cannot be skipped** (doc 03 §13). A settlement is
money leaving the platform, and the gap between CALCULATED and APPROVED is where
somebody reads the number first. `SettlementJobs` deliberately stops at
CALCULATED: a job that walked past approval would let a calculation bug pay itself
out overnight.

**A failed payout returns to APPROVED, not to PENDING.** It has already been
calculated and already been approved; sending it to the start would recalculate
against whatever changed since and ask again for an approval already given.

---

## D-055 — Reconciliation records a mismatch rather than refusing
**2026-09-15 · Settled**

`SettlementReconciliationService` compares two independent records of the same
money — what the order records say a supplier is owed, and what the payment
records say restaurants actually paid (captured minus refunded) — and writes the
answer onto the settlement.

**A mismatch is recorded, not thrown.** Doc 03 §13 requires reconciliation to be
idempotent, and a discrepancy needs a human rather than a retry. Refusing to
complete would make one unexplained figure block every later run; recording it
surfaces the problem while the payout waits at APPROVED, which is the correct
place for money nobody has explained yet. The mismatch is logged at error and
written to the audit trail, because this is money.

**Idempotent by construction**: it recomputes from the same two sources and
overwrites its own last answer, so a settlement reconciled a hundred times looks
exactly like one reconciled once. It runs repeatedly on purpose — a captured total
can change after calculation when a refund lands or a delayed capture completes,
which is exactly the case worth catching.

---

## OPEN-005 — The delivery fee is never charged to the restaurant
**Raised 2026-09-15 · Not yet closed**

Doc 01 §20: "restaurant pays delivery by default". In the implementation
`supplier_order.delivery_fee` is set to zero at submission and `total_amount` is
`subtotal + gst`. The courier's fee is recorded on the `delivery` row when one is
booked (Phase 11) and is never added to the order total — so the payment that is
authorised and captured does not include it, and the restaurant is not charged.

Found while writing the commission base, which must *exclude* delivery: the
subtraction is a no-op today because the fee never reaches the order.

**Why it is not fixed here.** The fee is only known at booking, which happens
*after* the payment is authorised and, for a full acceptance, after it is
captured. Charging it correctly means either authorising an estimate at checkout
and capturing the actual amount later, or raising a second charge after delivery —
a payment-flow decision with its own idempotency and refund implications, not a
line to add to a total. Doing it hastily in the last phase would risk the
guarantees D-020 and D-010 were built to provide.

**What it affects if left:** restaurants are under-charged by the delivery fee on
Costonomy deliveries; supplier settlement and commission are unaffected, because
both are computed on the item value and would exclude the fee anyway. Own-delivery
orders are already correct — the supplier's own fee is theirs to set and is
usually zero.

---

## D-056 — The app asks the server which outlets a user has; it does not read them off memberships
**2026-09-15 · Settled**

`/auth/me` returns one membership per scope the user holds a *grant* in. A
restaurant owner therefore holds a single RESTAURANT membership and no OUTLET
memberships at all — `ScopeResolver` is explicit that "a grant at the parent
satisfies a check at the child — an owner does not need re-granting for every
outlet they open".

M1's home screen listed `memberships.filter(scopeType === 'OUTLET')` and so told
an owner they had no outlets, one screen after they had created one. Caught in the
browser, not in a test, because both sides were individually right.

**Decision: `useOutlets()` resolves the list against the server** — outlets are
fetched per restaurant the user holds a grant in, unioned with the outlets named
by any OUTLET-scope grant whose restaurant the user does *not* already hold. A
manager assigned to two of a chain's nine outlets gets exactly those two; an owner
gets all nine.

**Why not expand it into `/auth/me`.** A fifty-outlet chain would carry all fifty
rows on every session restore and every token refresh, and outlets change far more
often than grants do. `/auth/me` answers "what may this user do"; the outlet list
is data, and it caches and invalidates on its own schedule.

The old helpers are renamed `outletGrantsOf` / `storeGrantsOf` so the next reader
cannot mistake a grant list for a resource list. **The supplier side has the
identical shape** — a SUPPLIER grant covers its stores — and M4 must resolve
stores the same way rather than filtering for SUPPLIER_STORE.

---

## D-057 — A route group is guarded by its layout, not by the index route
**2026-09-15 · Settled**

Signing out cleared the tokens and left the user looking at the restaurant home,
now rendered empty. The redirect lived in `app/index.tsx`, which had already run;
nothing re-ran it, so the mounted screen simply stayed.

**Decision: `AuthGate` wraps each group's `_layout`.** A layout re-renders when the
session changes, so losing a session unmounts the group — and a deep link into a
screen inside the group is gated too, which an index-route redirect never sees
because it does not pass through `/`.

It also redirects an audience mismatch back to `/` rather than rendering, so the
answer to "which half of the app is this" stays in one place (`audienceOf`) as M2
adds routes.

**This is navigation, not security** — doc 09 §2: never rely on mobile route
visibility for security. The server authorises every call regardless of what the
client chose to render. The gate exists so a signed-out user is not left sitting
in a shell of someone's dashboard.

---

## D-058 — An expired access token is renewed by the API client, not by each screen
**2026-09-15 · Settled**

The session provider had a correct single-flight `renew()` from the first day.
Nothing but `/auth/me` ever called it. Every screen query passed its token
straight to `apiRequest`, so when the fifteen-minute access token expired each
query failed on its own and the app looked broken until it was force-quit —
found by leaving a browser tab open for twenty minutes, not by any test.

**Decision: `apiRequest` renews once on a 401 and repeats the call.** Safe for any
method, `POST` included: the server rejected the request at authentication and
never saw it, so there is nothing to have happened twice. The renewal does not
consume a retry attempt, because nothing was wrong with the request.

The client cannot call `useSession` and the provider cannot be imported by the
client without a cycle, so the provider registers its renewal function in
`lib/api/session-bridge` on mount. With nothing registered — a unit test, a call
made before the provider mounts — a 401 stays a 401, which is the right answer
rather than a hang.

**A failed renewal is still a 401.** It propagates, the session clears, and the
route guards send the user to sign in. Retrying into a wall would be worse than
the bug this fixes.

---

## D-059 — Mock checkout is completed through a gated endpoint, not skipped
**2026-09-15 · Settled**

`MockPaymentProvider.completeCheckout` was reachable only from Java. A real
checkout happens in the provider's hosted UI, which no mock has, so a local or
staging environment could reach the payment screen and stop dead: the order sat
in `DRAFT`, no supplier ever saw it, and acceptance, tracking and receiving were
unreachable by anything except the test suite.

**Decision: `POST /api/v1/internal/payments/{id}/simulate-checkout`**, following
`DeliverySimulationService`'s pattern — two gates, both in the service next to the
work they guard. The caller must hold `PAYMENT_CREATE` on the payment's own
outlet, so it grants nothing they could not already do; and the configured
provider must actually be a mock, which is what makes it safe to ship. Against a
real provider it refuses, so it cannot become a way to mark real money authorised.

**It stops at authorisation** and returns the provider payment id. The caller then
goes through the real `/payments/{id}/confirm`. Short-circuiting straight to a
confirmed payment would have left the one step that matters — the server asking
the provider what actually happened — exercised by nothing but the suite.

---

## D-060 — The client renders the server's ranking; it never re-sorts
**2026-09-15 · Settled**

The comparison screen (REST-SUP-01) receives offers from the recommendation feed
already ranked, and treats index 0 as the recommendation. It does not sort, filter
or re-weigh them.

Doc 07 specifies the ranking and tests it; a client-side sort would quietly
substitute a different one that nothing tests, and "cheapest first" is not the
same answer as the feed's — which weighs fill rate, reliability and whether a
supplier can cover the quantity at all. Guardrail 9 also has a sharper edge here:
the shape carries no commission field, and `explanationLabel` has no commission
label and must never gain one.

**Quantity is part of the question.** The feed is asked for the quantity on
screen, because `coversFullQuantity` is meaningless without one, and changing the
stepper re-asks rather than re-filtering what is already loaded.

---

## D-061 — A supplier order line has no pack; the model mirrors the DTO exactly
**2026-09-15 · Settled**

The mobile `SupplierOrderItem` was written from the shape of the *cart* line and
carried `quantity`, `packSize` and `packUnit`. `SupplierOrderItemResponse` has
none of those: it carries `requestedQuantity`, `acceptedQuantity`, `unit` and a
line `status`, and no pack at all. TypeScript could not catch it — the API is
`unknown` at the boundary — so the screen rendered the em-dash that
`formatQuantity` returns for a missing value, and the supplier's decision screen
showed "— KG" where the quantity they were agreeing to should be.

**Decision: every model in `models/` mirrors one DTO, field for field**, and is
written by reading that DTO rather than by analogy with a neighbouring one.

The difference is real, not incidental: a cart line is denominated in packs a
supplier sells, while an order line is denominated in the ordering unit, and the
pack belongs to the SKU rather than to the order. Copying the cart's shape across
was assuming the two were the same thing.

**What this costs if missed:** nothing fails. There is no error, no empty state,
no console warning — just a number quietly absent from the screen where a
supplier commits to a quantity. Worth a browser pass on any screen whose model
was not read straight from its DTO.

---

## D-062 — An unfunded order says its payment did not complete
**2026-09-15 · Settled**

A supplier order in `DRAFT` never reached a supplier: its payment did not
complete, which is exactly what guardrail 16 and D-020 intend. The restaurant app
was listing those under "Active orders" with the chip "Draft".

Both halves were wrong. Nobody is working on the order, so it is not active; and
"Draft" describes a database row rather than telling a restaurant why their order
is going nowhere.

**Decision:** the chip reads **"Payment incomplete"**, and the order appears under
Pending rather than Active.

**It is not hidden.** An order a restaurant tried to place and that then silently
vanished is worse than one labelled honestly — they would place it again, having
been told nothing.

---

## D-063 — An applied delivery event writes one timeline row, not two
**2026-09-15 · Settled**

`DeliveryEventService.apply()` built a `DeliveryEvent`, wrote it through
`eventStore.record()` with the provider's event id and its disposition, and then
— at the end of the same method — called `timeline.record()`, which wrote a
*second* row for the same event: same type, same status, no provider id.

Both rows are marked `APPLIED`, and the timeline read returns everything marked
`APPLIED`. So a restaurant watching a delivery saw every step twice: driver
assigned, driver assigned, picked up, picked up.

**Decision: `apply()` publishes, it does not record.** The ledger row is already
written; what it still needed from `timeline.record()` was the outbox
publication, and `DeliveryTimeline.publish()` exists for exactly that. Everything
the platform does of its own accord — requesting a delivery, selecting a
provider, an ETA change — still goes through `record()`, which is the one place
that decides what a timeline entry looks like.

**Why no test caught it.** `DeliveryFlowIT` asserted the timeline with
`containsSubsequence`, which is perfectly happy with duplicates. The new test
asserts `containsOnlyOnce`. A sequence assertion answers "did these happen in
this order"; it does not answer "did anything happen twice", and a timeline needs
both.

---

## D-064 — The web build of the map is a real view, not a placeholder
**2026-09-15 · Settled**

`react-native-maps` has no web implementation, and the restaurant app is checked
in a browser on :7071. A `MandiMap.web.tsx` that said "map unavailable" would
make REST-ORDER-TRACK-01 the one screen nobody could actually look at.

**Decision: the web build renders the same facts without the tiles** — distance
from the outlet, the driver's coordinates and heading, and whether the fix is
current. It is explicitly labelled as the web view so nobody mistakes it for the
shipped experience.

**Doc 06 §8's stale rule is about the data, not the tiles**, so it holds
identically here: a position older than the freshness threshold is drawn as "last
known position" and never as a live one. Showing an old fix as current is worse
than showing none, because the restaurant plans around it.

---

## D-065 — A required header is part of the contract, and gets read like one
**2026-09-15 · Settled**

`markPreparing` and `markReady` were written as bodyless POSTs. Both endpoints
require an `Idempotency-Key` **header**, and a missing required header surfaces
as `MALFORMED_REQUEST` — so a supplier could accept an order and then never move
it, with an error message that sounded like a client bug in the request body.

Caught by walking the flow, not by a type: a header is invisible to TypeScript.

**Decision:** the same rule as D-061, extended. When writing a client call, read
the controller method — its `@RequestBody`, its `@PathVariable`, **and its
`@RequestHeader`**. Four endpoints require the key today (`accept`, `reject`,
`preparing`, `ready`, plus credit invoice payments); `cancel` takes it optionally.
Sending one where the server ignores it is harmless, so when in doubt, send it.

---

## D-066 — The two experiences are URL segments, not route groups
**2026-09-15 · Settled**

The restaurant and supplier halves lived in expo-router groups — `app/(restaurant)`
and `app/(supplier)`. A group adds no URL segment, so `(restaurant)/credit` and
`(supplier)/(tabs)/credit` both resolve to `/credit`. So did `index`, `orders` and
`orders/[id]`: four collisions, and M5 made the fourth.

Navigation *inside* the app worked, because every `router.push` named the group.
What broke was opening `/credit` directly — a deep link, a refresh, a shared URL,
or the browser checks this app is verified with. The router picked one of the two,
and a supplier landing on the restaurant's route was bounced by `AuthGate` to a
blank screen.

**Decision: `app/restaurant/` and `app/supplier/`, as real path segments.** URLs
become `/restaurant/credit` and `/supplier/credit`, and nothing is ambiguous from
a cold load. The cost is a prefix in every href; the alternative is a routing
table where correctness depends on never entering a URL from outside.

A group is right for a layout that should not appear in the URL — `(tabs)` still
is one. It is wrong for two experiences that both own a screen called "orders".

---

## D-067 — Credit that cannot be drawn shows the limit, and says why
**2026-09-15 · Settled**

REST-CREDIT-01 rendered `available` on every agreement card. For a line the
supplier had approved on modified terms, that meant a card reading "₹35,000
available" underneath a headline reading "₹0.00 available" — because the outlet
summary correctly excludes credit that cannot yet fund anything.

Both figures were the server's and both were right. Shown together they told a
restaurant they had money they could not spend.

**Decision: `available` is shown only when `canFund` is true.** Otherwise the card
shows the approved limit and a line saying what is standing in the way —
acceptance, a supplier's decision, suspension, expiry.

**`canFund` stays the server's answer**, never inferred from `status`: an ACTIVE
agreement can still be unable to fund today. The client reads the boolean and
explains it; it does not reconstruct it.

---

## D-068 — A realtime event is a prompt to refresh, never the record
**2026-09-15 · Settled**

`RealtimeProvider` holds one socket for the whole app and, on every event,
invalidates the queries that event touches. It never writes a payload into a
screen's cache.

A socket frame is the one piece of state in the system that arrives without an
access check at read time. Rendering it directly would let a stale or mis-scoped
payload appear as fact — and the payloads are deliberately thin (doc 06 §10 keeps
provider identity and quotes out of them), so a screen fed from a frame would
show less than the endpoint it replaced.

**Invalidation is deliberately coarse.** A delivery event invalidates the order
and its lists rather than one key. Being precise would mean encoding, on the
client, which screen shows which status — the thing the server already decides.

**Polling is the floor, not the failure case.** §16 orders the transports socket,
push, polling; the interval keeps running whenever the socket is not OPEN, and
with the socket up the tracking screen keeps a slow backstop. A socket that is
connected but silently dead looks exactly like a quiet delivery, and tracking is
where that distinction matters most.

**`ready` is not an event.** The server opens with a frame carrying the resume
cursor. Running it through the event handler would advance the cursor past events
the socket has not delivered, losing precisely what the reconnect drain exists to
collect.

---

## D-069 — The socket path is concatenated, not URL-resolved
**2026-09-15 · Settled**

`/realtime/ticket` returns a path relative to the **API's context**
(`/api/v1/realtime/socket`), while the API lives under `/costonomy-mp-api`.
`new URL(path, base)` treats a leading slash as origin-absolute and drops the
context path, producing a URL that never connects.

The failure is silent by construction: the client falls back to polling, the app
keeps working, and nothing anywhere says the socket is dead. It was found by
instrumenting `window.WebSocket` in a browser, not by any test — so there are
tests now, one per shape the server can return.

**Allowed origins are configuration, defaulting to none.** The socket config
reasoned that "clients are native apps, which send no Origin header" — true, and
it meant the Expo web build was refused by Spring's same-origin default, falling
back to polling in exactly the same invisible way. The local profile now names the
dev server. **Never a wildcard**: a leaked ticket plus `*` is any web page on the
internet opening an authenticated socket.

---

## D-070 — A notification event carries the words its template needs
**2026-09-15 · Settled**

Doc 08's template is `{supplierName} accepted order {orderNumber}`. The events
`SupplierOrderTransitions` published carried `supplierStoreId` and no name, so the
renderer dropped the placeholder and restaurants were told " accepted order
MP-260915-000010." — a headless sentence that reads as a bug because it is one.

`NotificationFlowIT` passed throughout, because it published its own payload with
`supplierName` included. **It proved the template and never the payload.** The new
test in `SupplierAcceptanceIT` asserts on what the transition actually emits.

**Money is formatted by name.** A `DECIMAL(19,4)` reached templates as
"35000.0000" — "You have 35000.0000 of credit" is not a sentence to send anyone.
`NotificationRelay` formats a named set of money fields as rupees. Named rather
than inferred: "anything with decimals" would turn a GST rate of 5.0000 into
₹5.00, and forgetting to add a new field degrades to a raw number rather than to a
wrong currency.

**The rule behind all three:** an event is published for consumers that do not
exist yet. Carrying the id alone is correct for a module that will look things up,
and insufficient for one that has to write a sentence.

---

## D-071 — Choosing a side selects a form; the organisation makes it true
**2026-09-16 · Settled**

A signed-in user with no memberships is asked whether they run a restaurant or
supply them. That answer is **not stored on the device** and is not a role. It
picks which registration form to show; the organisation the form creates is what
makes the user a restaurant owner or a supplier, and `/auth/me` is what says so
afterwards.

Remembering "they said supplier" locally would put a claim about a role in the
one place doc 46 says it must never live — and the two would disagree the moment
someone was invited to the other side.

**Registration ends with `reload()` before routing.** The screen has just created
an organisation, and it would be trivial to route on that fact directly. It
routes on the refreshed memberships instead, so there is exactly one answer to
"what is this user" in the whole app.

**A user who was invited never sees this screen**, because they already have a
membership. The screen says so, rather than letting them register a duplicate
restaurant next to the one they were invited to.

---

## D-072 — An outlet is pinned at registration, or it cannot be quoted
**2026-09-16 · Settled**

Delivery quoting is a real serviceability check against the distance between a
store and an outlet. An outlet with no coordinates can never be quoted for: its
orders stop at `READY_FOR_PICKUP` with no error on any screen. This build hit
exactly that, and spent a while looking for the bug.

**Decision: registration asks for the device's location**, at the moment the
person is standing in the place they are describing. `expo-location` on a device,
`navigator.geolocation` on web, imported lazily so the web bundle never pulls the
native module in.

**It is optional, and the copy says what is lost rather than insisting.** A
refusal is a supported outcome — the address is still enough for a human to find
— so the form submits either way and the confirm step states plainly that
delivery cannot be quoted until the outlet is pinned. A required-field asterisk
would have been a weaker argument and a worse experience.

**Permission is requested when it is used, never at launch.**

---

## D-073 — Verification is part of supplier registration, not a later task
**2026-09-16 · Settled**

A supplier cannot trade until a GST verification is reviewed: `canTrade` stays
false and no restaurant sees their catalog. Putting that behind a settings screen
would let someone register, list a hundred SKUs, and wonder for a week why
nothing ever sells.

So SUP-ONB-02 is the third step of SUP-ONB-01, with the GSTIN validated against
the same pattern the server enforces — 15 characters, state code, PAN, entity, Z,
checksum — so a typo is caught before a round trip.

**If the verification call fails, registration still succeeds.** The organisation
and its store exist, the person can build their catalog, and the toast says what
is still outstanding. Rolling back a good registration because a second call
failed would strand someone halfway through setup with nothing to show for it.

---

## D-074 — Approved is not usable, on both sides of the screen
**2026-09-16 · Settled**

D-067 stopped the restaurant being shown "₹35,000 available" for a line it had
not yet accepted. The supplier's own screen had the same fault and kept it: an
agreement approved on modified terms rendered a full credit position, so a
supplier saw a spendable balance for credit nobody could draw on.

**Decision: `canFund` gates the position on every screen, not just the
restaurant's.** Where it is false the screen states what is actually true —
"waiting for them to accept" — and shows the terms that were approved rather than
a balance.

The rule generalises: **a status is not a capability.** `APPROVED` describes how
the agreement got here; `canFund` describes what can be done with it today, and
only the server knows the second one. Any screen that renders money conditional
on a state machine should be reading the capability instead.

---

## D-075 — Setting terms and approving them are one deliberate act, stated
**2026-09-16 · Settled**

Answering a credit request on modified terms used to read as "Send these terms",
with the values edited inline on a list card. A supplier could change a number
and find the request approved, without ever seeing the two facts together.

**Decision: the counter form ends with what is about to happen and a button that
says it** — "Approving ₹40,000 · 45 days", then "Approve at these terms". It is
still one call, because the API has one; what changed is that the screen no
longer hides the consequence behind a neutral verb.

**A disabled primary explains itself.** The edit form's save button was greyed
with no reason given, and the reason — no limit, a cut below committed exposure,
a missing justification — is always knowable. One expression now returns the
sentence rather than a boolean, so the check and its explanation cannot drift
apart.

---

## D-076 — An order card leads with the place, not the order number
**2026-09-16 · Settled**

Every order card led with `MP-260915-000004`. Nobody recognises that string. A
supplier scanning a list of incoming orders is asking three things — who is this
for, where is it going, and what is on it — and the number answered none of them.

**Decision: the card is ordered by what the reader is actually asking.**

1. **The outlet**, which on the supplier's side is where the van goes.
2. **The counterparty, the locality and the distance** — "Spice Garden · 100 Feet
   Road · 5.1 km". The locality is the landmark where one exists, else the street
   line, because an outlet's own name is whatever the restaurant chose to call it.
3. **The goods, by name.** "3 items" tells a supplier nothing they can decide on;
   the decision is about *which* goods. Three names fit a line at phone width, and
   the overflow count is what is **hidden**, not the total — six items shown three
   at a time reads `+3`.
4. **The order number and the payment method**, on a line of their own.

On the restaurant's side the counterparty is the supplier, so that leads and the
outlet moves down — the same principle, the other party.

**The payment method is colour-coded, and neither colour is red.** Prepaid money
is secured; a credit order is a receivable against a limit the supplier granted.
Green and amber say which. Nothing has gone wrong in either case.

**Distance is null when either end is unlocated**, never zero. Doc 07 §4 — an
outlet that was never given coordinates has no distance, and "0 km" would tell a
supplier the order is next door.

One shared component, so the four places an order appears cannot drift apart.

### Grouping, after the flat version proved cluttered
The first build stacked the facts one per line. Seven lines, each as loud as the
next, with the money pinned to a row labelled "Order value" that said nothing the
₹ sign had not already said. The fix is grouping, not removal:

- **Two columns.** Payment method over order number on the left; the amount over
  the item count on the right. Four facts read as two pairs. The dominant figure
  in each column leads, and the two reference numbers sit beneath in the same
  quiet tone — a person reaches for those only when they already know why.
- **The SKU names go last**, below the columns. It is the widest line and the one
  a reader scans rather than parses.

### Only credit is coloured
Prepaid was a filled green pill, and it sat directly beside a green "Confirmed"
status chip. The two read as one smeared signal. Status chips already use every
one of green, blue, amber, red and grey, so **no filled colour is free for a
payment method** — the collision was structural, not a bad choice of green.

**Decision: prepaid is plain text; credit carries `Colors.credit` (violet).** That
token exists for precisely this reason — "credit is supplier-funded and must never
be visually confused with cash payment". Prepaid is the unremarkable case: the
money is secured, there is nothing to act on. The colour now means *this one is on
credit*, which is the fact a supplier acts on, and violet can never be mistaken
for a status.

### Credit reads the same way
A supplier deciding on credit is deciding about a restaurant, and the card showed
only the outlet's own name — whatever that restaurant chose to call it. It now
carries the identical block: **outlet, then restaurant · locality · distance**.
`PartyHeading` is shared by the order cards and the credit cards rather than
copied, because "who and where" is one question and two implementations of one
answer drift.

`AgreementResponse` therefore gains `restaurantName`, `outletLocality`,
`outletCity` and `distanceKm`, exactly as the order responses did.

**Due leaves the portfolio card and stays on the detail screen.** Not to save
room: on a card it sits directly under Utilized and reads as a second, separate
debt, when on a live agreement with nothing overdue it is the same money said
twice. The detail screen has space to show due *and* overdue together, where the
relationship — overdue is a subset of due, never an addition to it — is visible
rather than implied. The two explanatory hints go with it for the same reason,
and the card is roughly half its former height.

### The layout bug this exposed
The first attempt put all three text lines in a column beside the status chip.
That narrowed *every* line by the chip's width, and the first casualty was the
end of the secondary line — the payment method. Only the title shares a row with
the chip now.

---

## D-077 — `IncomingOrderResponse` is not `SupplierOrderResponse`
**2026-09-16 · Settled**

The supplier's pending and active endpoints return `IncomingOrderResponse`. The
mobile client typed both as `SupplierOrder`. The compiler was happy — the fields
it used all existed on the type it had named — and the app read
`order.acceptedAmount`, which that response did not carry.

The effect: a **partially accepted order showed the full requested total.** Order
`MP-260915-000004` was 2 of 4 accepted, worth ₹809.34, and the supplier's own
list showed ₹1,618.68 — a figure they had explicitly declined to commit to.

**Decision: `IncomingOrder` is its own model, mirroring its own DTO**, and
`acceptedAmount` is now on the response so the number can be rendered rather than
inferred. This is D-061 again, and the same lesson: a model is written by reading
the DTO it mirrors, never by finding a type that compiles.

The general rule this makes explicit: **two responses describing the same row
from opposite sides of a trade are two contracts, not one.** The supplier's view
carries the buyer and a countdown; the restaurant's carries the seller and a
payment status. Sharing a model between them means every screen silently reads
fields that may not arrive.

---

## D-078 — An unmatched URL is 404, including the ones Spring does not route
**2026-09-16 · Settled**

`GET /api/v1/search` — a path that does not exist — returned **500
INTERNAL_ERROR**, telling the caller our server had failed and that retrying
might help, when the only thing that could help was fixing the URL.

The cause is that `NoHandlerFoundException`, which the handler did map, is only
raised when `throw-exception-if-no-handler-found` is set. Otherwise an unmatched
path falls through to the static resource resolver, which raises
`NoResourceFoundException` instead — unmapped, so it reached the catch-all.

**Decision: both are mapped to `RESOURCE_NOT_FOUND`.**

The test is an integration test and it authenticates, because unauthenticated the
security filter answers 401 before the dispatcher ever looks for a handler. That
is correct behaviour and it is also why the bug survived: it only appears past
the filter, which is exactly where every real client is. A unit test over the
error catalogue could not have caught it — the mapping that was missing lives in
the dispatcher, not in the enum.

---

## D-079 — A status literal is a contract, and a union of lies type-checks
**2026-09-16 · Settled**

The mobile `SupplierOrderStatus` union declared `ACCEPTED` and `RECEIVED`. The
server has only ever sent `CONFIRMED` and `COMPLETED`. `ProcurementStatus` was
worse — `CART`, `VALIDATED` and `COMPLETED`, three spellings the API has never
produced.

TypeScript could not help, and that is the whole lesson. `order.status ===
'ACCEPTED'` compares a value of the union against a member of that same union.
It is perfectly typed. It is also permanently false.

What it cost:

- **A supplier who accepted an order in full got no "Start preparing" button.**
  The entire fulfilment path — preparing, ready for pickup — was unreachable from
  the app. The order sat CONFIRMED forever.
- A confirmed order **vanished from the restaurant's Active tab**, whose filter
  listed `ACCEPTED`.
- A completed order never reached the **Completed** tab, and stayed on the
  restaurant's home as "in flight" for good, because both lists said `RECEIVED`.

Nothing looked broken. `models/status.ts` had the *correct* keys, so every chip
rendered "Confirmed" and "Completed" exactly as it should. Only the branches were
dead, and a dead branch renders nothing rather than something wrong.

**Decision: the unions are the server's spellings, verbatim, and a mismatch is a
compile error.** Each display map is now written
`satisfies Record<StatusCode, StatusDisplay>` and only then widened to
`Record<string, StatusDisplay>`. The widening has to stay — `resolveStatus` must
survive a status a newer backend invents, because mobile releases lag the API —
but the literal itself is now checked in both directions: a code with no display
fails, and a display for a non-existent code fails. Restoring the old `ACCEPTED`
key now produces `TS2353` on the line that declares it.

An audit of every status union against its Java enum found one more: the client's
`RequirementStatus` was missing `EXPIRED`. The rest matched.

This is D-061 and D-077 a third time, and the general rule is now as strong as it
can be stated: **a client model is written by reading the server's enum, and the
type system is then made to enforce what reading it established.** Naming a
constant after what a field *means* — an accepted order is "accepted" — is how
every one of these happened.

---

## D-080 — A product's picture is platform-owned, and a wrong one is worse than none
**2026-09-16 · Settled**

`canonical_product.image_url` has existed since V6 and `ProductResponse.imageUrl`
has always carried it. Nothing ever set it, and no order response carried it, so
every screen that could have shown a product showed a name.

**Decision: the image stays on the canonical product and is never added to the
SKU.** Doc 01 §7 — the canonical product is the axis every comparison turns on, so
two suppliers' paneer must show the same paneer. A per-SKU image would let a
supplier win a comparison with better photography, which is the one thing ranking
is not allowed to be about. Setting it is an ops action (`PUT
/admin/catalog/products/{id}/image`, `CATALOG_MODERATE` at `PLATFORM`), audited,
and **a blank url clears it** — taking a wrong picture down must be as easy as
putting one up.

The image is carried on `SupplierOrderItemResponse` rather than fetched: a client
drawing a six-line order must not make six requests to do it. It costs nothing —
the mapper already loads the product for its name.

### Curation is the hard part, not the plumbing
Every one of the 34 seeded URLs was rendered and looked at before it was written
down. What that rejected is the argument for doing it:

- **a cooked dish for its raw ingredient** — the first "paneer" result was paneer
  *tikka masala*;
- **a duck leg** returned for "mutton";
- **peanut butter** returned for "groundnut oil";
- **branded packs** — a Kerrygold block for "Butter" would put one brand on the
  product every brand maps onto;
- **the same photograph on two different products**, which is a miscomparison
  drawn rather than stated.

**Jaggery has no image at all.** Nothing in the source was unambiguously jaggery
rather than confectionery, and on a marketplace a restaurant orders from, a
plausible-but-wrong picture is worse than an obviously absent one. The seed prints
what it left out.

For the same reason `ProductThumb` renders **one** neutral glyph for every
product with no picture, rather than deriving a stand-in from the category. A
category-derived icon puts a leaf on a bag of rice: it says "possibly this" where
the honest statement is "no picture". Load failures fall to the same tile, so a
dead URL looks like a product without a photo rather than an app that is broken.

### A SKU may have its own picture; the canonical one is the floor
A supplier sells a pack, and their pack is a real thing a restaurant recognises
on a shelf. `supplier_sku.image_url` has always existed and both write requests
have always accepted it; nothing ever showed it.

**Decision: the SKU image overrides, the canonical image is the fallback, and
which one is showing depends on what the screen is talking about.**

| Screen | Shows |
|---|---|
| `/supplier/catalog` | the SKU's picture, else the product's — this is the listing as a restaurant will see it |
| `/supplier/catalog/new` | always the canonical one; the supplier's pack does not exist yet |
| `/supplier/catalog/{id}` | canonical beside "Listed against", the SKU's own beside their price |

The editor keeps them apart deliberately. It is the one screen where a supplier
needs to see the difference between their photograph and the platform's, and a
single merged image would make "you have not added one" indistinguishable from
"you have".

`SkuResponse` therefore carries **both** `imageUrl` and `canonicalProductImageUrl`
rather than one resolved field. Resolving server-side would be less data and would
destroy exactly the distinction the editor is built on.

**A blank url clears a SKU image**, through `blankToNull` — the same treatment
`skuCode` already gets. Stored as `""` the field is present-but-empty, and every
client falling back with `sku.imageUrl ?? canonical` would render nothing at all:
`??` only falls back on null. The clients use `||` as well, because a contract
that depends on one operator choice in one file is not a contract.

### Two defects this shook out
**`old_state` and `new_state` are `varchar(64)` and hold state-machine states.**
Auditing an image change through them truncated the column and failed the whole
request with `CONCURRENT_MODIFICATION` — a message about a race that never
happened. A URL is not a state; `recordChange` and its JSON snapshots are where a
value of any length belongs.

**`acceptedAmount` is zero on every order nobody accepted**, so a screen reading
it unconditionally showed an expired ₹10,587.97 order as **₹0.00** — as if it had
been worth nothing, when what it lacked was an answer. `orderValue()` now returns
the committed figure only once there is a commitment. This is D-074's shape again:
a number that is correct in one state is not thereby correct in all of them.

### Not settled
These are development seed images hot-linked from Unsplash's CDN under the
Unsplash Licence (free, commercial use, no attribution; none are Unsplash+).
Production wants owned, consistently-lit photography and an upload path — a
marketplace where every product is shot differently looks like a marketplace with
one product photographed badly. No decision has been made about hosting.

---

## D-081 — Uploads go through us, and the bucket is laid out by tenant
**2026-09-16 · Settled**

A supplier can now photograph their pack. Pasting a URL was never the feature —
it was the part that could be built without deciding anything.

**Decision: the file is posted to us and we put it in the store.** A presigned URL
is cheaper and is the obvious alternative, and it is wrong here for two reasons.
It moves validation to the client — what a browser calls a JPEG and what a file
actually is are different claims, and only the server can check the second. And it
leaves local development with nothing to presign against, so the upload path could
not be exercised until a bucket existed.

`FileStorage` is a port with two adapters, selected by
`costonomy.mp.storage.provider`. `LocalFileStorage` writes to disk under the
**identical key** and serves it back from `/files`, so an upload produces a URL
that really resolves and every screen can be checked today. **There is no bucket
yet, and none is needed**: turning S3 on is four properties, and `S3FileStorage`
is not instantiated until then. Credentials come from the default AWS chain and
are deliberately not properties — a bucket secret in `application.properties` is a
bucket secret in the repository.

### The layout is an access-control decision
`suppliers/{orgId}/stores/{storeId}/sku-images/{yyyy}/{MM}/{uuid}.{ext}`

**The tenant is the first segment, always.** A bucket organised by kind —
`images/`, `documents/` — is one nobody can reason about later: "delete everything
belonging to this supplier" becomes a full scan, an IAM policy cannot be written
per tenant, and listing one prefix reveals every tenant's filenames. Laid out by
owner, all three are a prefix operation. The date below it exists for lifecycle
rules, not for people.

**The owner id is read from the database, never taken from the request.** A
client-supplied owner is a directory-traversal parameter with a friendly name. The
filename is a UUID for the same family of reasons: an uploaded name is
attacker-controlled, collides across tenants, and leaks whatever the person called
the file.

### The bytes are the authority
`ImageBytes` identifies the format from the file's own magic bytes and ignores the
declared content type and the extension entirely. An HTML file named `.jpg`, served
back from our own origin, is a script running as us — `ImageBytesTest` asserts it
is refused. **SVG is excluded on purpose**, not by oversight: it is a document that
executes script. Anything unidentifiable is refused rather than stored.

The 5 MB limit is stated twice — `ImageBytes.MAX_BYTES` and
`spring.servlet.multipart.max-file-size` — and the two must agree, because Tomcat
rejects an oversized part before our check runs and would otherwise produce the
wrong error.

### Uploading and saving are separate acts
The endpoint attaches the image to nothing; it returns a URL for the form to
submit later. A supplier who picks a photo and then abandons the form leaves an
orphaned object, which a lifecycle rule collects — the alternative is a listing
that is half-changed, which only a person can notice. For the same reason the
client uploads **on pick, not on save**: otherwise the slowest part of saving runs
after the person has committed, and a failure arrives attached to an action they
thought was about a price.

### The SKU editor after this
The canonical product moved **into the header** — its picture, its name, the
supplier's own name for it beneath. A "Listed against" card below the header said
the same thing twice and made the screen read as being about two products. The
"Currently" card went entirely: its price, pack and GST are each already stated by
the field that edits them.

**Availability and delisting are rows, not buttons.** Side by side as outlined
pills they read as equal in weight to Save and to each other, and they are
neither: marking stock is a daily toggle, delisting takes the listing off the
market. As rows they have room to say what is true now — "Restaurants can order
this right now" — which is also where the state the removed "Currently" card used
to show now lives, stated as a consequence rather than as a chip.

**The save bar is always present and disabled until there is something to save.**
Appearing only once a field changed made it arrive under the thumb mid-edit and
pushed the content up as it did — and a supplier who cannot see a save button has
no way to know the screen saves at all. Disabled covers two different things:
nothing has changed, and what changed cannot be saved (an empty name, a zero
price).

---

## D-082 — Units are a closed vocabulary, and a container states its contents
**2026-09-16 · Settled**

`base_unit` and `pack_unit` were `VARCHAR(32)` with no validation anywhere: no
CHECK, no enum, no pattern, no normalisation. The only vocabulary written down was
five strings hard-coded in the mobile create form — and it was wrong, offering
`BOX` and `DOZEN` while omitting `GM`, `LTR` and `PC`.

**Decision: `Unit` is an enum of fifteen**, and it is the only thing that decides
what a unit is.

```
GM KG OZ LB · ML LTR · PC DOZEN PAIR · BOTTLE PKT CASE BULK TIN BUNDLE
```

This is a comparison-correctness rule, not a tidiness one. Two suppliers' paneer
map to one canonical product so a restaurant can hold their prices side by side,
and that only means anything if both quote the same measure. Free text could not
carry it: `KG`, `Kg`, `kg` and `kgs` are four units to a database and one to a
person, and nothing would have noticed until a restaurant compared two prices that
were not comparable.

**The enum, not a CHECK constraint.** Adding a unit should be a code change with
tests rather than a migration, the column stays readable in a dump, and this
follows D-009 and every status column already here.

### A pack unit is not always a measure
"1 PKT" says how goods are bundled and nothing about how much is being bought.
So `supplier_sku` gains `measure_value` and `measure_unit`, and the rule runs both
ways:

- **PKT, CASE, BULK, TIN, BUNDLE must carry a measure.** Without one, the listing
  states a bundle and never an amount, and no comparison can use it.
- **Everything else must not.** "1 KG of 500 GM" is two statements of one
  quantity, which is two chances to disagree — and the disagreement is discovered
  by whoever receives the wrong weight.

`BOTTLE` is deliberately on the second list even though it is a container: "1
BOTTLE of 1 LTR" is worth saying, but a bottle is also a unit people quote alone.
Contents are measured in `GM KG ML LTR PC BOTTLE PKT` — a subset, because a
measure has to be something a person can add up. "1 CASE of 24 PKT" is useful; "1
CASE of 2 BULK" is a riddle. A unit may not measure itself.

### Two things the tests forced
**Carried-forward is not supplied.** The first update path merged the request over
the stored values and then validated the result, so moving a SKU from PKT to KG
was refused: the leftover 500 GM looked like a contradiction the caller had
written. It is not — the right answer is to clear it, because a stale 500 GM on a
SKU now sold by the kilo is worse than either. The validator now knows which
values the caller actually sent.

**Changing only `packUnit` still validates the measure.** The dangerous edit is
KG → PKT with nothing else in the request: a check that looked at the request
alone would see no measure to object to and store a container with no contents.

### Legacy spellings
`L` and `PIECE` predate the vocabulary. V20 rewrites them to `LTR` and `PC`
everywhere, **including on order, procurement and receiving lines**. Those are
transactional snapshots, and the rule against rewriting a snapshot is about
*values* — a price, a quantity, a total — because those must reconstruct what was
agreed. A unit's spelling is not a value: `L` and `LTR` are the same litre.
Leaving them would make a past order render `L` while a new one renders `LTR`, and
would break any grouping by unit across time.

`Unit.parse` also accepts the spellings people type — `Kg`, `litre`, `pcs`,
`packet`, `carton` — because an import that rejects a supplier's whole file over
`Kg` is an import nobody uses. Leniency at the edge, one spelling in the database.

### The vocabulary is served, not copied
`GET /api/v1/units` returns the pack units, which of them require a measure, and
what a measure may be. D-079 is the argument: a client holding its own copy of a
server vocabulary compiles perfectly while being wrong, and nothing notices until
a comparison silently stops matching. The mobile list is gone.

### Not settled
Nothing checks that a SKU's pack unit is *compatible* with its canonical product's
base unit — a supplier can still list Paneer in `LTR`. That needs a dimension on
each unit (weight, volume, count) and a rule about which conversions are
meaningful, and it is a larger decision than this one.

---

## D-083 — A notification carries the side it was written for, and points at a screen
**2026-09-16 · Settled**

Tapping any notification went to the home screen. Three separate faults, and each
one would have been enough on its own.

### The destinations were all restaurant routes
`destinationFor` mapped every target type to `/restaurant/...`. A supplier tapping
"New order" was sent to a restaurant URL they hold no grant on, bounced by the
route guard, and landed on their home screen — which is why *every* notification
looked like it did nothing.

**Decision: the notification states its audience; the viewer's role is not
consulted.** Routing by the viewer would fix the common case and still fail for
anyone who is both a supplier and a restaurant, because they have no single role
to route by. The server already decided who it was writing to.

### The audience cannot be derived from the event type
The first attempt looked the event up in the rule catalogue. **`SupplierOrderExpired`
has a rule for each side** — the restaurant is told their order expired, the
supplier that they missed it — so `findFirst()` mislabelled one of the two copies,
and a mislabelled copy sends its reader to the other side's URL.

**Decision: `notification.audience` is a stored column, written by the relay**,
which is the only place that knows which rule produced which row. Deriving it was
cheaper and was wrong; the backfill splits the ambiguous event by whether the
recipient holds a supplier grant.

### The target was the aggregate, and no screen is keyed by it
`targetId` was `envelope.aggregateId()`. For a delivery event that is the
**delivery** id, and neither side has a screen keyed by one — the restaurant
tracks `/tracking/{orderId}` and the supplier opens `/orders/{orderId}`. So a
notification about delivery 2 opened **order 2**: a different restaurant's order,
behind a link that looked like it worked. Disputes had the same shape.

**Decision: a rule may name the payload field holding its target**, and the
delivery and dispute rules name `supplierOrderId`. It falls back to the aggregate
when the field is missing, because a payload that changed shape should still
produce an inbox row.

V21 backfills the notifications already sent. An inbox row that opens the wrong
order is worse than one that opens nothing: the first is a link someone follows
and believes.

### The general rule
**A notification is a pointer, and a pointer has to name something the reader can
open.** Three things have to be true at once — the right resource, the right side,
and a screen that exists for that pair — and this failed all three while looking,
from the inbox, exactly like a working feature. `DISPUTE` was also simply missing
from the client's switch, which is the mildest version of the same problem.

---

## D-084 — A store says when it trades, and the answer window is not its to set
**2026-09-16 · Settled**

The store settings screen edited a name, a street line and a prep time. Address,
PIN code, coordinates, trading hours and both policies were unreachable or
absent — a supplier could not say when they were open or how they delivered, and
the marketplace had no way to know either.

### Hours are a trading rule, not a display preference
`supplier_store.operating_hours_json` had existed since V4 and nothing read it.
It now decides whether an order may be placed at all, because a shut store cannot
answer one: an order placed at midnight counts down against a window nobody is
there to answer, and the restaurant waits the full thirty minutes to learn what
was knowable when they tapped.

**A closed store is shown, marked closed, and not orderable.** Hiding it would be
simpler and worse — a supplier would look *gone* at 9pm and their catalogue
unreachable until morning. The blocker names the reason and the opening time:
"Metro Fresh Supplies is closed. They open at 03:00."

**Defaults are every day, 10:00–21:00, and absent means the defaults** rather than
"closed". Unreadable JSON falls back the same way: one malformed row must not
remove a supplier from the marketplace with nothing to say why.

**Unticking every day is refused.** It reads as "closed forever", but no days is
how the server spells no answer, so it falls back to the defaults — and the store
would look open all week to everyone except its owner. Going offline is the
control for that, and the message says so.

### The answer window belongs to operations
A supplier who could set their own window could set it to an hour and never be
late again, and "responds quickly" would stop meaning anything to compare across
the marketplace. It is also the number a restaurant's countdown is measured
against, so it belongs to whoever is accountable for that promise rather than to
the party being held to it.

Removed from `CreateStoreRequest` and `UpdateStoreRequest`, still returned by
`StoreResponse` — a number you are judged by should be visible — and settable at
`PUT /admin/supplier-stores/{id}/response-sla` under `CATALOG_MODERATE` at
`PLATFORM`, audited. Live orders keep the window they were created with; doc 13
is explicit that changing an SLA must not move a countdown already running.

**Note for clients:** Jackson rejects unknown properties, so sending
`responseSlaSeconds` is now a 400 rather than a silent ignore. That is the better
failure — an attempt to change a protected setting should not look like success —
but it breaks an older client rather than degrading it.

### Both policies became reachable
`supplier_delivery_policy` had no endpoint at all: the policy decided how every
order shipped and no supplier could read or change it. `GET`/`PUT
/supplier-stores/{id}/delivery-policy` now exist. **Turning both delivery modes
off is refused** — that is not a policy, it is a store nobody can buy from, and it
would otherwise surface at checkout as "no delivery partner" rather than as the
setting that caused it.

### The screen
A list, not a form. One expanding card meant a supplier scanning for "which store
is offline" had to read a form to find out. Each store is a summary row that opens
its own screen of five sections, with Save and Cancel in a sticky footer —
present always, disabled until something changes, because a form whose save button
appears only once you have typed gives no sign it saves at all.

### What this broke, and why it mattered
**The suite became time-dependent.** Eleven ITs create a store and place an order
against it, and with a 10:00–21:00 default every one of them would fail between
9pm and 10am. It passed first time only because the run happened at 19:22 — the
worst kind of red, arriving on a morning when nobody changed anything and pointing
at whichever test ran. `TestCatalog.tradesAroundTheClock` now says the shop is
open, beside the `lifecycle_status = ACTIVE` those helpers already set.

**A tenant-isolation test nearly passed for the wrong reason.**
`foreignStoreIsUnreachable` asserts 404 rather than 403 so store ids cannot be
enumerated (doc 09 §3). It began returning **400**, because its patch body carried
the now-removed SLA field and an unknown property is refused as malformed before
the scope check runs. The isolation held; the test had stopped exercising it.

### Not settled
Defaulting *unset* hours to 10:00–21:00 changes the behaviour of stores already in
the database, not only new ones: every existing store stops trading at 9pm the day
this ships. The alternative — unset means unknown, keep trading, and only new
stores get the default written — is a smaller blast radius and a weaker promise.

---

## D-085 — The store leads the header, and a sheet closes from inside it
**Raised 2026-09-16 · Settled 2026-09-16**

### The header names where you are, not who you are
A supplier with more than one store works in one of them at a time. The header
had the business as the headline and the store as a grey line beneath it, which
answers the question nobody asks. They are swapped: the store is the title, the
business the caption. The storefront icon went with the swap — once the title is
a store, a glyph saying "this is a store" is decoration in the one place on
screen where width is scarce.

`storeLabel()` strips a repeated business prefix, so "Metro Fresh Supplies
Koramangala" under "Metro Fresh Supplies" reads as "Koramangala" rather than
truncating to "Metro Fresh Supp…" — the same eleven characters that were already
on the line below. It falls back to the full name when the remainder is under two
characters, because "Metro Fresh Supplies 2" must not become "2".

The title is the switcher, and only when there is something to switch between.

### Section titles are scaffolding, so they stop competing with content
"New orders" at 16px semibold in the primary text colour was the same weight,
near the same size and the same colour as the card titles under it, so the label
and the thing labelled looked equally important. Section titles are now 11px,
letterspaced, uppercase and secondary — unmistakably a heading — and carry the
count, which is the fact a supplier actually wants from a section header.

### A bottom sheet's scrim is not a button
It was one, on the reasoning that tapping away is how people close a sheet and
that the gesture deserves an accessible name. But the scrim is the sheet's
*ancestor*, so every control inside every sheet in the app rendered as a button
inside a button: invalid on web, and a screen reader offering two nested controls
where there is one surface. It surfaced on the store switcher and was never
about the store switcher.

**Decision: the scrim closes on a tap and says nothing, and the sheet carries a
close button.** The tap-away is a sighted convenience; the button is the route
that is announced, focusable and reachable. That is the swap the accessible name
was standing in for, and it is better than what it replaced — every sheet now has
a visible way out, rather than requiring you to work out that the dimmed area is
tappable.

The sheet still has to swallow taps so they do not reach the scrim, but it does
that by claiming the responder rather than by being a `Pressable`, which was the
same nesting one layer down.

---


## D-086 — The outlet leads the restaurant header, and its restaurant is already known
**Raised 2026-09-16 · Settled 2026-09-16**

The restaurant half of D-085, and deliberately the same shape: the outlet is the
title, the restaurant the caption, the title is the switcher when there is more
than one outlet, and `placeLabel()` drops a repeated business prefix so "Spice
Garden Koramangala" under "Spice Garden" reads as "Koramangala".

If anything the case is stronger here than on the supplier side. "Which outlet is
this cart for" decides where a delivery is sent, and it was previously answered by
a small grey "Ordering for …" row on Home only — the other four tabs showed the
outlet as a chip beside a section title, in one of three different layouts.

### The restaurant's name is not fetched
`/auth/me` already carries it: a RESTAURANT grant names the restaurant in its own
`scopeName`, and an OUTLET grant names it in `parentScopeName`. So an owner and a
single-outlet manager both get an answer, from different rows, with no request
and nothing new on the wire.

It is resolved **per outlet** rather than per user, because a person can hold
outlet grants in two different restaurants — taking the first grant would caption
the header with whichever restaurant happened to sort first.

### One header, not five
Each restaurant tab built its own, which is why the cart badge existed on Home
and nowhere else: a cook could add to a cart on Discover and lose sight of it.
The bell had the same gap. `OutletSelector` is deleted rather than kept — it was
the fifth bottom sheet implementing the same list, and the header subsumes it.

`RestaurantHeader` takes the screen's doc 05 code as a required prop. The cart is
now reachable from five screens instead of one, and "opened the cart" is only
worth recording if it says from where.

---


## D-087 — Search asks three questions, and the pack is the one being answered
**Raised 2026-09-17 · Settled 2026-09-17**

Search returned one list: canonical products, "from ₹X", tap to compare. That is
the marketplace's central idea and it is the right default — but it is not the
only question a kitchen asks, and the other two had no answer at all.

**Products** — what is curd, and who has it. One row per canonical product,
every supplier collapsed into a lowest price. The comparison the platform exists
for.

**Packs** — what curd can I buy right now. One row per supplier's SKU, with
*their* price, *their* pack and *their* photograph. A cook who already knows the
brand was being made to go through a canonical product to reach it.

**Suppliers** — who can deliver to me at all. This is the one the app genuinely
could not ask: `/search/suppliers` required two characters, so there was no way
to answer "who is out there" for a restaurant that does not yet know any supplier
by name. It now lists with an empty box.

### Distance orders; the supplier's own radius excludes
The obvious reading of "suppliers within 10km" is a 10km filter. It is the wrong
rule. Whether a store serves an outlet is **the store's own declared radius or
pincode list** — they set it, in their own settings — so a fixed cut on top would
hide a supplier who has said they deliver 15km and does. Membership is
serviceability; distance is the sort order. `radiusKm` narrows the list, and what
it excludes comes back as `beyondRadius` so the app can say "2 more deliver here"
rather than presenting a filtered list as the whole answer.

The old endpoint also returned unserviceable suppliers with `serviceable: false`,
which the app had no way to render except as a row you cannot buy from. They are
now simply not results.

### A supplier search is a search for what they sell
Matching supplier names was the obvious implementation and close to useless: you
cannot search for a supplier by name unless you already know the name, which is
the opposite of the problem the tab exists to solve. Typing "pan" means paneer.

A supplier now qualifies by **stocking something buyable that matches** — SKU
name, brand or canonical product, the same three columns pack search reads — and
`matchingProductCount` comes back with them so the row says why it is there:
"1 matching item" rather than a bare catalog total, which is the number a
restaurant is actually choosing on.

Stocking it is not enough; it has to be sellable **today**. The predicate requires
a live SKU, a live unexpired offer, and stock: a supplier who listed paneer and
withdrew the price cannot sell you paneer, and neither can one who is out of it.

That last condition is what keeps the three tabs telling the same story. Without
it a search for curd read "2 suppliers" on Products and listed three on
Suppliers, for the same item — because Products requires `AVAILABLE` and the
supplier count did not. The pack list still shows an out-of-stock row, greyed,
with its Add disabled: "they carry it and are out today" is worth knowing. It is
only worthless as a reason to choose that supplier, which is exactly what a count
beside their name is.

**Names still match**, as the second half of an OR. The other question that
screen answers is "find the supplier I already deal with", and the credit request
screen asks for a supplier by name and nothing else — making this products-only
would have quietly broken it.

### A pack tap and a product tap land in the same place
Tapping a SKU asks "who else sells this, and for how much" — which is exactly the
canonical comparison. Resolving the SKU to its product and going there is not a
shortcut; it is the answer. A separate "similar products" screen would be a
different question, and nobody asked it.

### The SKU leads the comparison, not the supplier
`OfferCard` opened with the supplier's name in bold. But what is being chosen
between is the pack — this brand, this size, this price — and the supplier is how
it arrives. Two packs from one supplier looked like the same row twice. The card
now opens with the picture, the SKU and the pack, and carries the supplier
beneath with its rating and distance.

Rating is exposed for the first time (`averageRating` with `ratingCount`) — it
was computed and fed the ranking, but never shown. It renders **only when it
exists**: most stores have none, and "0.0 ★" would read as a bad supplier rather
than an unrated one (doc 07 §4). The count is shown beside it because 5.0 from
one order and 4.6 from two hundred are not the same claim.

### One row shape behind all of it
`StorefrontSku` serves SKU search, a store's catalog and the comparison. Three
near-identical DTOs would have drifted, and the three screens are the same rows
sliced differently.

A store's catalog is deliberately **not** filtered by serviceability: the
restaurant asked for that supplier by name, and an empty shelf answers a question
they did not ask. Whether an order can be placed is settled at checkout, by the
server, which is the only thing that can settle it.

### No category chips
The reference design carries subcategory facets under the search box. Left out on
purpose for now: our canonical catalog is small enough that a term already
narrows it further than a facet would, and a chip row that is usually one chip
wide is scaffolding with nothing to hold.

---


## D-017 — The requirement lifecycle includes SOURCING
**Raised 2026-09-14 · Settled 2026-09-14** (was OPEN-003)

`03-state-machines-permissions.md` §3 has `OPEN → SOURCING → PARTIALLY_FULFILLED
→ FULFILLED`. `Mandi_Engineering_PRD_v1.0.md` §6 omits `SOURCING`.

**Decision: included.** Per D-001 the numbered docs win, and the state earns its
place — "we have submitted this to a supplier and are waiting" is genuinely not
"nothing has happened yet", and §23A.14 shows the two differently.

A requirement returns to `SOURCING` from `PARTIALLY_FULFILLED` when the shortfall
is submitted to another supplier, which is the loop guardrail 14 exists to keep
open.

## D-088 — The request is the basket, and money starts at the order
**Raised 2026-09-18 · Settled 2026-09-18**

The migration spec separates what a restaurant wants from what a supplier will
supply from the commercial transaction. Executing it meant deciding where the
basket lives, and the answer shapes everything else.

**Decision: an intent *is* the basket, one per supplier.** Adding a pack finds or
opens a `DRAFT` intent for that pack's store; the cart screen becomes a card per
supplier; "Send request" flips them to `OPEN`. The split into one-supplier
requests happens while the restaurant shops rather than at checkout, which is
what keeps the intent → order boundary one-to-one without anybody having to think
about it. `uk_intent_order_link_intent` makes that boundary a database
constraint rather than a convention.

`Procurement` stays for orders already placed through it. Nothing new is created
there.

### What this fixes
The old flow took payment, then let the supplier reduce quantities. Money moved
against a promise nobody had made, and the order the restaurant paid for was not
the order it received — which is what produced the misleading-totals and
partial-acceptance defects fixed in D-085 and its neighbours. Here the supplier
commits first and payment is last, so there is no partial acceptance to display
because there is nothing to reduce after the fact.

### Lifecycle status is not fulfilment
`IntentStatus` says where a request is; `IntentFulfilment` says how much of it was
agreed to, **derived** from accepted against requested quantities and never
stored. An intent can be `ORDERED` and only a third filled, and the restaurant's
filter is asking the second question. One stored value for both would force a
choice between a wrong status and a wrong filter.

**"Fulfilled" means accepted** — the supplier has committed. Not delivered, not
received; receiving has its own three quantities for that (doc 03 §11).

Fulfilment rolls up **per line, never by summing quantities**. Lines are in each
SKU's own pack unit, so 20 KG of rice and 5 LTR of oil have no meaningful total,
and summing them would make fulfilment depend on which units were in the basket.

### The acceptance is a quote with a deadline
There is no price-change flow at order creation, and no separate "acceptance
validity" clock. An acceptance stands for exactly as long as an order can be
created from it, so `intent_acceptance.expires_at` is set to the intent's
`order_creation_deadline` rather than computed from a second setting — two clocks
that must agree are two clocks that will eventually disagree, and the
disagreement reads as "your offer is still valid" on one screen and "this expired"
on the other.

Inside the window the quoted price holds. The supplier is protected by the window
being short; the restaurant by the price not moving inside it. A supplier who
repriced their catalogue after committing does not get to reprice a live
commitment. The old cart needed a price-change confirmation because it collected
money against prices nobody had agreed to yet.

**The window is snapshotted onto the intent at acceptance, never re-read.**
Re-reading would make every deadline a function of today's configuration: raise
the setting and yesterday's expired intents come back to life, lower it and a
restaurant loses a window it was told it had.

**Default 30 minutes**, clamped to [60s, 24h]. Five minutes was the first choice
and was wrong: the window starts when the supplier answers, not when the
restaurant looks, and an acceptance can land hours after the request. Most
requests would have expired before anyone read the notification, and restaurants
would learn that accepted requests routinely evaporate.

### The restaurant may take less, never more
`0 ≤ ordered ≤ offered` at order creation. Nothing is clamped silently — a
quantity above what was offered is an error, because the client showed somebody a
number and quietly ordering less is how a kitchen ends up short without being
told.

### An order from an intent arrives already accepted
Its lines carry `acceptedQuantity` equal to what was ordered, and it is released
to `CONFIRMED` rather than `PENDING_ACCEPTANCE`. Routing it through
`PENDING_ACCEPTANCE` would ask the supplier to accept what they just accepted,
and the acceptance countdown would expire an order that was already agreed.

Guardrail 16 is unchanged: the order is still created `DRAFT` and still released
only once funding is secured. What changed is where it goes next.

**`OrderReleaseService` reads the destination off the order, and must.** The first
attempt took it as an argument from the caller. That looked clean and was wrong:
release is triggered from wherever funding lands first — the confirm call, a
provider webhook, or the reconciliation sweep — and none of those know how the
order was built. Only the create path passed `CONFIRMED`, so for a prepaid order
(where nothing is secured at creation) the webhook path quietly kept the old
default and sent an already-accepted order back to the supplier to accept again.
An integration test caught it; nothing about the code read as wrong.

The discriminator is `procurement_id IS NULL` — a local fact on the order saying
which flow built it. It also stays correct when the cart flow goes: every order
is then intent-built, every `procurement_id` is null, and every order confirms.

### Permissions are reused, not added
Building a request needs `PROCUREMENT_CREATE`, sending and ordering
`PROCUREMENT_SUBMIT`, cancelling `ORDER_CANCEL`, viewing `ORDER_VIEW`. On the
supplier side the *shape of the answer* picks the permission: in full needs
`ORDER_ACCEPT`, short needs `ORDER_PARTIAL_ACCEPT`, declining everything needs
`ORDER_REJECT`.

No new permission codes, because the capability is the same capability — the
intent replaces the cart, so whoever could build a cart should build a request.
Reusing them also keeps `AccessControlIT` and `PermissionCatalogIT` meaningful
without a migration seeding rows that mean the same as rows already there. The
supplier split is worth keeping: a store can let a warehouse hand confirm what is
in stock without also letting them turn business away.

### Answer every line, or be refused
A response must cover every line; offering zero declines one. An omitted line read
as zero would turn a client dropping a row into a refusal the supplier never made,
and the restaurant would be told a product was unavailable when nobody said so.

Suppliers send **no prices**. Each line is priced from that store's live `ACTIVE`
offer through `Pricing`. A price changes by superseding an offer (D-012), never by
answering a request differently — otherwise the price compared on the product
screen and the price charged could differ with nothing to reconcile them.

### Two expiries, not one
`EXPIRED` is the supplier never answering. `ORDER_CREATION_EXPIRED` is the
supplier answering and the restaurant letting the window lapse. Conflating them
would blame the supplier for the restaurant's delay, and the supplier's response
rate is built from the difference.

### Schema notes
The cart assumption turned out to be baked into the schema in **four** places,
found one at a time as each failed:

- V23 — `supplier_order.procurement_id`, made nullable with the new tables.
- V24 — `supplier_order_item.procurement_item_id` was still `NOT NULL`, so the
  order header was portable and its lines were not, and inserting an intent-built
  order's items would have failed.
- V25 — `payment.procurement_id` and `credit_reservation.procurement_id`, both
  `NOT NULL`. This one got as far as arranging payment before failing, which is
  the worst place to discover it.

Nothing is lost by relaxing them. Payment and credit are both per supplier order
(D-010), and the column is a convenience for grouping one multi-supplier
checkout — which an intent-built order has nothing to group with, since one
request is one supplier is one order. `intent_order_link` records where the order
came from, and each line still carries its canonical product and supplier SKU,
which is what receiving, disputes and settlement actually read. Every foreign key
stays; a null is exempt, so cart-built orders keep exactly the guarantees they
had.

## D-089 — The supplier's clock belongs to the request, not the order
**Raised 2026-09-18 · Settled 2026-09-18**

D-088 moved the supplier's commitment from the order to the request, and the
response SLA did not follow it. The result was backwards in both directions:
`supplier_store.response_sla_seconds` — the supplier's own promise about how
quickly they reply — was still being snapshotted onto an order that, under the
new flow, arrives already accepted and needs no answer at all; while the
**request**, which is the thing a supplier now actually has to answer, expired
on a global default that ignored their configuration entirely.

**Decision: the store's SLA is the request's deadline.** It is snapshotted onto
`intent.response_deadline` at send time, from `sent_at` plus the store's window,
for the same reason the order-creation window is snapshotted at acceptance: a
supplier changing their SLA must not move a deadline both sides are already
watching, and a deadline recomputed from today's configuration would resurrect
expired requests every time somebody widened the setting.

`IntentResponder` checks it rather than trusting the sweep, so "a supplier
cannot answer an expired request" is true at every instant and not merely within
thirty seconds of one. The job remains housekeeping.

### Clamped, and the floor is the part that matters
`supplier_store.response_sla_seconds` defaults to **60**, a figure chosen for a
supplier watching an order queue. A request can arrive overnight. Left
unclamped, every request to a store that never configured an SLA would expire a
minute after it was sent, and the restaurant would conclude that suppliers never
reply rather than that a default was wrong. Floor 5 minutes, ceiling 7 days; a
store with nothing configured falls back to the platform default rather than to
the column's.

### Its own error code
`INTENT_EXPIRED`, not the `SUPPLIER_ORDER_EXPIRED` it would otherwise borrow. A
client matching on codes would tell a supplier their *order* expired when what
lapsed was a request they had not answered — the accurate-but-useless report
D-018 exists to prevent.

### Both clocks are now visible, to the right person
The supplier sees a countdown to reply; the restaurant sees the same deadline as
"usually replies within", so a kitchen can decide whether to wait or go
elsewhere instead of refreshing a screen that says only "waiting". Once the
request is answered the clock changes hands and becomes the restaurant's window
to order. Only one is ever live.

`MandiCountdown` grew an `action` label for this. It said "to respond"
unconditionally, which is somebody else's job when a restaurant is counting down
its own window.

The supplier's "New orders" section no longer claims a response window, because
there is nothing there to respond to.

### A migration lesson worth keeping
The first version added the columns, backfilled the requests already in flight,
and added a validating CHECK — in one migration. It applied perfectly to an
empty database and **failed against one with data**: the constraint was
validated without seeing the backfill written immediately above it, Flyway
recorded the migration as failed, and the application refused to start until the
history row was removed and the half-applied columns dropped.

The replay used to check migrations runs against a clean database, where a
backfill is a no-op — so it could not have caught this, and did not. Split into
V26 (columns and backfill) and V27 (constraint and index), each migration acts
on data the previous one has committed. **A migration that backfills must be
tested against representative data, and a validating constraint belongs in a
later migration than the backfill it depends on.**

## D-090 — The basket price is real, and sending locks it
**Raised 2026-09-18 · Settled 2026-09-18**

D-088 kept prices off the intent entirely: the supplier's reply set them, so
anything the basket displayed was a guess and had to be labelled one. Shown to a
restaurant, that read as "approximately ₹3,936.66" — a hedge on the one figure
they were about to commit to, and the wrong answer to a real need. A kitchen with
a budget cannot send a request blind, and the price is not a secret: it is the
same listing they compared on the product screen a moment earlier.

**Decision: the price lives on the line and is locked when the request is sent.**
`intent_item` carries `unit_price_snapshot`, `gst_rate_snapshot` and the offer
they came from (V28). A draft tracks the supplier's current price; sending
re-confirms it and freezes it; the supplier's reply confirms that price or
declines the line; the order is created on the same figure. One number from
basket to invoice, which is what makes it safe to show without a tilde.

This is still not "financial" in the sense V23 meant — no payment, no
reservation, no commission, no GMV. A price on a line is a quote, and a quote
nobody orders against costs nobody anything.

### The supplier cannot reprice a request in flight
`IntentResponder` prices from the snapshot, not from today's catalogue.
Re-reading it would hand the supplier a unilateral change between the asking and
the answering, and make the figure the restaurant confirmed a lie. A supplier who
no longer wants to sell at it declines the line — an answer they can always give,
and far better than a silent reprice nobody agreed to. Verified: with the
catalogue moved to ₹500 after sending, the reply came back at the locked ₹425.

Catalogue changes still supersede as D-012 requires; they apply to the next
request rather than to one already sent.

### Repriced requests are held, not blocked
One action sends the whole basket. Requests whose prices have not moved go
immediately; repriced ones come back in `held` with old and new for each line,
and re-sending with `acceptPriceChanges` agrees to them. One supplier's overnight
price rise should not stall the other two, and §23A.16 is satisfied either way: a
price that moved is shown and agreed to, never absorbed — and never silently
applied, which is what sending them anyway would amount to.

The drift is also flagged on the line itself in the basket, so somebody scanning
it sees which item moved without having to press send to find out.

### A missing price is not a zero
A line whose SKU has no live offer is priced `null` and the screen says "No
price"; the request's total then reports itself incomplete. Zero would read as
free, and a total quietly missing an item is worse than one that admits it.

### The basket total comes from the server
`GET /outlets/{id}/intent-drafts` returns a basket rather than a bare list.
Summing the per-supplier totals in the client would be arithmetic on money — the
one thing guardrail 3 forbids — and a client-side sum of already-rounded figures
is exactly the kind that lands a paisa from the server's own.

---

## D-091 — The order stops asking, and the restaurant chooses how goods travel
**Raised 2026-09-19 · Settled 2026-09-19**

D-088 moved the supplier's commitment to the request. The order lifecycle never
followed, so it still carried the whole vocabulary of a system where the supplier
decided at order time: `PENDING_ACCEPTANCE`, `PARTIALLY_ACCEPTED`, `REJECTED` and
`EXPIRED` remained legal states, four of the twelve, all of them unreachable from
the live flow and all of them still handled by every switch, chip map and
dashboard query in both repos.

At the same time the one thing the order genuinely did need to decide — how the
goods get from the store to the kitchen — was modelled as two nullable
`delivery_mode` columns with **no defined values anywhere in main or test**, set
by the supplier on acceptance and never copied onto the order.

**Decision: trim the lifecycle to what can happen, and give the mode to the
party that pays for it.**

### Eight statuses, and no second acceptance

```
DRAFT → CONFIRMED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY
      → DELIVERED → COMPLETED,  with CANCELLED
```

An order arrives `CONFIRMED` because the supplier already said yes to the
request. Their two actions are *start preparing* and *cancel* — there is no
acknowledgement step, because a status that exists only to be clicked through
adds a way for a paid order to stall and answers no question the request did not
already answer.

`PARTIALLY_ACCEPTED` goes because partial acceptance now happens on the request:
the supplier offers less, the restaurant orders what was offered, and the
shortfall stays visible on the request rather than being encoded as a flavour of
order. `REJECTED` and `EXPIRED` go with the acceptance step that produced them.

D-088's own note said `PENDING_ACCEPTANCE` "remains for orders still created the
old way, and goes when that path does". This is that.

### A supplier who cannot fulfil cancels, and the record says who

The escape hatch after `CONFIRMED` is cancellation, not rejection, because money
has already moved — the difference between the two is a refund. One `CANCELLED`
status carries `cancelled_by` (`RESTAURANT` / `SUPPLIER` / `SYSTEM`) and a
reason.

Deliberately **not** two statuses, which is the opposite of the call D-089 made
when it kept `EXPIRED` and `ORDER_CREATION_EXPIRED` apart. The distinction there
was between two *different things happening*: a supplier ignoring a request and a
restaurant not using a reply. Here one thing happens — the order is cancelled —
and only the actor differs. An attribute separates actors; a status separates
events, and a reliability metric reads `cancelled_by` just as well as it reads a
status name while every switch stays at eight cases.

### The mode is the restaurant's, and it is priced before payment

`PICKUP`, `SUPPLIER_DELIVERY`, `COSTONOMY_DELIVERY`, typed, `NOT NULL` on the
order.

The restaurant chooses, because the restaurant pays the delivery fee, and it
chooses **at order creation** — the fee is part of what is charged, so the mode
must be fixed before the payment intent exists. A supplier therefore no longer
names one mode on acceptance; they declare which modes they can serve, bounded by
`supplier_delivery_policy.own_delivery_enabled` and `costonomy_delivery_enabled`.

### `allowedTransitions()` stops being a function of status alone

This is the structural consequence and the reason the change is worth a decision
rather than a commit message. `READY_FOR_PICKUP` goes to `COMPLETED` under
`PICKUP` and to `OUT_FOR_DELIVERY` otherwise, so the mode is now an argument.

Authorisation branches with it. §23A.38 — *a supplier cannot claim pickup or
delivery on a courier's behalf* — is correct under `COSTONOMY_DELIVERY` and
**wrong under `SUPPLIER_DELIVERY`**, where the supplier is the courier. Under
`PICKUP` the restaurant moves the order, which no order transition previously
allowed.

| From → To | PICKUP | SUPPLIER_DELIVERY | COSTONOMY_DELIVERY |
|---|---|---|---|
| `READY_FOR_PICKUP →` | restaurant → `COMPLETED` | supplier → `OUT_FOR_DELIVERY` | courier event only |
| `OUT_FOR_DELIVERY → DELIVERED` | n/a | supplier | courier event only |
| `DELIVERED → COMPLETED` | n/a | restaurant | restaurant |

Cancellation stays closed from `READY_FOR_PICKUP` onward. Doc 01 §13 is unchanged
by any of this: once goods have left, the path is return or dispute.

### Capture moved to confirmation

Payment capture fired when the supplier accepted. With no acceptance left it
fired nowhere at all, and every payment authorised while none was ever captured —
caught by `PaymentFlowIT` rather than by reading the diff, which is the argument
for keeping those suites pointed at a real order.

It now fires from `OrderReleaseService` at confirmation. Doc 01 §14 is unchanged:
only the accepted commercial value is taken, and under this flow the accepted
amount is known at creation because the order was built from what the supplier
offered. The timing is the same in substance — money was always taken when the
supplier's commitment became firm — and that moment is now earlier by the length
of a response window that no longer exists.

Credit follows the same path: reserve and draw land together, where they used to
be separated by the acceptance.

### A pickup order is still received

Collection goes through `ReceivingService`, the same path a delivered order
takes, rather than a single button that marks the order complete. A pickup order
that skipped receiving could never record a shortfall, and a discrepancy noticed
in the van would have no route except a dispute raised against no recorded
quantity.

### The delivery fee is quoted, stored, and binding

`COSTONOMY_DELIVERY` needs a number before a provider has been chosen, and
provider bidding is internal (doc 06 §10) — the restaurant sees one fee and never
a quote. So the fee comes from a quote endpoint that resolves both endpoints,
weighs the lines, and prices the run.

**It takes ids, not coordinates.** The caller sends the request; the server
resolves `outlet` and `supplier_store` to their own latitudes and longitudes.
Accepting an origin from the client would let a caller quote a one-kilometre
delivery and receive a twenty-kilometre one, and guardrail 3 puts the arithmetic
on the server regardless.

The quote is **stored and referenced by id** at order creation, not recomputed.
A fee recomputed between the screen that showed it and the charge that collected
it is a silent reprice, which §23A.16 forbids; an expired or mismatched quote
surfaces as a price change, shown old and new, and confirmed.

It returns fee and ETA and nothing else. Vehicle type and the provider responses
that produced it are internal: they determine the platform's cost, not the
restaurant's price.

A provider outage cannot block every order, so the quote degrades to a configured
rate card and records that it did.

### Weight is missing from the catalogue, and mostly derivable

The quote needs weight and `supplier_sku` has none. Of the seventy-five SKUs in
the development catalogue, fifty-seven carry mass directly (`KG` pack units, and
`PKT` with a `GM` measure), twelve are volumes that need an assumed density, and
six are counts — `PC` — that carry no weight information at all.

So `weight_grams` goes on `supplier_sku`, nullable, with the derivation as a
documented fallback rather than the source of truth. Guessing a vehicle from a
density assumption is acceptable for a litre of oil and is not acceptable for a
tenth of the catalogue.

### Four things this leaves open

- **A supplier cancelling a credit order leaves the debt standing.** Rejection
  used to happen before the draw, so releasing the hold was the whole reversal.
  Confirming a credit order as it is created utilises it and raises the invoice,
  so by the time a supplier backs out the money is drawn and
  `CreditLedgerService.release` returns early — the reservation no longer holds
  exposure. This is the same shape as prepaid, where cancelling after capture
  needs a refund rather than a release; credit's equivalent is a credit note and
  it does not exist yet. `CreditFlowIT` asserts the current behaviour explicitly
  rather than hiding it.

- A `READY_FOR_PICKUP` pickup order nobody collects has no expiry path, and
  cancellation is already closed by then.
- `latitude` and `longitude` are nullable on both `outlet` and `supplier_store`.
  `COSTONOMY_DELIVERY` is withheld when either is missing rather than estimated
  from a pincode.
- `intent_acceptance.eta_minutes` is unused now that the supplier no longer
  enters a delivery duration. The ETA belongs to the store's preparation time
  plus the quote's travel estimate.

### Dead columns removed in passing

`supplier_order.credit_status` and `intent_item.status` were both declared,
defaulted, and never written or read by anything in `src/main`. `intent_item.status`
was on the wire as well, so every client received a constant it could have
branched on.

---


## D-092 — The cart groups by supplier, and a repriced request cannot be collapsed
**Raised 2026-09-19 · Settled 2026-09-19**

The cart rendered every line of every supplier expanded, one card each, in a
single scroll. With three suppliers and six lines apiece that is about a thousand
points of scrolling in which the only thing separating one supplier's request
from the next is a card edge that has already left the screen — and the lines on
screen carry no indication of whose request they belong to.

**Decision: collapsible sections with a pinned heading.** Each supplier is a
heading and a body. The heading pins to the top of the scroll view while its own
lines pass beneath it, so a price on screen always has a supplier's name and that
request's total beside it. Sections beyond the second start collapsed; one or two
start open, because two fit on a screen and the grouping is not yet earning
anything.

### A collapsed heading still carries the commercial facts
Item count and the request's total sit on the heading whether it is open or shut.
Collapsing is a way to find a request, never a way for the app to stop showing
what one costs.

### A request with a price change cannot be collapsed at all
If any line has been repriced, or any line has no live price, that section is
forced open, says so on its heading, and loses its chevron and its button role
along with the ability to close. §23A.16 requires a change to be shown old and
new and confirmed; a change folded behind a chevron the restaurant never opened
is the app hiding it, and "they could have expanded it" is not consent. The
chevron goes rather than sitting there refusing to work, because a dead
affordance is worst on exactly the request that most needs reading.

### The screen shell learned `stickyIndices`
`MandiScreen` normally puts its children inside one padded wrapper, which would
make the whole list a single scroll child and stickiness meaningless. Passing
`stickyIndices` moves the gutter onto the scroll view's content container and
lifts the children to be its direct descendants. That is also why the heading and
the body of a section are two exports rather than one component, and why a
collapsed body still renders an empty `View`: the indices address children by
position, and a child that vanishes shifts every index after it.


### A single supplier's request is sent through the basket, not through `send`
Added after the fact, because the cart now offers "Send This Request" under each
supplier's total — a basket of three is three conversations and they are not
always ready together.

`POST /intents/{id}/send` looks like the endpoint for that and is not. It neither
checks whether the supplier has repriced nor re-snapshots the line, so one
request sent that way would reach a supplier at a price nobody agreed to and the
restaurant would never see the change. `sendAll` does both, and returns `held`,
which the cart already knows how to show.

So `SendBasketRequest` gained an optional `intentId` and the per-supplier button
calls the same endpoint with it. Agreeing to a price change carries the id too:
accepting one supplier's new price is not agreement to send the other two.

---


## D-093 — A supplier's shelf says whether that supplier has given you credit
**Raised 2026-09-19 · Settled 2026-09-19**

Credit decided whether a kitchen could buy from a supplier at all, and the only
place that said so was the credit screen, three taps away from every screen where
the question comes up. A restaurant browsing suppliers had no way to tell which
of them they had terms with.

**Decision: credit appears wherever a supplier does.** A chip on the tile in the
home rail and the browse page; a line with a bar on the supplier's own shelf,
with an offer to ask when there is none.

### The shelf header is a scoped endpoint, and that is the point
`GET /supplier-stores/{id}/storefront?outletId=` is served by
`SupplierStorefrontService`, deliberately separate from `StorefrontService`.
That one is open — it returns the catalogue any signed-in restaurant may shop,
and its `outletId` only supplies a distance. This one carries what a supplier has
extended *that outlet*, so naming an outlet requires being scoped to it; asking
about somebody else's outlet is a 404, as every scoped read here is.

Without an `outletId` there is no credit, no distance and no ETA — only the
branch and its rating. That is a legitimate call, not a degraded one.

### The app joins credit to a list; it never computes it
The tiles get their credit from one `GET /outlets/{id}/credit/agreements` joined
by store, rather than a per-row request that would land the most important fact
on a tile last. The join is all the client does: every rupee is the server's, and
`available` above all, since it nets off reservations against orders already in
flight (§23A.24).

The bar on the shelf header is the one derived thing on the screen, and what it
derives is a width. The numbers beside it — used, limit, available — are printed
as sent, because a bar is not readable to everyone (§23A.48) and a length is not
a figure.

### `canFund`, never the status
Whether an order can draw on an agreement is the server's word. The app shows
three states — spend it, wait for them, ask for it — and picks between them on
`canFund` plus the status for the *wording*, never by inferring fundability from
a status name.

### A rating nobody has given is not a good rating
`ratingCount == 0` renders "Not rated yet". Doc 07 §4: a supplier without history
has no score, and averaging nothing into a number is how a new store ends up
looking excellent or terrible for no reason.

### The branch is the title; the organisation is beneath it
A kitchen orders from a branch — it has the distance, the shelf, the hours. A
supplier with more than one trading branch gets a switcher in the title bar, and
the sheet says plainly that each branch has its own shelf and its own prices,
because they do. A supplier with one branch gets no switcher: a picker with one
option is a control that cannot do anything.

### ETA is preparation plus travel, or nothing
The same `Serviceability.estimateMinutes` the product comparison ranks on, so a
shelf and a recommendation cannot disagree about how long a supplier takes. Null
when either end has no coordinates — an ETA invented from a pincode is a number
somebody plans a service around.

---


## D-094 — A store that keeps stock can be ordered from without being asked
**Raised 2026-09-20 · Settled 2026-09-20**

The request round trip exists to answer one question: does this supplier
actually have the goods. A meat or vegetable wholesaler with a short list and
real inventory has already answered it by listing the line, and making a kitchen
wait out a response window to hear so is a delay that buys nothing.

**Decision: `supplier_store.direct_orders_enabled`.** Off for every existing
store, which is exactly today's behaviour. The supplier sets it on their own
store settings; an operator can set it for them through
`PUT /admin/supplier-stores/{id}/direct-orders`, with a reason, because a
wholesaler who asks on the phone should not have to find the screen. Both paths
audit who changed it and from what — from the supplier's side an operator's
change looks like a setting they never touched.

### It does not fork the order flow
`POST /intents/{id}/direct-order` writes the supplier's answer from their own
standing offer and hands back a request in `RESPONSES_RECEIVED`. Everything after
that is the existing path: the same review screen, the same delivery choice, the
same payment, the same `intent_order_link`, the same commission base. A second
way to create an order would be a second place for the money to be wrong.

The acceptance it writes has `responded_by` null. Nobody typed it; the store's
setting made it, and that is what the record should say.

### The price is the live offer, and a change is still shown
A draft carries the price from when the line was added. Ordering directly
re-reads the offer, and if it moved, nothing happens and old and new come back in
the same `held` shape the basket returns — so the cart shows it with the sheet it
already has. §23A.16 does not stop applying because the supplier is not in the
loop; this is the only place it could be enforced.

### A line the supplier cannot fill refuses by name
Turning this on says "I hold these lines", but the offer still carries the
quantity they declared, and an order beyond it lands on a supplier who cannot
fill it. So a short line refuses with the product, the figure they declared and
what was asked for, and nothing is charged. **Refusing rather than quietly
capping**: the quantity is the restaurant's decision, they may want to take less
or buy the rest elsewhere, and only they can say which.

A line whose offer has been delisted refuses separately, and is not reported as a
price change — there is no new price, and saying "the price went up" would be a
lie about what happened.

### The setting is re-checked on the server, every time
A client that has not refreshed since a supplier turned this off must not be able
to spend it. The flag on the draft is for drawing the button; the check that
matters is the one in `DirectOrderService`.

### Both buttons, not one replacing the other
A supplier with direct ordering on still accepts requests, and the cart offers
both under that supplier's total. A kitchen may want the supplier to confirm
before money moves, and that is their call. The buttons are per supplier because
an order is per supplier: a cart-wide "Create Order" cannot work when one store
allows it and another does not, and a single tap producing two different outcomes
for two suppliers is worse than two taps.

---


## D-095 — An outlet and a store share one conversation, and it can be switched off
**Raised 2026-09-20 · Settled 2026-09-20**

Two parties arranging a delivery had nowhere to say anything to each other. The
order screens carry states, not sentences, and "can you send six more packs" is
not a state.

**Decision: one thread per outlet–store pair.** Requests and orders are shared
*into* it as links. A thread per order was the alternative and gives a kitchen
nine conversations with one supplier, no way to ask a general question, and a
guarantee that everybody uses the newest thread for everything anyway.

### A thread exists only where the two have traded
Opening one needs a request sent or an order placed between them, and only the
restaurant opens it — they are the side that chooses a supplier. Without that,
the marketplace is a way to message strangers, in both directions.

### Switching it off closes the composer, not the record
`outlet.chat_enabled` and `supplier_store.chat_enabled`, both defaulting on,
both set by operations with a reason and an audit row. Either being off stops
new messages and leaves every message already sent readable to both sides: what
a supplier agreed to in writing is exactly what somebody needs after support has
been called, and deleting it would be the platform editing a conversation it was
not part of.

**The server decides this, not the app.** `canSend` and `disabledReason` come
back on every thread. The app cannot work it out, because it only ever holds a
flag about one of the two parties — and the header action is disabled with a
line pointing at support rather than hidden, because a control that vanishes
reads as a bug.

### An attachment is a typed reference, never a URL
`{type, id}`, checked against the pair before it is stored: an id from somebody
else's order would be a link into a tenant the reader cannot see — a 404 at
best, a probe for which ids exist at worst. The reference is snapshotted onto
the message so a shared order still reads as `MP-260919-000013` without a join,
and the app resolves the *screen* from who is reading, so a supplier tapping an
order lands on their view of it rather than the restaurant's.

### Two event names for one thing
A notification rule names one audience, and a message has to reach whoever did
not send it, so `ChatSide` owns `ChatMessageToSupplier` and
`ChatMessageToRestaurant` (D-044 — the name belongs to the enum that raises it).
**No preview in the notification body**: doc 08 §8 renders bodies from named
fields precisely so a template asking for an order number can only ever contain
one, and a message preview is whatever somebody typed, landing on a lock screen.
It says who; opening it says what.

### Read state is per side, not per user
A store is answered by whoever is on the counter. Three colleagues each carrying
their own unread badge for one conversation is three people ignoring it.

### The scope is passed to the chat hook, never read from a provider
`OutletProvider` lives under the restaurant navigator and `StoreProvider` under
the supplier one. A hook reaching for both throws on whichever side is missing —
which it did, and took the supplier home screen down with it. The inbox sits
above both navigators and has neither, so the header that opens it names the
outlet or store in the link.

### Polling, for now, and said plainly
Chat is the only thing in this app that arrives without the reader doing
anything; everything else changes because somebody tapped. The right answer is a
`realtime` channel and the events are already on the outbox for one. Until that
exists the app polls slowly, which is honest rather than chatty.

---


## D-096 — A SKU is something to decide about, not only to compare
**Raised 2026-09-20 · Settled 2026-09-20**

A listing was a price, a pack and a thumbnail: enough to compare on, not enough
to choose on. Nobody buys a 25 kg sack without knowing what it is, what it looks
like, or whether it fits the shelf.

**Decision: optional detail on the SKU, and a page that shows it.** Description,
dimensions, weight, a gallery and a YouTube link, all optional — a listing with
none of it behaves exactly as it did.

### Dimensions are numbers, not a sentence
Length, width and height in centimetres, beside the `weight_grams` V29 already
added for delivery quoting. Free text could never be compared between two packs
or read by anything else.

### The gallery is separate; the thumbnail is not
`supplier_sku.image_url` stays where it is — every list, cart row and order line
reads it and none of them wants a join. The rest live in `supplier_sku_image`,
sent whole on save: reordering four pictures is one decision, and four calls for
it leave the gallery half-applied when one fails.

### A review needs an order line, and the order has to have completed
`sku_review` is keyed on `supplier_order_item_id`, unique. That is the whole
design: a review is a report of something that arrived, and anything weaker is
an opinion a competitor or the supplier themselves could have written. The same
restaurant reviews again by buying again, which is the right cadence — a pack
that was good in March and wet in June has two things worth saying.

Reviewing a line that already has one returns the existing review rather than
erroring, as order ratings do (§23A.23): the honest answer to "did that go
through?" is the review.

Separate from `rating`, which is about the order and the store. "It arrived
late" and "the paneer was wet" are different complaints, and a kitchen comparing
packs needs the second.

### Several SKUs per product, one card per supplier
Nothing in the schema ever stopped a supplier listing the same product in
several packs — the restriction lived in the app, which bounced you to the
existing SKU. It is gone: a supplier selling a 200 g tub and a 5 kg block has
two things to sell.

The comparison still shows **one card per supplier, their best pack**, because
every pack competing turns a comparison of suppliers into a comparison of one
supplier's shelf: a deep range fills the screen and pushes the others below the
fold, on a screen whose whole job is to put them side by side. The card says how
many other packs there are and the detail page lists them.

### The page never recomputes money
Price, GST and availability are the live offer; the pack price with GST is
`Pricing`'s, computed the way the order will compute it. A detail page that
priced a SKU differently from the row that led to it would be the worst possible
place in the app to disagree.

### What the product picker says
"3 listing this" counted suppliers across the marketplace, which tells a
supplier in a city of hundreds nothing about their own decision. "In catalog"
was a yes/no from when one product meant one listing. Both are replaced by how
many SKUs *this store* already lists under that product — and zero of them says
"new" without needing a word for it.

---


## D-097 — A store has its own contact, and it is required
**Raised 2026-09-20 · Settled 2026-09-20**

The contact on `supplier_organization` is whoever runs the business. An order is
filled by a **branch**, and the person to ring about it is whoever is on that
counter — which on a supplier with three stores is three different people.

`supplier_store.contact_name` and `contact_phone` have existed since V3 and were
optional. They are required from here on: at registration, and for any store
somebody saves.

### Backfilled, not constrained
V36 copies the organisation's contact into stores that have none. It stops
there, and the columns stay nullable, because **a store whose organisation has
no contact either has no truthful value to write**. Inventing one would put a
name and a number in front of a restaurant chasing a delivery that nobody can
answer. Of seven existing stores exactly one had an organisation contact to
copy; the other six stay empty until their supplier fills them in, which the app
now requires before it will save a store. D-089's split between a backfill and a
constraint is the whole reason this is safe to ship.

### `@NotBlank` belongs on the create body, never on the PATCH
Putting it on `UpdateStoreRequest` rejects **every partial update** — a client
toggling direct ordering sends that one field and nothing else. It did exactly
that for one build here, until a test of the toggle caught it. So: required on
`CreateStoreRequest`; on the update, absent leaves the field alone and blank is
refused by the service, which is the only place that can tell those two apart.

### The contact number pattern is loose on purpose
Digits, spaces, dashes and brackets, optionally led by a country code. This is a
number somebody dials, not an identity: the strict E.164 rule belongs on the
login phone, and applying it here rejects the landline with an STD code that a
warehouse counter actually answers.

### Onboarding copies, it does not assume
The business contact seeds the first store's, and the store's is held separately
the moment anybody types in it. Registration used to pass the signed-in phone
silently as both and never ask for a name, which is how every seeded store ended
up with a number and nobody to ask for.

Two smaller things fell out of this. `SupplierResponse` never returned the
organisation's contact at all, so the business settings screen opened with empty
fields over stored values and would have written the blanks back on save. And
the store settings screen held `contactName`/`contactPhone` in state and sent
them, with no input rendered for either — so every store saved whatever it
started with, which for most of them was nothing.


### Amendment (2026-09-20): either side may open a thread

D-095 limited opening to the restaurant, on the reasoning that a supplier
opening threads to kitchens is how a marketplace acquires a spam problem. The
`traded` check is what actually prevents that — a supplier with an order from
this outlet is not a stranger to it — and the restriction was instead stopping a
supplier answering a question about an order they are filling, which is the
wrong half to block. `POST /supplier-stores/{id}/chat/threads` is the same call
from the other side, with the same `traded` requirement.

Both request and order detail screens, on both sides, now carry the action in
their header. It opens the **pair's** thread, not a thread about that order —
the order is shared *into* it, which is what the share picker is for.

---

## D-098 — Razorpay's checkout opens on the client, and the adapter speaks Razorpay's documented API
**Raised 2026-09-26 · Settled 2026-09-26**

The Razorpay adapter existed from Phase 7 but had only ever run against the
mock, and the app had no way to open Razorpay's checkout at all — the pay screen
always called the mock-only simulation, which the server refuses against a real
provider. So `PAYMENT_PROVIDER=RAZORPAY` produced an app nobody could pay in.
Wiring the client exposed six places where the adapter and Razorpay disagreed.
Each was checked against Razorpay's own documentation, not inferred.

**The client opens the provider's checkout, chosen by the intent.** The app
reads `provider` off the payment intent — `RAZORPAY` opens Razorpay's window
(checkout.js on web, `react-native-razorpay` on iOS and Android), `MOCK` uses the
simulation. Never a build flag: a build and a server that disagreed about the
provider would fail in the one place it matters. The client sends no amount;
Razorpay reads it from the order the server created, so no paise are computed in
the app. Checkout ends at *which* payment; confirm still asks the provider.

**Native needs a development build.** `react-native-razorpay` wraps Razorpay's
native SDKs, which Expo Go does not contain. It is required lazily so Expo Go
shows "this preview build cannot open the payment window" rather than crashing.

What the adapter had wrong, and what changed (7 and 8 were found later, by
the test-mode suite and while writing it):

1. **Confirm accepted any payment id.** `/payments/{id}/confirm` fetched whatever
   id it was given and applied its state, so any authorised payment — a cheaper
   one, another outlet's — funded the order. `ProviderPayment` now carries the
   provider order id, and `PaymentService.completes` requires it to match the
   intent we minted (and, when money first appears, the amount). Confirm refuses
   with `VALIDATION_ERROR` and audits `PAYMENT_CONFIRM_MISMATCH`; the webhook,
   sweep and capture paths ignore a mismatch and log it at error.
2. **Every real webhook was malformed.** The event id was read from the body.
   Razorpay sends it only in the `X-Razorpay-Event-Id` header, which is also what
   they say to deduplicate on. The header now wins; a body `id` remains the
   fallback the mock uses.
3. **Reconciliation could not find a payment it only had the intent for.** If
   the client died after paying and the webhook was lost, the sweep skipped the
   payment because it had no payment id. It now asks
   `GET /v1/orders/{id}/payments`, for intents under a day old. Only a payment
   holding or having taken money counts: Razorpay lets a customer retry inside
   one order, so a declined first attempt is not the outcome.
4. **Manual capture used an undocumented flag.** `payment_capture: 0` is not in
   the current Orders API; the documented form is
   `payment: { capture: "manual", capture_options: { manual_expiry_period } }`.
   Had the flag been ignored, the dashboard default would decide.
5. **"Already captured" read as failure.** Razorpay refuses a second capture with
   a 400. After a capture whose response was lost, that refusal means it worked,
   and treating it as a rejection marked a captured payment `FAILED`. On any
   non-retryable capture refusal the adapter now fetches the payment and returns
   it if it is captured.
6. **Refunds were not idempotent at Razorpay.** The adapter sent
   `X-Razorpay-Idempotency-Key`, which Razorpay does not read. The documented
   header is `X-Refund-Idempotency`, with a key of at least ten characters — so
   the refund key moved from `refund-{id}` to `mandi-refund-{id}`. Orders and
   captures have no idempotency header at all; they rely on `uk_payment_order`
   and on point 5.
7. **An id the provider does not know no longer fails the payment.** Confirm
   used to mark the payment `FAILED` on any non-retryable lookup error — and
   `FAILED` is terminal, so one stale or garbled id from a client made the order
   unpayable for good. It is the client's claim that is wrong, so confirm now
   refuses it with `VALIDATION_ERROR` and changes nothing, like a mismatch.
8. **A webhook about a payment we never made stays `IGNORED`.** The handler
   marked it `IGNORED` and then overwrote that with `PROCESSED`, so the events
   kept precisely to make such cases diagnosable looked like ordinary work.

**Rejected:** trusting Razorpay's client-side `razorpay_signature` instead of
fetching the payment. It proves the checkout completed; it does not say what
state the payment is in now, and confirm's contract is that the provider is asked.

**Verified against Razorpay's test mode** on 2026-09-26 by
`costonomy-mp-mobile/tools/razorpay-e2e`: 27 cases, most of them failures —
declined cards, wrong OTPs, retries inside one order, a closed window, a lost
confirm recovered by the sweep and by a webhook, forged and duplicate webhooks,
refund idempotency — each asserting our database and Razorpay's own record. Two
full runs passed back to back. Point 8 was found by that suite.

**Still open.** UPI: the test account's checkout does not offer it, so the UPI
test ids cannot be exercised yet. Real webhook delivery from Razorpay (through a
tunnel) is simulated with correctly signed requests rather than observed.
OPEN-005 (the delivery fee) is unaffected.

---

## D-099 — No provider call holds a connection, and every payment write takes its lock first
**Raised 2026-09-27 · Settled 2026-09-27**

A review of D-098's work for load and concurrency, with Docker available so the
integration suite could run in full for the first time on that branch. It found
five defects; the concurrency ones only by writing tests that race two real
requests rather than reading the code.

**Provider calls ran inside database transactions.** `PaymentService.confirm`,
`performCapture` and `RefundService.process` called the provider while holding a
connection. The pool is ten and a provider read may take fifteen seconds, so ten
slow checkouts would stall every endpoint, not only payments. Each now asks the
provider with no transaction open and writes the answer in a short one, through
`TransactionTemplate` — not `@Transactional`, because a self-invocation would
bypass the proxy. A refund is claimed as PROCESSING and committed before the
call; one left there by a process that died mid-call is resent after five
minutes, which the per-refund provider key makes safe.

*Not changed (superseded by D-136):* order creation still called the provider inside
`IntentOrderCreator.create`; D-136 moves the call out.

**A retryable capture failure stranded the payment.** It returned the payment to
AUTHORIZED "so the job tries again", but the capture job reads only
CAPTURE_PENDING and nothing marks a confirmed order for capture twice. The
authorisation would lapse with the order confirmed and the supplier unpaid. It now
stays CAPTURE_PENDING. The test for it asserted AUTHORIZED and called that
retried; it now asserts two runs make two attempts.

**Confirm and a webhook deadlocked.** Each inserted a ledger row — a shared lock
on the payment through the foreign key — then asked for the exclusive lock to
update it. MySQL killed one: a 500 for a customer who had paid. Every state change
now takes `PaymentRepository.lockById` first. `OrderReleaseService.releaseIfFunded`
had the same race one level up, on the order — its own Javadoc promised "only one
release happens" and it did not hold under concurrency — so it locks the order.
Both are proven by five consecutive races in `PaymentFlowIT$Hardening`.

**All twenty-one jobs shared one scheduler thread** (Spring's default), so a slow
payment sweep delayed captures, the outbox, notifications and settlement.
`spring.task.scheduling.pool.size=8`; ShedLock still keeps each job single.

**The sweep would have hit rate limits.** It asked the provider about every
unpaid intent every minute for a day. It now backs off with age — a fifth of it,
two to thirty minutes — about sixty lookups per abandoned checkout instead of
1,440, recorded with a one-column update so it cannot overwrite a concurrent
confirm. Capture and reconcile runs are batched (100 and 200).

**Also fixed:**

- **The wallet top-up let anyone who can order credit their own wallet** with no
  money behind it. The endpoint is refused unless payments run on the mock — the
  same gate as checkout simulation. `WalletService.topUp` is unchanged, for a
  real funding rail to call.
- **A double tap on Create Order was a second order.** The app minted a fresh
  idempotency key per call, so the duplicate was refused on the spent delivery
  quote while the order had in fact been placed. The key now lives as long as the
  choices it was made for, and a second tap in flight is ignored. The quote's
  "already used" case has its own message instead of "belongs to a different
  request". `tools/razorpay-e2e` D1 fails on the old app and passes on the new.
  A truly simultaneous duplicate still gets a 500 from the server — within
  `IntentFlowIT$Concurrency`'s "whatever each call reported", but worth a clean
  conflict one day.

`StorefrontIT$Suppliers.radiusCountsWhatItExcluded` listed every supplier near
Hyderabad and failed once more of them existed; it now searches for its own
uniquely named stores.

Full suite: 319 unit, 326 integration, no failures.

---

## D-100 — A payment's story can be read from the logs, by its id, in order
**Raised 2026-09-27 · Settled 2026-09-27**

The database always held a payment's sequence — `audit_log` with before and
after, `payment_transaction`, `payment_webhook_event` — but the logs did not. A
real test-mode payment that was authorised by the sweep, marked for capture,
released and captured left **one** log line, and every audit row it produced had
`request_id` = `-`. Debugging from production logs meant knowing to open the
database first.

Four gaps, each closed where the work enters the system rather than in every
method:

1. **Job runs had no correlation id.** Every `@Scheduled` run now gets
   `job-<method>-<8 hex>` from `CorrelatedTaskScheduler`, in both the MDC (logs)
   and `RequestContext` (audit, outbox) — two separate thread-locals, and setting
   one would make logs and audit disagree. A subclass of Spring's scheduler,
   because `setTaskDecorator` arrived in 6.2 and this is 6.1; built from Boot's
   builder so `spring.task.scheduling.*` still applies. All 21 jobs, not only
   payments.
2. **No business ids on lines.** `TraceScope` puts `payment=… order=… rzp_order=…
   rzp_payment=…` (or `refund=…`, `rzp_event=…`) on every line in scope, via the
   `trace` MDC key the log pattern appends. Opened at the edges — the confirm and
   refund endpoints, the webhook, each job iteration, order creation — so a line
   written deep inside code that knows nothing of payments still carries them,
   including the error lines (the scope wraps the catch). Values are sanitised:
   webhook ids come from outside, and a newline would forge a line.
3. **Normal steps were not logged.** One INFO line per state change, next to each
   audit write that already marks one: `Payment 160 CREATED → AUTHORIZED via
   CONFIRM`, and the same for capture, release, failure and each refund step.
4. **Provider calls were invisible.** One line per Razorpay call — method, path,
   status, milliseconds; WARN when not 2xx. Never the body, which can carry a
   customer's contact details. The line to quote to Razorpay support, and the one
   that shows a slow gateway before the connection pool does.

Webhooks also log arrival and outcome (processed, duplicate, ignored, failed)
under Razorpay's event id.

**Not done:** JSON log output. It would make every id a searchable field in a log
platform, but the right encoder depends on where production logs go, which is
not decided. The `key=value` form is chosen so any platform can parse it until
then.

`TraceabilityTest` (4) and `PaymentFlowIT$Traceability` prove it — the latter runs
a payment through confirm, a real scheduler run and a late webhook, then finds
each step in the captured log output with its ids, and the same request and job
ids on the audit rows.

---

## D-101 — An attempt is not a payment, and a refund no one can finish goes to a person
**Raised 2026-09-27 · Settled 2026-09-27**

Three independent reviews of D-098 to D-100 (security, backend correctness,
mobile) found three critical and four high problems. This records the backend
ones fixed here. The pay screen's server side is D-102; the refund policy is
D-103 (cancellation) and D-104 (who may refund); the pay screen itself is fixed in
`costonomy-mp-mobile`.

**One Razorpay order takes several attempts, and only one of them is the payment.**
A declined attempt used to move the payment to FAILED, which is terminal; and
D-098's ownership check matched only the order, so a *different* attempt's
decline — late, retried, or deliberate — could fail a payment that was already
authorised or queued for capture. The order went ahead, capture never ran, the
hold lapsed, and the supplier delivered for nothing. Now:

- a declined attempt on an unpaid payment is recorded — a FAILED `AUTHORIZE`
  ledger row and the reason on the payment — and the payment stays payable;
- once a payment tracks an attempt, no other attempt can change it, except one
  that brings money to a payment that has none;
- an intent past the one-day window, asked once more and still unpaid, is
  expired (`INTENT_EXPIRED`) and its order abandoned.

**The sweep cannot be starved.** Ordered by `updated_at`, the rows it skipped
without writing stayed at the front, and 200 abandoned intents filled every batch
for good. It now takes the least recently asked first, records every ask, and
expires what is past the window.

**A refund is complete when the provider says it is complete.** Razorpay's
`pending` was read as done. It now stays PROCESSING with the provider's refund id
and is asked about (`GET /v1/refunds/{id}`), never resent.

**Refunds cannot be over-promised, and stop being retried when retrying cannot
help.** What can be refunded now subtracts refunds still on their way, under the
payment's lock. A provider's outright refusal goes to `NEEDS_REVIEW` at once, and
a transient failure after five attempts (`refund.attempts`, `V45`); both log at
error for an alert to match. Every claim changes the row, so two job runs cannot
both send one refund. A refund key now belongs to its payment.

**Also:** `markForCapture` and `releaseOrRefund` lock the payment (the D-099 500
survived one step later); a capture records the amount we asked for, not
Razorpay's full `amount`; the confirm id is validated (`[A-Za-z0-9_]{1,64}`) and
Razorpay call paths are sanitised in logs, and TraceScope values capped at 64;
blank Razorpay secrets stop startup; a `prod`/`production` profile refuses a mock
payment or OTP provider (`ProductionProviderGuard`); `applicationTaskExecutor` is
declared, since the scheduler bean suppressed Boot's.

**Not changed:** the webhook event id header is not covered by the signature — a
replay can cost a lookup but cannot move money, since state is re-fetched. The
refund key moved from `refund-{id}` to `mandi-refund-{id}` in D-098; no live
refunds exist, so no refund was sent under the old key.

### Found in passing, not fixed here: the supplier directory's 100 cap

`StorefrontService.searchSuppliers` takes the first 100 suppliers **by name**
(`order by display_name limit 100`) and only then sorts them by distance. In a
city with more than 100 suppliers, the nearest one is missing from a restaurant's
directory if its name sorts late. `StorefrontIT` hit it as the suite's shared
database grew — its tests now search for their own stores, and the one that
cannot use a term names its stores to sort first. The product fix (distance in
SQL, or paging) belongs to discovery, alongside ONBOARDING's open question on
serviceability.

### Direct orders and "payment only after the supplier accepts"

Settled with the product owner on 2026-09-27: a restaurant pays only after the
supplier has accepted. For a store with direct orders on (D-094), switching the
setting on **is** the supplier's standing acceptance of any order within its
listed stock and prices, so a direct order is paid at once. A supplier who cannot
fill one cancels it, and the restaurant is refunded (D-103).

---

## D-102 — The pay screen asks the server for the order's checkout
**Raised 2026-09-27 · Settled 2026-09-27**

The pay screen held the payment intent only in a query-cache entry nothing
observed, gone after five minutes and on any refresh. A bank or UPI flow can take
longer than that, and nothing else linked to the pay screen, so an order whose
screen lost its intent could never be paid from the app. And a Create Order retry
that found the order already made returned it with `payment: null`, which the app
read as "nothing to pay" — the same dead end, reached from the other side.

**`GET /supplier-orders/{orderId}/payment-intent`** returns the order's payment
as the pay screen needs it: the provider order to open checkout against, the
publishable key, `payable` (still CREATED with a provider order), `fundsSecured`,
and the last decline's reason. A read — it never creates a provider order, so
asking twice cannot charge twice. PAYMENT_CREATE on the payment's outlet; another
tenant's order is a 404.

**The "already ordered" return carries the open intent** through a new read-only
`OrderFundingPort.openIntent`, rather than re-running `arrangeFunding`. Empty for
funding with no client step (credit, wallet) and once the payment is funded or
ended.

---

## D-103 — Money is held until the order is ready, and taken there
**Raised 2026-09-27 · Settled 2026-09-27** · supersedes D-091's "Capture moved to confirmation"

D-091 captured at confirmation, about ten seconds after payment. That made almost
every cancellation a refund of money already taken — a charge and a reversal on
the restaurant's statement for an order that never happened, and a refund that
the cancellation path did not in fact issue (it logged "a refund is required" and
stopped). Settled with the product owner: **the money is taken when the supplier
dispatches**.

**The trigger is `READY_FOR_PICKUP`.** Every order passes through it, whether it
is collected, carried by the supplier or by a courier, and it is exactly where
cancellation closes (doc 01 §13, D-091). So:

- **Before ready**, the payment is AUTHORIZED — held, not taken. The order is
  funded (`fundsSecured`) and the supplier works on it. A cancellation, by either
  side, drops the hold: RELEASED, nothing charged, nothing to refund.
- **At ready**, `SupplierOrderTransitions.advance` marks the payment for capture
  (`OrderFundingPort.onOrderDispatched`) and the capture job takes it.
- **Capture and cancellation can never race**: an order cannot be cancelled once
  ready, and money is not taken before.

Credit is unchanged: it still draws at confirmation (`onOrderAccepted`), so
D-091's note on a supplier cancelling a credit order still stands.

**A hold has an end.** Razorpay's manual-capture hold lasts at most five days;
after that the money goes back to the customer on its own. The supplier is
refused at "ready" once a hold is within six hours of that
(`OrderFundingAdapter.canTakeFunds`, 409) — goods must not leave against money
that is about to lapse. The sweep checks held payments every six hours rather
than every few minutes, and logs at error once a hold is four days old with the
order not dispatched, for someone to resolve with both parties before the limit.

**If money was taken and the order is then cancelled** — only possible for an
order captured before this change — the full remaining amount is refunded,
system-initiated and keyed `cancel-order-{id}`, so a repeated cancellation
cannot refund twice. D-104 moves where such refunds go (the wallet).

Tests: `PaymentFlowIT$Capture` — held after paying and taken at ready; a supplier
cancelling while held releases it with nothing captured or refunded; no
cancellation once ready; a cancelled order already captured is refunded once;
an expiring hold refuses "ready".

## D-104 — A refund goes to the wallet; money leaves the wallet only back to the card
**Raised 2026-09-27 · Settled 2026-09-27** · part one, the money's path; part two, below, the dispute workflow that decides a refund · amends D-036 and D-048

Before this, a restaurant could refund its own captured payment:
`POST /payments/{id}/refund` needed only `PAYMENT_CREATE` on its outlet, and took
any reason and any amount up to the capture. Nobody on the supplier's side had to
agree, and the supplier was paid for the order all the same — the reviews of the
Razorpay work flagged it as critical. Settled with the product owner:

- **A restaurant cannot refund itself.** The endpoint is removed. A refund comes
  from a dispute the supplier approves, or ops approves after the supplier
  declines or does not answer within 48 hours (part two), or from the system
  when an order whose money was taken is cancelled (D-103).
- **A refund is credited to the outlet's wallet**, at once.
- **Money leaves the wallet only back to the card or bank it came from** — a
  provider refund on the original payment.
- **Costonomy never funds a refund.** The supplier bears every refund, including
  one ops approves over their decline, and Costonomy keeps its commission. Part
  two enforces it; it is recorded here because it shapes part one.

**How it is built.**

- `refund.destination` is `WALLET` or `ORIGINAL`. A `WALLET` refund
  (`RefundService.refundToWallet`) is completed in the same transaction as the
  wallet credit — no provider, nothing to wait for, nothing that can
  half-happen. It counts against the payment at once (`refunded_amount`,
  `PARTIALLY_`/`FULLY_REFUNDED`): the money is no longer the supplier's, it is owed
  to the restaurant, and settlement reconciliation (captured − refunded) is right
  without change.
- **A withdrawal** (`POST /outlets/{id}/wallet/withdraw`, new `WALLET_WITHDRAW`
  permission for owner, admin, purchase manager and finance staff) is split
  across the payments the wallet's refunds came from, oldest credit first. Each
  part is an `ORIGINAL` refund with reason `WALLET_WITHDRAWAL`, sent by the
  existing refund job — so it inherits D-099/D-101's retries, the pending check,
  NEEDS_REVIEW and the idempotency key at Razorpay. It does **not** add to the
  payment's refunded amount again: that moved when the money reached the wallet.
  Nor does it count as "in flight" against a later refund of the same payment.
- **Only card money can leave.** A top-up (mock only) or a wallet-paid order's
  return has no card behind it: it stays spendable and cannot be withdrawn. The
  most that can go back to one payment's card is what was refunded from it.
- **The wallet ledger says why.** `wallet_transaction.kind` (TOP_UP,
  ORDER_PAYMENT, ORDER_REFUND, REFUND, WITHDRAWAL), a unique `reference`
  (`refund-41`, `withdrawal-42`) so one operation cannot move the balance twice,
  and `refund_id`. The old unique key (one row per order and direction) would
  have refused a second refund credit on one order, so it now applies only to
  ORDER_PAYMENT and ORDER_REFUND, through a generated column. The statement
  shows each withdrawal's refund status, read live.
- **Cancellation refunds (D-103) go to the wallet** too.

**Concurrency.** Everything that decides what a wallet can give back holds the
wallet row (`SELECT … FOR UPDATE`), and takes it **before** the payment: a
withdrawal holds the wallet and then writes refunds against payments, so a wallet
refund taking them the other way round could deadlock with it. Two withdrawals of
the whole balance at once run one after the other and the second is told the
money is gone (`Withdrawals.concurrentWithdrawalsSpendOnce`, five races). Found
on the way: the lock was first taken on a wallet already loaded into the
persistence context, which Hibernate only version-checks rather than re-reads —
the waiting withdrawal failed on a stale version (409) instead of finding the
balance spent. `WalletService.lock` now locks before anything loads it.

**What a withdrawal that cannot finish looks like.** Out of the wallet, not on
the card: its refund goes to NEEDS_REVIEW and is logged at error, as every
provider refund that cannot finish is (D-101). The statement shows it. Putting
the money back into the wallet is a person's decision, because a refund that
failed with an unknown outcome may in fact have reached the card.

**Open.**
- **Legal.** Whether Costonomy may hold refunds as a wallet balance is a question
  for a lawyer (RBI's rules on prepaid payment instruments). Until that is
  answered this should not go live.
- **Razorpay's refund window.** Razorpay refuses refunds on old payments (the limit
  is set on the account). A withdrawal from a payment past it ends in
  NEEDS_REVIEW. Allocating oldest first uses the oldest credit while it can still go back.
- **Razorpay's balance.** A refund is paid from Costonomy's Razorpay balance; if
  it is short the refund fails and goes to a person.

Tests: `PaymentFlowIT$Refunds` (5) — credited at once with no provider call,
idempotent, capped at the capture, refused on a held payment, the self-refund
endpoint gone. `PaymentFlowIT$Withdrawals` (8) — sent to the card and counted
once, capped at the balance, only refund money, split oldest first with each
card capped, idempotent and key reuse refused, another tenant 404, concurrent
withdrawals, cancellation to the wallet. The provider-refund cases (stuck,
declined, pending, transient, no open transaction) now run through a withdrawal.

### D-104, part two — a refund is decided on a dispute, and the supplier pays for it

**The workflow.** A restaurant asks for an amount on a dispute
(`POST /disputes/{id}/refund-request`, `DISPUTE_CREATE`, once per dispute, once the
order is DELIVERED or COMPLETED — before that a problem is a cancellation, which
releases the hold). The supplier approves or declines with a reason
(`POST /dispute-refunds/{id}/approve|decline`, new `DISPUTE_REFUND_DECIDE` for
owner, admin, store manager and finance staff — not a salesperson, because it
gives money away out of the payout). If they decline, or have not answered in 48
hours, operations decides (`/admin/dispute-refunds`, new INTERNAL `REFUND_DECIDE`
for OPS_FINANCE and OPS_ADMIN; the queue needs `DISPUTE_INSPECT`, D-046). The
supplier may still answer after 48 hours, until operations has. Every step writes
to the dispute's thread, is audited, and raises `DisputeRefund{Requested,
Approved,Declined}` — named by `DisputeRefundStatus`, D-044 — which notify the
supplier (with the 48-hour clock) and the restaurant, and the supplier again on an
approval, since it is their payout.

**An approval moves money twice, in one transaction:** the supplier's payout is
charged (`SupplierRefundLedger.charge`, a `supplier_deduction`) and the wallet is
credited through the order's funding method (`OrderFundingPort.refundToWallet`:
a wallet refund of the card payment; a `DISPUTE_REFUND` credit for a wallet-paid
order, which has no card and so cannot be withdrawn; refused for credit, which is
settled between the two parties). Either refusal rolls both back.

**Costonomy never funds a refund.** Settled with the product owner — "make sure
Costonomy will not lose any money". Four rules, each tested:

1. **The supplier bears every refund**, including one operations approves over
   their decline. Costonomy keeps its commission on the order.
2. **Capped at the supplier's payout for the order** — its value less commission,
   less earlier refunds on it — and at the order's money. From the stored
   calculation once the order is settled, from `CommissionService.preview` before
   (never saved: a calculation row is what marks an order settled).
3. **Refused once that payout is approved.** After approval the money is committed
   to the supplier, and a refund would come out of Costonomy's pocket if they
   never traded again. The restaurant is told to contact support.
4. **A payout cannot be approved while a refund on one of its orders is
   undecided** — REQUESTED, or DECLINED and waiting for operations. Approval
   applies any deduction still pending first, and refuses a settlement whose net
   would be negative.

**How a deduction reaches the payout.** An order is settled the day after it
completes. A refund approved before that waits as PENDING and is applied, as a
DEBIT `REFUND` adjustment, by the generation that picks the order up; one approved
while the order's settlement is open is applied to it at once (the settlement's
version makes that and a simultaneous approval exclusive). The order is **never
held back from generation** instead: generation finds orders by the day they
completed, so an order skipped once would never be settled.

**Concurrency.** Deciding locks the request row, so a supplier and an operator
approving at once pay once (`NoLoss.concurrentDecisionsPayOnce`, five races). Lock
order: request, settlement, wallet, payment.

**Known edge.** The cap before settlement uses today's commission rate; if the
rate rises before generation, the deduction can exceed that order's net by the
difference. Approval refuses a settlement that nets negative, so it cannot pay out
wrongly — someone corrects it first.

Tests: `DisputeRefundFlowIT` (16) — approved, credited and taken from the next
payout; applied at once to an open settlement; visible on the dispute and both
lists; capped at the payout; refused after the payout; undecided holds the payout;
concurrent approvals pay once; an approval twice pays once; operations waits 48
hours and the supplier still pays; declined by both moves nothing; only the
supplier decides, and support cannot; once per dispute; only after delivery;
delivered but not received; a wallet-paid order's refund is spendable, not
withdrawable.

## D-105 — An order's payment status is read from its funding method, live
**Raised 2026-09-28 · Settled 2026-09-28**

`supplier_order.payment_status` was written once, at release, as `AUTHORIZED` —
whatever paid for the order, and never again. A wallet order therefore read
"Authorized" when its money was already paid; a credit order, when no money had
passed through Mandi; and a card order still said so after its money was taken,
refunded or released (a cancelled order looked as if money were still held).

Order responses now ask the order's funding method (`OrderFundingPort.paymentState`,
routed by `OrderFunding`, the same way as funding and refunds): a card payment's own
status (`AUTHORIZED`, `CAPTURED`, `RELEASED`, `PARTIALLY_REFUNDED`…); `PAID`,
`PARTIALLY_REFUNDED` or `REFUNDED` for a wallet; `ON_CREDIT`, `RELEASED`, `FAILED` or
`EXPIRED` for credit. Release writes the same value into the column, so it is right
when written; reports that read the column directly (the admin order search) can
still lag behind a later capture or refund.

Tests: `PaymentFlowIT$Capture.orderShowsItsPaymentLive` (held, taken, released),
`DisputeRefundFlowIT` (wallet: paid, then partly refunded), `CreditFlowIT` (on
credit, in the response and the column).

## D-106 — QuickScan, part one: pay any UPI merchant from the wallet
**Raised 2026-09-28 · Settled 2026-09-28**

**Why.** Everything Mandi has funded so far pays a supplier the restaurant
already has an order with. A kitchen also buys from shops that will never be on
Mandi — the corner vegetable seller, an ice supplier paid in person — and today
that means cash or a personal UPI app, off the platform and off the wallet
balance entirely. QuickScan lets a restaurant scan any shop's UPI QR code and pay
from the wallet the same way a UPI app pays from a bank account.

**What.** `POST /outlets/{id}/quickscan/payments` (new `QUICKSCAN_PAY`, granted
by default to owner, admin, purchase manager and finance staff — the same set
`WALLET_WITHDRAW` uses): a VPA, an amount, an optional name and note. The wallet
is debited for the amount plus a flat fee (`costonomy.mp.quickscan.fee`, zero for
now — the fee policy is undecided) in the same transaction the `quickscan_payment`
row is created in, `WalletService.lock` first, before any payout has been
attempted — the same shape as every other wallet write (D-104): the debit that
must never race a second click happens first, and the payout catches up with what
the ledger already recorded. A `PayoutProvider` port takes it from there
(`createPayout`/`fetchPayout`; only `MOCK` exists — RazorpayX is the natural next
adapter, once a test account exists to build it against), in the same claim →
call the provider outside any transaction → record the outcome shape as
`RefundService.process`, with the same `NEEDS_REVIEW` after `MAX_ATTEMPTS` (5)
for an outcome retries never resolved. `GET /outlets/{id}/quickscan/config` tells
the client whether WALLET is available (and why not, if the flag is off) and that
UPI-direct is not built yet.

**The legal flag.** `costonomy.mp.quickscan.enabled` (default `false`) gates the
whole feature, and `ProductionProviderGuard` now refuses to start a production
profile with it on, or with the payout provider left at `MOCK` — paying third
parties out of a wallet balance is the same open RBI/prepaid-instrument question
D-104 raised and left unanswered, and this is a second reason it should not go
live until a lawyer has answered it.

**Outcomes.** `PAYOUT_PENDING` (debited, payout not finished) → `PAID` (reached
the shop); `FAILED` (refused or reversed — the money goes back to the wallet,
`WalletService.returnQuickScan`, idempotent on `quickscan-return-{id}` so a
REVERSED arriving after a PAID settlement cannot return it twice); or
`NEEDS_REVIEW` (retries exhausted, outcome unknown) — deliberately **not**
auto-returned, because the payout may already have reached the shop, the same
reasoning D-101 uses for a refund NEEDS_REVIEW. `QuickScanJobs` is the safety net
for whatever the synchronous call inside the request does not settle: a PENDING
answer, a transient failure, or a process that dies mid-call. VPAs are masked in
every log line (first two characters, then the handle) the same way a card
number or token would be.

**Fees are undecided.** The column and the config response carry a fee today so
adding one later is a number, not a migration or an API change — `fee` defaults
to zero and nothing charges it until a rate is set.

**Two defects found after the above went in, both about the gap between the
wallet debit committing and the payout settling.** First: `QuickScanJobs`'
`findClaimable` used to treat any `attempts = 0` row as claimable immediately,
so a job run landing in the few milliseconds between `payFromWallet`'s debit
commit and its own synchronous `sendPayout` claim could win that claim first —
the request's own `saveAndFlush` then lost the optimistic-lock race and
`payFromWallet` threw, telling a restaurant its payment had failed (and marking
the idempotency key failed) although the wallet was already debited and the
payout was already in flight. Fixed two ways: `findClaimable` now only takes a
fresh (`attempts = 0`) row once it is older than `JOB_CLAIM_DELAY` (30s) — the
request that created it owns the claim until then — and `payFromWallet` never
lets a failure from its own `sendPayout` call escape after the debit has
committed; it logs a warning and returns the row's true state instead, because
the money has already moved and `QuickScanJobs` will finish the payout regardless.
Second: `findSettleable` had no age bound and nothing actually recorded a
check — `payments.save` on an entity with no changed field is a Hibernate
no-op, so a PENDING "touch" and an already-PAID row confirmed still PAID never
moved `updated_at` — so every open QuickScan payout was re-fetched from the
provider on every ten-second job run forever, a rate-limit and cost problem
against a real provider. Fixed with a `checked_at` column (V48, edited before
it shipped) that `settlePending` sets explicitly on every outcome it gets an
answer for, and a bounded, named backoff: a PENDING row is asked about at most
once a minute (`PENDING_CHECK_EVERY`), and a PAID row at most once every six
hours (`PAID_CHECK_EVERY`) and only for 48 hours after paying
(`PAID_WATCH_FOR`) — a reversal, if one comes, almost always shows within
hours of paying, and a later one is an ops matter, not something to poll for
indefinitely.

Tests: `MockPayoutProviderTest` (6, the amount-driven scenarios) and
`QuickScanValidationTest` (17, the VPA shape) as units;
`ProductionProviderGuardTest` (+2, the legal flag and the mock payout provider);
`QuickScanFlowIT` (18) — paid synchronously within the request, a refused payout
returned once even after a second job run, a pending payout settled to PAID, a
pending payout reversed and returned once (and not twice), a transient failure
retried to the cap then NEEDS_REVIEW with the money left out, an unexpected
(non-`PayoutProviderException`) error right after the debit commits still
returns 200 `PAYOUT_PENDING` with the wallet debited once and the job finishing
the payout on the next run, a fresh job-inserted row excluded from
`findClaimable` under 30s old and included once past it, a PAID row not
re-fetched within six hours of its last check but re-fetched once that passes
and never once 48 hours old, a PENDING row fetched at most once a minute,
over-balance, over-max, an invalid VPA and UPI-direct all refused with nothing
written, the same idempotency key replayed and a different amount on it
refused, another tenant 404 on pay and on read, a member without
`QUICKSCAN_PAY` 404 with nothing debited, two concurrent payments over the
balance settling to exactly one (five races), and the config endpoint;
`QuickScanDisabledIT` (2) — the flag off refuses the payment and says so on the
config endpoint, both with the wallet otherwise fully funded.

## D-107 — Adding money to the wallet through Razorpay, and where that money physically is
**Raised 2026-09-29 · Settled 2026-09-29**

**Where the money lives, plainly.** The wallet balance is a number in our MySQL
database: a ledger (`wallet_transaction`) and a total (`wallet.balance`) saying
how much we owe a restaurant. It is not money. The money is the cash Razorpay
collected when the restaurant paid: it sits in *our* Razorpay balance and settles
to *our* bank account on Razorpay's schedule. So a wallet is a **liability** —
rupees we hold for a restaurant and must be able to pay out (to an order's
supplier, back to a card on withdrawal, or to a shop by QuickScan) — and the
promise behind every rupee of it is that the same rupee is in our Razorpay
balance or our bank. A credit with no captured payment behind it is money we owe
and do not have; that is the one thing this design exists to prevent, and it is
why the mock top-up (D-099) is refused on a real provider.

**What.** `POST /outlets/{id}/wallet/top-ups` (`PROCUREMENT_SUBMIT`, scoped to the
outlet, `Idempotency-Key` required) validates the amount and the limits, writes a
`wallet_top_up` row (V45) and opens a Razorpay order for exactly that amount with
**`payment.capture = automatic`** — a top-up is captured when paid, unlike an
order (D-103), because there is nothing to wait for and a held authorisation
would lapse into money we never took. `PaymentProvider.AuthorizationRequest`
gained `autoCapture` (default false, so every order is unchanged). The client
opens Razorpay's checkout with `{ topUpId, razorpayOrderId, keyId, amount,
currency }` and calls `POST .../top-ups/{id}/confirm` with `{ razorpayPaymentId,
razorpaySignature }`. `GET .../top-ups/{id}` gives the status, and
`GET /outlets/{id}/wallet` now carries a `limits` object.

**Confirm trusts nothing the client sent.** The signature is checked first
(`PaymentProvider.verifyCheckoutSignature`: HMAC-SHA256 of `order_id|payment_id`
under the API secret — not the webhook secret, and the two are never accepted for
one another). Then Razorpay is asked what the payment is, and it is credited only
if it belongs to *this top-up's order*, is *captured*, and its amount equals the
amount *we stored*. A payment that is authorised but not yet captured, or a
Razorpay that cannot be reached, answers `TOP_UP_PROCESSING` (409): the money is
safe and the poller credits it.

**One method credits, so nothing can credit twice.** The client's confirm and the
background poller both end in `WalletTopUpService.settle`. In one transaction it
locks the outlet's wallet, then the top-up row (that order, always), re-checks the
limits against the freshly locked balance, moves the row out of CREATED/EXPIRED
with a conditional `UPDATE ... WHERE status IN (...)`, and writes the ledger
credit through `WalletService.creditTopUp`. Behind it: a unique
`razorpay_payment_id` per top-up, a unique ledger `reference` (`topup-{id}`), and
the unique `razorpay_order_id`. The transaction runs at READ COMMITTED: under
MySQL's default REPEATABLE READ a transaction that waited for the wallet lock
still read the month's total as it stood before the wait, and two top-ups could
each pass a limit that together they break. (A test that removes the wallet lock
fails, five races in a row, on exactly this.) No provider call holds a
connection (D-099).

**No captured money is ever left without a credit or a refund.**
- *Confirm never arrives* (app killed, network gone): `WalletTopUpJobs.poll`
  finds CREATED top-ups older than a minute, asks Razorpay for the payment on the
  order, and credits a captured one through `settle`. Asked every minute in the
  first hour, every half hour after (an abandoned checkout costs about fifty
  calls, not 1,440). After a day with nothing paid — checked at Razorpay first,
  never assumed — the row is EXPIRED. A payment that turns up after that is still
  credited: EXPIRED can become CREDITED.
- *Crediting would break a limit* (two top-ups racing past the maximum balance,
  say): the payment is not credited and not dropped. The row moves to
  REFUND_PENDING in the same transaction, and a provider refund of that payment
  is sent straight after commit with a key derived from the top-up
  (`mandi-topup-refund-{id}`), so a retry reaches the same refund. A refund
  Razorpay accepts as pending is followed up by asking, never sent twice; one that
  fails is retried by the refund job up to five times and then logged at ERROR as
  needing a person, with the balance untouched. The refund goes straight to
  Razorpay rather than through `RefundService`, deliberately: that machinery is
  built around an order's `payment` row and a supplier order, which a top-up has
  neither of. It borrows its shape (claim, send without a transaction, record,
  retry, hand to a person) rather than its tables.
- *A captured payment whose amount is not the stored amount* is neither credited
  nor refunded, and is logged at ERROR. Razorpay fixes an order's amount, so this
  should never happen; if it does, a person decides.

**Limits stand in for KYC.** `costonomy.mp.wallet.max-balance` (₹1,00,000),
`monthly-top-up-limit` (₹10,00,000), `min-top-up` (₹10) and `max-top-up`
(₹1,00,000) are checked when the top-up is created and again when it lands. A
wallet anyone can fill without limit is a place to park money whose owner we know
nothing about; KYC is what would allow that, and **KYC is not being built**, so
the limits keep the exposure small without it. A future KYC tier would raise them
per outlet, and `WalletLimits` is the one place they are read. "Month" is the
calendar month in Asia/Kolkata; `addedThisMonth` counts CREDITED top-ups only, so
the mock top-up, a refunded one and one still waiting do not count.

**Open item — legal, not code.** Holding restaurants' money in a balance they can
spend on orders, withdraw, and (D-106) pay third parties from is very likely a
prepaid payment instrument under RBI's PPI rules, which need authorisation or a
licensed partner holding the funds (an escrow or nodal account, or a PA/PPI
partner). Limits are a risk control, not a licence. Whether this may go live, and
under whose licence, is unanswered; it is the same question D-104 and D-106
raised. Until it is answered this should be treated as sandbox only.

**Known gaps.**
- The payment webhook is not wired to top-ups. A `payment.captured` event for a
  top-up's order is recorded as IGNORED (it matches no `payment` row); the poller
  is what credits an unconfirmed payment, within about a minute and a half. Wiring
  the webhook would make it instant; the poller would still be the net.
- After a FAILED top-up (Razorpay refused the order) a retry needs a new
  `Idempotency-Key`; the same key answers that the top-up has ended.
- The auto-capture order body (`payment.capture = automatic`) follows Razorpay's
  documented Orders API and is tested against a stand-in HTTP server, not the real
  sandbox.

Tests: `WalletTopUpIT` (52 across seven groups), `WalletTopUpLimitsIT` (5, small
configured limits), `WalletLimitsTest` (3, the IST month), `RazorpayPaymentProviderTest`
(+3: automatic capture, exact paise, checkout signature).


## D-108 — Wallet history and statements, and where each kind of money movement is recorded
**Raised 2026-09-29 · Settled 2026-09-29**

A restaurant needs to see what happened to its wallet (a scrollable history with month
headings and filters) and to hand its accountant a file (a statement). Both are *read
models*: nothing here moves money, and neither keeps a second copy of it.

**Where each kind of movement lives.** "Wallet money" is spread over five places, on
purpose, and the history and statements read only the first two:

| What | Where | Notes |
|---|---|---|
| Every change to the wallet balance | `wallet_transaction` (the ledger) | Append-only. Every row has `direction`, `kind`, `amount` and `balance_after`, written under the wallet lock in the same transaction as the balance, so `(created_at, id)` order is balance order. The source of truth for history and statements. |
| A payment made to add money | `wallet_top_up` (V45, D-107) | The attempt. A credited one also has a ledger row (`reference = topup-{id}`); one that was paid and *returned* (status REFUNDED) has none, because the balance never moved. |
| Money paid for an order | `payment`, `payment_transaction`, `refund` | Card, prepaid and credit orders. A refund credited to the wallet writes a ledger row (`REFUND`); a withdrawal writes a `WITHDRAWAL` ledger row pointing at its `refund`. The refund row's status is what says whether a withdrawal has reached the card. |
| A wallet-paid order | `wallet_transaction` (`ORDER_PAYMENT`, `ORDER_REFUND`, `DISPUTE_REFUND`) | No `payment` row: the ledger *is* the record (D-105). |
| QuickScan | `quickscan_payment` (D-106) | The payment and its payout. Its wallet effect is a `QUICKSCAN_PAYMENT` debit and, if returned, a `QUICKSCAN_RETURN` credit in the ledger. |

**History: `GET /outlets/{id}/wallet/transactions`** (`ORDER_VIEW`, scoped to the outlet,
like the wallet itself). Query `months`, `kinds`, `statuses`, `cursor`, `size`; all
optional. Newest first.

- **Keyset pagination on `(created_at, source, id)`, never offset.** A movement that lands
  while someone scrolls arrives at the front of a newest-first list, so a page boundary
  cannot repeat or skip a row; an offset would shift by exactly the number of new rows.
  Rows at the same microsecond are ordered by `id`, which is balance order. The cursor is
  opaque to clients and strictly validated (400).
- **Two sources in one list.** Ledger rows, plus top-ups that were paid and returned,
  shown as `TOP_UP` with status `RETURNED`, `balanceAfter` null and no effect on any
  total. The customer will look for a debit on their bank statement; the history has to
  explain it. Ids of the two tables can coincide, so each item also carries `key`
  (`L12` / `T12`). A returned top-up is dated when it was *started* (`created_at`), the one
  timestamp on that row that never changes, which a cursor needs.
- **Status is what the customer needs to know, not our state machine.** Ledger rows are
  `COMPLETED`, except a `WITHDRAWAL` whose refund is not `COMPLETED` yet: `IN_PROGRESS`,
  including when the refund is FAILED (being retried) or NEEDS_REVIEW (a person has it),
  because the customer's question is "has it arrived". `refundStatus` carries the detail.
  `FAILED` is accepted as a filter and matches nothing today: a top-up whose Razorpay
  order could not be created, or that expired unpaid, cost the customer nothing and is not
  shown.
- **Months are Asia/Kolkata months, computed in Java.** Each is turned into an instant range
  and sent to the database as instants; no SQL time-zone conversion, so the answer cannot
  depend on a database session's zone. 30 September 19:00 UTC is 1 October in India.
- **`monthTotals` are ledger truth, unfiltered by kind and status.** `added` is the sum of
  CREDIT rows, `spent` of DEBIT rows, for the months asked for (or, when none is asked for,
  the months on the page). A returned top-up is in neither. This is what the statement for
  that month says, so the header and the file cannot disagree because a filter was on.
  `availableMonths` lists every month with anything to show, whatever the filters.
- **`instrument`** ("Card •1007", "UPI", "Netbanking") says where a top-up's money came
  from. V46 adds `wallet_top_up.payment_method` and `payment_detail`, set once, from the
  payment Razorpay returns, at the moment the top-up is credited or returned
  (`PaymentProvider.ProviderPayment` gained `method` and `methodDetail`). Only a card's last
  four digits or a provider wallet's name is kept; never a full card number, a UPI address
  or a bank account, and anything that is not exactly four digits is dropped at the
  adapter. Null for every top-up before V46 and for a method Razorpay did not report; the
  history shows those without an instrument rather than guessing.

**Statement: `GET /outlets/{id}/wallet/statement`** (same permission). `range` LAST_30,
LAST_90, LAST_180, LAST_365 or CUSTOM (`from`, `to` as `yyyy-MM-dd`, inclusive, at most 366
days, `to` not in the future, `from` not after `to`), or `financialYear=2025-26` (1 April to
31 March; the year in progress runs to today; one that has not started is refused);
`format` PDF or CSV. LAST_n is n days ending today, today included. A period is whole IST
days: `from` 00:00 IST up to, not including, midnight after `to`.

- **Contents:** outlet name, period, opening and closing balance, total added, total spent,
  then every ledger row of the period oldest first: date-time IST, description (the same
  wording as the mobile app's `entryLabel`, `lib/wallet/entryCopy.ts`), reference (the
  order number, if the movement was about an order), direction, amount, balance after.
  The CSV adds the ledger's own note as a last column. A returned top-up is **not** on it:
  it never moved the balance.
- **It reconciles or it does not exist.** Opening is `balance_after` of the last row before
  the period (0 if there is none); closing is the last row's `balance_after` in the period
  (else opening). Before a file is written, every row's `balance_after` must follow from
  the row before it, `opening + added - spent` must equal `closing`, and, when the period
  reaches the last row, closing must equal `wallet.balance` (a different statement, in the
  same transactions). Any mismatch logs at ERROR and answers 500 rather than emit a
  plausible wrong statement: this is the file a restaurant gives its accountant. All reads
  are in one read-only transaction so they see one moment.
- **Bounded:** more than 20,000 rows is `422 STATEMENT_TOO_LARGE` ("choose a shorter
  period"), checked with a count before any row is loaded.
- **CSV:** RFC 4180 (CRLF, quoting, every line the same width). Text cells that start with
  `= + - @` (or tab or carriage return) are prefixed with a single quote so a spreadsheet
  does not run them: an outlet's name and a ledger note are text a person wrote. Amounts are
  plain numbers, never prefixed.
- **PDF: written by hand, no library.** openpdf, pdfbox and iText are not in the local
  Maven repository and the build runs offline, so adding one would break every build but
  the author's. `WalletStatementPdf` writes A4 pages with Helvetica and Helvetica-Bold (the
  standard fonts, nothing embedded), the table header repeated on each page and
  "Page n of m". The standard fonts have no ₹ glyph, so amounts read "Rs."; any character
  the fonts cannot draw becomes "?". If richer layout or Indic scripts are ever wanted, that
  is the time to bring in a library, with a font that has the glyphs.
- One INFO log line per statement (outlet, period, row count); no amounts.

**Not done.** No push notification or e-mail of a statement; no XLSX; no per-kind filter on
statements; the history does not show top-ups that are still waiting (CREATED) or being
returned (REFUND_PENDING), which the wallet home shows as before. The statement file's
wording is duplicated from the mobile app, not shared, because the two are in different
repositories.

Tests: `WalletHistoryIT` (18), `WalletStatementIT` (19), `StatementPeriodTest` (8),
`WalletStatementFilesTest` (9), one more in `RazorpayPaymentProviderTest` and in
`WalletTopUpLimitsIT` (a returned top-up in the history through the real flow).

## D-109 — Cancelling an order whose money was debited captures it and refunds it, and a lapsed hold is noticed
**Raised 2026-09-30 · Settled 2026-09-30** (owner recommendations E-1 to E-6 accepted as defaults; the fee and instant-refund choices are still the owner's, see below)

**The finding.** A live test-mode run (e2e O2) paid an order by UPI, had the supplier
cancel it before "ready", and looked at Razorpay two and a half minutes later: the payment
was still `authorized`, the payer's account debited, no refund, no capture. Our database
said `RELEASED`, "nothing was taken". A card is only a hold; **UPI, netbanking, a wallet
app, pay-later and cardless EMI debit the payer the moment they are authorised**, and
Razorpay will not refund an authorised payment until it is captured. D-103's "a cancellation
drops the hold, nothing is charged" is true of a card and false of everything else, and the
restaurant was out of pocket until Razorpay's own expiry gave the money back.

Two defects in the same code, fixed here because the cancel flow cannot be right without them:
- **The cancel transaction called Razorpay.** `releaseOrRefund` fetched the payment inside the
  transaction that cancelled the order, a fifteen-second read with the transaction open, which
  breaks D-099. Its answer was only logged.
- **A lapsed hold was never noticed.** Razorpay returning an uncaptured authorisation at
  expiry arrived as "refunded", which no transition allows from `AUTHORIZED`, so it was logged
  as out of order and the payment stayed `AUTHORIZED` for good. `canTakeFunds` then trusted our
  own clock: five days less six hours, while Razorpay's documents say both three and five. If
  the limit is three days, a supplier could mark ready on day four against money already
  returned, and the goods would leave unpaid.

**Options.** A: capture on cancel, then refund (capture at ready unchanged). B: capture UPI at
authorisation. C: shorten `manual_expiry_period`. D: UPI Reserve Pay.
- **A, chosen.** It changes only the cancel path, decides from Razorpay's live answer at the
  moment it matters, works when the method was not known at order creation, and needs no
  backfill. Cost: the gateway fee on each cancelled non-card order, which Razorpay is believed
  to keep on refund (unverified, V-4).
- B rewrites the happy path for the dominant method and reverses D-103 for it; it stays a valid
  later choice, and A's machinery would not change if it were added. C is impossible: the order
  is created before the payer picks a method, so a short expiry would also lapse card holds.
  D is the only true UPI hold and is a separate product, for later.

**The rule is an allow-list: only `method = card` may lapse.** Anything else, and a method we
have not read, is debited money and is returned. A new or unfamiliar method is therefore
refunded, never left to lapse. Cards are exactly as before: `RELEASED`, no Razorpay call.

**State machine.** `payment` gains `CANCEL_PENDING` (allowed to `CAPTURED`, `RELEASED`, `FAILED`),
reached from `AUTHORIZED`, and `release_reason` (`CARD_HOLD_DROPPED`, `PROVIDER_AUTO_REFUND`).
`CANCEL_PENDING` is neither `fundsSecured` nor holding funds, so nothing releases the order to a
supplier or takes the money for it; `markForCapture` refuses it; and `applyProviderState` never
moves it, so the `payment.captured` webhook for our own cancel-capture cannot write CAPTURED
with no refund behind it (the job writes CAPTURED and the refund in one transaction).

1. **Cancel transaction** (`OrderFundingAdapter.onOrderUnfulfilled` → `PaymentService.onOrderCancelled`).
   No provider call. Sets `cancel_requested_at`, then: a card hold is released; anything else
   goes to `CANCEL_PENDING`; a payment still `CREATED` is only marked (money that arrives later
   is sent back); a captured payment is refunded as before, to the wallet (legacy, orders
   captured before D-103). The order is CANCELLED and the payment says what is owed, atomically.
2. **`PaymentJobs.settleCancellations`** (every 15 s, ShedLock `payment-cancel`, 50 a run, least
   recently worked first) calls `CancellationService.settle` per payment in three steps: claim
   under the lock and count the attempt; ask Razorpay (`inspect`, no transaction); write the
   outcome under the lock, re-checking the payment is still `CANCEL_PENDING`. **Razorpay's
   answer decides, not our stored method:** a card is released; an authorised non-card payment is
   captured with key `cancel-capture-{paymentId}`; a payment already captured (our capture's
   answer was lost) is recorded without a second capture; a hold Razorpay returned itself is
   recorded `RELEASED`/`PROVIDER_AUTO_REFUND`. A payment that is not exactly the order's own, or
   one refunded outside Mandi, or failed, is stopped (`review_required_at`, ERROR) and **never
   captured or released on a guess**; the job skips it until a person looks. **Only Razorpay
   saying it does not know the payment stops a payment this way: HTTP 404, or the HTTP 400
   `BAD_REQUEST_ERROR` "The id provided does not exist" that Razorpay really answers on `GET
   /v1/payments/{id}` (the provider maps that body, and no other 400, to NOT_FOUND).** A
   rate limit (429), refused credentials (401/403), an outage (5xx) or any other refusal says
   something about the call, not the payment: the payment stays `CANCEL_PENDING` with no state
   change and is asked about again next run (429 also stops the rest of that run, WARN; 401/403
   is an ERROR line, "refused our credentials"). Review is not a dead end: a payment in review
   is asked about again every three hours and moves only where the provider's answer is final
   and matches the order (returned by Razorpay itself: `RELEASED`/`PROVIDER_AUTO_REFUND`;
   captured with nothing refunded: the refund is raised), it is written to the ERROR log again
   after three hours and then daily while it stays, and operations can clear the flag
   (`POST /api/v1/admin/payments/{id}/clear-review`, `PAYMENT_RECONCILE`, a reason, audited)
   to hand it back to the normal run.
3. **The refund** is one row, key `cancel-order-{orderId}`, the same key the legacy wallet path
   uses, so at most one cancellation refund can exist whichever path raises it. It goes
   through the ordinary refund job, now sending `receipt = mandi-refund-{id}`, `notes`
   (`mandi_refund_id`, `mandi_payment_id`, `purpose`) and `speed`. A refund Razorpay rejects goes
   to `NEEDS_REVIEW`; **it is never credited to a wallet, because the money never came from one.**
   A refund answered 429 or 401/403 is not "declined": it goes back to `FAILED` and is retried
   every run with the same key however many times it takes (past the usual five attempts too),
   with an ERROR line for refused credentials, never `NEEDS_REVIEW`.

**Crashes.** Before the cancel commits, nothing changed. After it, `CANCEL_PENDING` and the job
finds it. Capture sent, answer lost: the next run's `inspect` says captured and the capture is
never re-sent. Crash between the capture and the refund row: same, the job records both together
next run. Razorpay's own expiry racing our capture: either wins and the payer is repaid exactly
once, and the loser's refusal is not an error, the next `inspect` says which.

**Races.** Cancel against "ready" is settled by the supplier order's version, as before: one
save wins and the loser writes no payment state (`concurrentCancelAndReadyOneWins`, five rounds).
Two runs settling one payment, or two job runs, are made safe by the payment lock, the status
re-check in every transaction, and the unique refund key.

**Late money.** A payment finished after the restaurant cancelled a draft (`cancel_requested_at`
set on a `CREATED` payment) goes `AUTHORIZED → CANCEL_PENDING` in the same transaction, so no
reader sees it funded and the supplier never sees the order. Money that reaches a payment whose
intent had already expired (`FAILED`/`INTENT_EXPIRED`) is moved to `CANCEL_PENDING` by an explicit
`reopenForReturn`, not a generic transition (`FAILED` stays terminal), and only after checking
the amount is ours. Any other `FAILED` payment is never reopened.

**One hold limit, stored on the payment.** `costonomy.mp.razorpay.manual-expiry-minutes` (12 to
7200) is the `manual_expiry_period` sent with every **new** order, and `PaymentHoldPolicy` reads
it once, when the order's payment is created, and stores it on the payment
(`payment.hold_minutes`, what Razorpay was told). The guard on "ready", the four-day warning and
the past-the-limit alert all measure against **the payment's own figure**, not the setting as it
reads today: Razorpay fixes an order's expiry when the order is made, so raising the setting to
7200 must not stretch the guard for the orders already made at 4320, which Razorpay still returns
at 72 hours (the failure this decision exists to prevent, reintroduced by its own change
procedure). A payment with no stored value falls back to the current setting. So the setting can
be raised at any time; it only takes effect for orders made afterwards. **Default 4320 (three days), until Razorpay confirms the limit**,
so "ready" is refused after 66 hours instead of 114 (E-6). The sweep now recognises a returned
authorisation as `RELEASED`/`PROVIDER_AUTO_REFUND` (the adapter reads Razorpay's `refunded` with
`captured = false` as a returned hold; silence is read as taken), logs ERROR, and `canTakeFunds`
is false from then. A provider "refunded" on a payment we hold as captured is ignored with a WARN:
refund rows decide `FULLY_REFUNDED`, not a webhook ahead of them.

**Money captured only to be refunded funds nothing.** A payment the job captured to send back
is `CAPTURED` like any other, and reading it as funded would let an order released to its
supplier and marked ready against money that is on its way to the payer. So "funded" is
`Payment.fundsSecuredForOrder()`: the status says the provider holds or has taken the money
**and** `cancel_requested_at` is null. It is the one test behind `isFundingSecured` (and so
`releaseIfFunded`), the payment API's `fundsSecured`, and the webhook/sweep/confirm follow-up,
which now share one rule (`PaymentFollowUp`): release if funded, end the draft if the payment
failed, and end the draft if the order was cancelled (the confirm call had no such branch, so a
draft that the sweep expired but never abandoned could stay open and be released by the webhook
for our own capture). `canTakeFunds` is false whenever `cancel_requested_at` is set. Both
the confirm and the webhook path are covered by
`PaymentFlowIT$CancelDebited.draftPaidLateAfterExpiryIsNeverReleasedWhileItsMoneyIsReturned`.

**Money analysis.** The restaurant is repaid in full, exactly once, or the payment is stopped
with an ERROR for a person. A cancelled-before-ready order has no supplier payout and no
commission, so nothing is clawed back. Costonomy bears the gateway fee on each cancelled
non-card order (recorded per payment in `provider_fee`: Razorpay's `fee`, which already
includes the GST on it; `tax` is a part of `fee`, not an addition), and needs Razorpay balance for the
refund straight after the capture: the fee must come from other money, and a refund declined for
balance goes to `NEEDS_REVIEW` with the money still in Costonomy's Razorpay account.

**Owner choices left open.** E-2 who bears the fee (built: Costonomy absorbs, recorded; a
supplier deduction is not built). E-3 instant refund: `costonomy.mp.razorpay.cancel-refund-speed`
(`normal` default; `optimum` refunds instantly where Razorpay can, at a per-refund fee Costonomy
pays; never deducted from the restaurant, and withdrawals are always normal). E-5 debit-card
holds block funds until expiry, and card EMI is refunded as non-card. E-8 keep a Razorpay balance
float. E-4 capture-at-authorisation for UPI (B) stays a later option.

**What the apps read.** `paymentStatus` on an order gains `RETURNING` (CANCEL_PENDING, or captured
with the cancellation refund still open), `RETURNED` (Razorpay returned it itself) and
`RETURN_DELAYED` (a person has been told); `paymentInstrument` (`card`, `upi`, `netbanking`…) is
new, for wording only: **"not charged" is true only of `card` + `RELEASED`.** The restaurant is
notified when the refund starts (`RefundRequested`, without "5-7 working days" when instant
refund is on) and when it lands (`RefundCompleted`, worded for where the money went); completed
refunds now name their outlet, so they reach someone. Two refunds send no `RefundCompleted` of
their own, by explicit variant: a dispute refund to the wallet (the dispute's own
`DisputeRefundApproved` already told the restaurant, so a second push was noise) and each part of
a wallet withdrawal (it would read "refunded" and open whichever old order the money was drawn
from; before this branch nobody was told at all, so this is no loss). The order JSON also gains
`refundAmount` (the amount of the cancellation refund to the source account, from the moment it is
raised) and `refundedAt` (when it completed, null until then), both null on any other order.

**Alerts.** The "still CANCEL_PENDING after 15 minutes" and "past the provider's hold limit" ERROR
lines are written once an hour per payment, not every run (a run is every 15 seconds); the wait is
counted from the later of the cancel and the money's arrival, so a draft cancelled last week and
paid this morning does not report thousands of minutes. A run of the cancellation job has a
three-minute budget inside its five-minute lock and always settles at least one payment, so it
cannot outlive its lock and start a second instance on the same batch.

**Known limitations, unchanged by this decision (F10).** (1) A `CREATED` payment past the
one-day lookup window whose Razorpay order has only a returned attempt (refunded, now read as
`RELEASED`) is not expired by the sweep, because the lookup finds something: it is asked about at
every sweep run for good and its order stays a draft. Behaviour identical to before; ops can
end it by hand. (2) An `AUTHORIZED` payment that Razorpay shows `refunded` **with** `captured =
true` (captured and refunded by hand in the dashboard) is ignored as an out-of-order event, so
its order can still be marked ready and the capture then fails. Ops-only, and not something the
apps can cause. Neither is made worse here; both are recorded in `docs/RAZORPAY.md`.

**Second review (N1-N6).** (N1) Razorpay answers an unknown payment id with HTTP 400, not 404, so
"only a 404 goes to review" never fired in production and such a payment waited for ever, order
`RETURNING`. The provider now reports that one body (`BAD_REQUEST_ERROR`, "The id provided does not
exist") as NOT_FOUND; other 400s still wait, with their warning once an hour per payment. The exact
body is unverified in test mode (V-7). (N2) A send refused with 429 or 401/403 no longer counts toward
the five attempts, so a rate limit then one 5xx cannot send a refund to `NEEDS_REVIEW`. (N3) The
payment-intent read is not payable, and hands out no checkout key, once the order was cancelled: a
payer who paid it would have cost Costonomy the gateway fee. (N4, part) A 429 or 401/403 stops the
refund run as it stops the cancellation run, and a refund `FAILED` for more than an hour is an hourly
ERROR. Looking a refund up at Razorpay by receipt before resending it is **not** built here; it
belongs to the next branch (withdrawal failure). (N5) The credentials ERROR lines and the "run
stopped" warnings are throttled (one shared `AlertThrottle`), and a run that stops early still
writes the reminders for payments waiting for a person. (N6) The concurrent-refund test now calls
`RefundService.process` from two threads instead of the ShedLock-serialised job. (N7) not built:
a refund made by hand in the dashboard has no API exit (runbook in `docs/RAZORPAY.md`).

**Tests.** `PaymentFlowIT$CancelDebited` (40 at the first cut, 89 after the second review, 76 with the first review's
regression tests: F1 draft paid late, F2 429/401/403/5xx/400/404 on the lookup and recovery, a
payment in review that Razorpay then returned, the ops clear, a rate-limited refund, F3 the
stored hold, F5 notifications, F6 the method, F7 alerts, F8 the run budget, two real pending rows
in one run, a second refund run during the first one's provider call) and `OperationsIT`; before that: UPI, every non-card method, unknown method decided
live (card and UPI), restaurant and supplier cancel, duplicates, a lost capture answer, Razorpay
expiry before and during, transient and persistent failures, the webhook for our own capture, a
draft cancelled then paid (by confirm and by the sweep), late money after intent expiry, cancel
against ready and simultaneous settle runs (five rounds each), a rejected refund, a mismatched
payment, a payment refunded outside Mandi, the hold limit, receipt/notes/speed, instant refund on
and off, the notifications, the alert, and the log story with the job's run id. Unit: state
machine, adapter (method, fee, `amount_refunded`, returned-hold reading, expiry setting),
`PaymentHoldPolicy`, notification wording.

**Open verifications (test mode, before sign-off).** V-1 Razorpay really returns an uncaptured
UPI and card authorisation at `manual_expiry_period` (12 minutes is the minimum). V-2 the longest
hold: 4320, 4321, 7200 and 7201, then ask support. V-3 the exact error text for a capture after
expiry. V-4 whether the fee is kept on refund, and the instant-refund fee.

## D-110 — A withdrawal part the provider will not send comes back to the wallet, on proof that it did not leave
**Raised 2026-09-30 · Settled 2026-09-30** (owner recommendations E-7 to E-10 accepted as defaults)

**The finding.** A live test-mode run (e2e O3) withdrew wallet money whose source payments Razorpay
did not know (stale keys from an earlier test account). Each part is a partial refund on the original
payment, sent by the refund job *after* the wallet had been debited. Razorpay refused them; the refunds went
to `NEEDS_REVIEW`, and the money had left the wallet and gone nowhere, with no way back except the database.
D-104 said "putting the money back into the wallet is a person's decision"; this amends that: it is
automatic **only on a definite rejection plus reads of Razorpay's refund list and payment that show the payer was
not refunded**, and a person's decision in every other case.

**Principles.**
1. The pre-check before the debit is advisory. Razorpay can change its answer between the check and the send,
   so correctness rests on what happens after a refusal, not on the check.
2. **The system puts money back only on proof that the payer was not refunded.** Proof is all of: a definite refusal
   (a 4xx that Razorpay answered, not a timeout); `GET /v1/payments/{id}/refunds` showing no refund of ours (matched by
   the `receipt` we send, `mandi-refund-{id}`, or `notes.mandi_refund_id`; a refund Razorpay reports `failed` is not
   money that left), read at least two minutes after the first send because the list may lag; the same list showing
   **no non-failed refund that is not ours** (no `mandi-refund-` receipt, and not one we recorded); and
   `GET /v1/payments/{id}` showing `amount_refunded` no larger than our own listed refunds explain. Wording plays no part:
   *(amended after review: the first version put back any definite refusal whose list held none of ours, so a payment
   refunded in the dashboard with a refusal in words we did not recognise was credited to the wallet as well as the
   card.)* What is guaranteed is exactly this: no automatic re-credit while Razorpay's records show the payer was, or may
   have been, paid another way, or cannot be read. It is not a guarantee about a refund Razorpay has not made yet; the
   late-success watch is for that.
3. Ambiguity is never put back automatically. It is retried with the same key, looked up before every resend,
   and escalated.
4. One reversal per part, three ways: a conditional status change on the locked refund row (`REJECTED` or
   `NEEDS_REVIEW` to `REVERSED`), the unique wallet reference `withdrawal-reversal-{refundId}`, and the wallet lock.
   Lock order is wallet, then refund, then payment. The credit is the wallet's atomic update
   (`WalletService.creditWithdrawalReversal`), like every other balance change.
5. A source Razorpay will never accept is **blocked** (`payment.provider_refund_blocked_at`): withdrawals skip it and
   its wallet money stays spendable (E-10; an ops-assisted bank payout is a separate decision, not built).

**Before the debit** (`WalletWithdrawalService`, `WithdrawalSources`). Three phases, no provider call with a
connection held: read the sources (no lock), ask Razorpay about them in order until enough is covered, ten sources
or twenty seconds (`inspect`), then under the wallet lock recompute from the database and cap each checked source by
what Razorpay says is left less our own refunds it may not have counted (`unconfirmedOn`, from five seconds before
the read; never negative). A payment Razorpay does not know, that is not captured there, or is refunded in full is blocked;
one that cannot be read, does not match, or is older than `refund-window-days` (180, V-6) is skipped and not blocked.
More than can go back is refused whole, **422 `WITHDRAWAL_EXCEEDS_REFUNDABLE`** with `details` `requested`,
`withdrawableNow`, `blocked`, `unavailable`, `reason`: the app offers a new request for `withdrawableNow` (E-7, accepted:
refuse and offer, never silently withdraw less; the response amount always equals the request). This also
replaces the earlier 400 for "more than can go back to a card" (a wallet with no card behind it, a top-up). A
request above the wallet balance stays a 400. **Circuit breaker:** a refund refused for `INSUFFICIENT_BALANCE` or
`CONFIG` in the last thirty minutes returns **503 `WITHDRAWALS_PAUSED`** before anything is read or debited; so does a
double credit found by the late-success watch, for that outlet only. A pause is a refusal before anything was
done: the client's idempotency key is released, not marked failed, so its retry with the same key works after the pause.

**Classifying a refusal** (`RazorpayPaymentProvider.refundFailureKind`; the error body's description is read, cut to
120 printable characters and never logged whole). `PaymentProviderException` carries a `ProviderFailureKind`. The text only
decides a label and whether to block; **it never decides that money did not move**, so a reworded message can at
worst be filed as `REJECTED_OTHER` (verified, put back, not blocked; a second one for the same payment blocks it as
`OPS`). Kinds: `AMBIGUOUS` (timeout, 5xx, unreadable answer, idempotency conflict), `THROTTLED` (429), `CONFIG` (401,
403), `PAYMENT_UNKNOWN` (404, or 400 "does not exist"), `ALREADY_REFUNDED`, `OVER_REFUND`, `NOT_CAPTURED`,
`WINDOW_PASSED`, `INSUFFICIENT_BALANCE`, `REJECTED_OTHER`, `PROVIDER_FAILED` (made, then reported failed: asked again after an
hour). See `docs/RAZORPAY.md` section 7 for what each does.

**States.** `RefundStatus.REJECTED` (a definite refusal, verification pending) and `REVERSED` (terminal: the money never left
and is back in the wallet, or was redirected to it by operations). `withdrawableByPayment` counts a withdrawal
part as spent unless it is `REVERSED`, and marks blocked payments. New refund columns `failure_kind`, `sent_at`,
`verified_at`/`verified_result`, `failed_at`, `reversed_at`/`reversed_by` and a pending second-approver request; new
payment columns `provider_refund_blocked_at`/`_reason` (V48).

**Verify before every resend.** A refund claimed before (`sent_at` set, or attempts above zero) is never sent on a hunch: the
job lists the payment's refunds by receipt first, adopts one of ours (completing it, or following it while pending),
sends only if none is there, and does not send if the list cannot be read (the attempt counts, except a 429 or refused
keys). This closes the D-109 limitation that resending relied on Razorpay remembering `X-Refund-Idempotency`
for an unknown time (V-5): a withdrawal part is a partial refund, so a forgotten key would have paid twice.

**Watching what is put back.** After a reversal the list is read at ten minutes, one hour and six hours, then daily for
fourteen days (from `reversed_at` and `verified_at`, checked every minute; *amended: it was daily from the day after,
so the money could be spent before anyone was told*). A refund of ours turning up is
`CRITICAL refund N was REVERSED but provider refund X exists: restaurant credited twice`, marks the payment for a
person, publishes `WithdrawalDoubleCredit`, and **pauses the outlet's withdrawals** (`refund.late_success_at`) until ops
resolves it (`resolve-late-success`); spending stays allowed. It does not claw back: the balance may be spent and cannot go negative.
A refund in review after an ambiguous send is read hourly for a week and adopted if Razorpay made it.

**Operations** (`REFUND_OPERATE` for `OPS_FINANCE` and `OPS_ADMIN`, V48; read needs `PAYMENT_INSPECT`; a note on
every action; audited with the actor). Verify, retry (reads first), **re-credit** a withdrawal part (a verification under
ten minutes old showing none of ours, an explicit confirmation, evidence when the outcome was never known, and **a
second person above ₹10,000**, E-9, accepted), **send a cancellation refund to the wallet** (`cancel-wallet-{id}`, same
gates), **mark completed** (Razorpay must list that refund under the payment, processed, for the amount, not used by another
refund), block and unblock a source, and **`returned-outside`** for a cancelled order's payment that someone refunded by
hand in the dashboard (Razorpay shows it refunded with `captured = true`; verified, then recorded as refunded, with the cancellation
refund recorded under its usual key). The last replaces the hand-run SQL in `docs/RAZORPAY.md`, whose known
limitations 3 to 5 are closed by this decision.

**Deviations from the design, and why** (the lead's design is `design-upi-cancel-withdrawal.md`):
- `to-wallet` needs the same fresh verification, confirmation and second approver as a re-credit; the design was silent, but
  it is money going to the restaurant that the provider did not send, so the safest reading applies the same gates.
- A cancellation refund's `REJECTED` state goes to `NEEDS_REVIEW` at once, as before; only a withdrawal part is put back.
- The pre-check also counts a refund of ours that completed after the read (not only pending ones), and sets the mismatched-order
  case aside instead of trusting it: both can only lower what is allowed.
- The ops queue and detail read use `PAYMENT_INSPECT` (D-046's read counterpart), not `REFUND_OPERATE`.
- The reversal audit and the ambiguous read use `verified_at` on the refund as their schedule, not memory, so a restart
  neither skips nor repeats them. (The bulk statement that stamps `verified_at` sets `updated_at` to itself: the column has
  `ON UPDATE CURRENT_TIMESTAMP`, and it is the clock of the last decision that the breaker and the review window read.)
- **A latent race, found by the concurrent-withdrawal test and fixed here.** `WalletService.lock` began with a plain
  SELECT, which under REPEATABLE READ fixed what every later plain SELECT in the transaction saw. A withdrawal that
  waited for the wallet lock then decided what a payment could still give back from figures that left out the
  withdrawal ahead of it. Before this branch only the balance guard (a locking `update`) stopped both, so two
  withdrawals could each take the same card money when the balance covered both and the card did not. The lock is now
  the first statement, and the debit's transaction is READ COMMITTED.
- A system read that ends in review is not recorded as a verification: an operator who acts on the refund reads the
  provider themselves (the fresh-verification rule needs a person's `verify`).

**Amended after the second review (2026-09-30; findings in `review-api17.md`).**
1. *The proof above* (foreign refund on the list, `amount_refunded`, both reads succeeding), and an over-refund wording
   ("exceeds the refundable balance") is classified before the balance one so it is never read as our account's balance.
   `ALREADY_REFUNDED` and `OVER_REFUND` are no longer special cases: they pass through the same proof, so an over-refund whose
   payment holds only our own refunds (an over-estimated allowance) is put back instead of stranded in review.
2. `refund.provider_refund_id` is uniquely indexed (V48); adoption, mark-completed and the legacy match refuse (to review) when
   another refund holds the provider refund, inside the locked transaction.
3. The late-success schedule and pause, above.
4. The amount a refusal offers is computed with the formula the plan uses, so asking for it is accepted.
5. `unconfirmedOn` counts every refund of ours still in flight (with or without a provider id) and any changed since the read
   (`updated_at`, the last send, not `sent_at`, the first).
6. `WITHDRAWALS_PAUSED` releases the idempotency key.
7. Two different payments in a row that Razorpay does not know (pre-check, send, or refund list) are a configuration fault:
   nothing is blocked, put back or sent, one throttled ERROR. A 404 on the refund list is not proof that no earlier send
   exists: no resend, no reversal, review.
8. A rate-limited refund no longer ends the run before rejected refunds are settled.
9. Production refuses any explicit value of `withdraw-precheck` but `true`.
10. A person cannot put back an `AMBIGUOUS` refund within `recredit-min-age` (30 minutes) of its last send.
11. A first approver's pending request is void when the refund's status changes or it is verified again.
13. `WalletService.creditDisputeRefund` locks the wallet before it decides, and decides on what has been committed
    (since the third review, by locking reads on the transaction's own connection: see below).
14. 408 and 409 on the refund POST are `AMBIGUOUS`. **Deployment prerequisite:** production MySQL must run with
    `binlog_format=ROW`, because the wallet debit runs at READ COMMITTED (MySQL refuses those writes under statement-based
    logging).

**Amended after the third review (a second independent review; findings in `review-api17b.md`).**
1. *The rejected queue is read in full, and no two dead payments can starve it.* Two payments Razorpay could not place at
   its head used to end the run. Now the whole page is read; an answer about any other refund proves the keys work and the
   held ones go to review once (`PAYMENT_GONE`); only when nothing was answered is it a configuration fault, and the held refunds
   move behind the others (`settle_held_at`). The thirty-minute alert is its own pass over every rejected refund.
2. *The age of an ambiguous refund is counted from its last send* (`refund.last_sent_at`, set at every claim), not from
   `updated_at`, which recording or voiding an approval also moves: with the production `recredit-min-age` (30 minutes) a
   second person could never complete a re-credit above the threshold.
3. *A part sent to review because the payer was refunded another way records why* (`review_cause`, `review_ref`), and no
   person can put it back. `verify` and `recredit` run the system's proof (`FOREIGN_REFUND` is a result, and a stored
   `verified_result`); `to-wallet` too. Exits: `mark-completed`, or `retry`. (The first version only applied the proof to the system's path, so the
   ops path re-credited exactly the parts the system held back.) *(Amended by the fourth review: those were the only exits, and
   neither works when the foreign refund is not this part's; see below.)*
4. *A refusal the provider's own reads contradict is not acted on.* `PAYMENT_UNKNOWN` with both reads succeeding is sent again
   (`REJECTED` to `REQUESTED`, at most five attempts, then review), not reversed and not blocked. This is the safest reading:
   the alternative, reversing without blocking, would still silently undo a withdrawal that was fine.
5. *A withdrawal's pre-check reads every source before it declares a configuration fault* (two dead oldest sources no longer
   lock an outlet out for good).
6. *`ALREADY_REFUNDED` that the reads do not show is reviewed, not reversed.* **This amends "already refunded is never put
   back"**, which was true of the first version and is not of the second: it is put back only when the payment's own
   `amount_refunded` shows it refunded in full and refunds of ours explain all of it. The audit of reversed refunds also
   looks for a refund that is not ours (*amended by the fourth review: on two looks at least five minutes apart, not two reads
   milliseconds apart*): a hit is CRITICAL and pauses the outlet, as a late refund of ours does.
7. *The reversal audit stops on a 429 or refused keys.*
8. *`creditDisputeRefund` needs one connection.* Its reads after the wallet lock were made in a second transaction, so
   as many concurrent dispute credits as the pool has connections could each hold one and wait for another. They are
   locking reads (`FOR SHARE`) on the connection the transaction holds, which see what is committed now whatever the
   transaction's own snapshot. *(The first version read by the new reference and deadlocked two outlets' refunds every
   time they overlapped; see the fourth review.)*
9. *V48 is edited in place* (it has never been applied outside the tests), with a pre-deploy check for a duplicate
   `provider_refund_id` (RAZORPAY.md, "Deploying V48") and the instruction that an environment that ran an earlier V48 needs
   `flyway repair` or a V45.
10. *A payment read without `amount_refunded` (or `amount`) is unreadable*, not "nothing refunded".

**Amended after the fourth review (a third independent review; findings in `review-api17c.md`).**
1. *A part held back for a refund that is not its own has an exit.* The third review's fix (a person can never put back a part
   the system held for a foreign refund) left no way out when that refund was unrelated (a goodwill refund on a payment
   the payer was also to be paid this part from), for a different amount, or covered several parts: `recredit` and
   `to-wallet` were refused for good, `mark-completed` needs the same amount and a unique `provider_refund_id`, and a retry came
   back to review. Two exits, both checked at the provider and audited, and neither a change of the proof:
   - **`POST /admin/refunds/{id}/foreign-refund-not-this-part`** records a *judgement*, moves no money. A note, evidence and the
     provider refund ids (up to six). **Always two different people** (the first approval is bound to the ids by a hash in
     `ops_action`, and is void when the refund is read, retried or changes status, or after 24 hours). It needs a `verify` from the last
     ten minutes that found a refund that is not ours, the minimum age since the last send for an ambiguous part, and reads the
     provider again when the second person acts: every id must be listed on the payment, not failed, not ours. It is stored
     on the refund (`review_cause = FOREIGN_NOT_THIS_PART`, `review_ref` = the ids), and the proof then leaves out **exactly those
     ids and their listed amounts, for this refund only**. Any other refund, another id of the same size, or any amount those do not
     explain still makes the part `FOREIGN_REFUND`, and a second refund needs its own two people. *(The fifth check adds: each refund named must be worth less than the part and all recorded together less than it; see that record.)* The re-credit that follows needs
     everything it always did (a fresh `verify`, a second person above the threshold, evidence and the minimum age for an
     ambiguous part, its own read). The late-success watch reads the same record. A retry clears it.
   - **`mark-completed` with `confirmPayerRefundedInFull: true`** (withdrawal parts only) closes a part against a refund that is for
     more than the part: the provider must show the payment refunded in full (`amount_refunded` at `amount`), the refund must be processed and not
     ours, and the parts closed against it never add up to more than it (`claimedAgainst`, checked again under the payment
     lock). One dashboard refund of the whole payment closes a smaller part; one refund for two parts closes both. The part is
     COMPLETED with `provider_refund_id` empty (that column stays a one-to-one claim); the claim is `review_cause =
     COMPLETED_BY_OTHER_REFUND`, `review_ref` = the provider refund; the source is blocked `REFUNDED_ELSEWHERE`. Above the threshold a second person, bound
     to that refund. The wallet is not credited: the payer has the money.
2. *`creditDisputeRefund` no longer deadlocks two outlets' refunds.* The reads added in the third review locked the gap where the
   new reference would be, so two refunds on two wallets with neighbouring references each waited for the other to insert.
   Now one `FOR SHARE` read of the order's own entries (index forced, or the optimizer may scan the table and lock its end),
   the decisions made in Java, the unique reference the backstop. Still one connection. And a dispute approval that loses a
   deadlock anyway (`DeadlockRetry`, only where it owns the transaction) is run once more: it is one transaction, so nothing
   moved.
3. *The audit's second look is a second look.* Our own refunds that carry a provider refund id and are missing from the list count
   as explained (that is the lag). A mismatch is first only noted (`verified_result = SUSPECT`, `verified_at` = when), read
   again at least five minutes later whatever the schedule says, and only then CRITICAL. No schema change.
4. *Two tests the reviewer's mutations survived* (a person putting back a contradicted "unknown payment" part does not block a
   healthy source; unknown payments after an answered refund on the page are reviewed, not held).

**Amended after the live UPI campaign, round 2 (findings B1, B3, F3, F4 of `upi-e2e-results-final.md`).**
1. *A part on a payment the provider does not know can be closed by two people, on proof that the keys work (B1).* Razorpay's
   refunds list for an unknown payment answers **200 with nothing in it**, while the payment read says unknown, so the
   person's "unknown payment" path (which assumed the list itself answers not-found) never fired: `verify` rethrew, answered
   503 and could never record, and `recredit` was impossible for ever (legacy refunds 24 and 25). Now, for a person (never
   the system, and not the system's hourly read of a refund in review, which records nothing): when the list is empty or
   not-found **and** the payment read is not-found, the provider's keys are first **proven to work**: a successful read of a
   payment of ours **made after this one** (captured or refunded, this provider's own, not one found unknown), newest first, at
   most five, answering for the same order as our books (`WithdrawalReversalService.proveKeysWork`). Later, so that the keys of
   an older account cannot prove themselves with the very payments they should not know; consequence: the newest payment of
   all is never decided this way. A read that fails any other way, answers as another order's, or finds no answer at all is
   no proof: **503 `PROVIDER_UNAVAILABLE`** ("the provider's keys could not be proven to work"), nothing recorded, nothing
   put back (wrong keys, mode or base URL make *every* payment unknown, which is exactly this). Proven, `verify` records
   **`verified_result = UNKNOWN_PAYMENT`** (15 characters; the column is 16) and audits `REFUND_UNKNOWN_PAYMENT_PROOF` naming the proving payment.
   This also replaces the old reading "a list that is not-found is 'none of ours'", which recorded `NONE_OF_OURS` with no proof
   at all.
2. *`recredit` and `to-wallet` of such a part are the strictest actions there are.* **Always two different people, whatever the
   amount**, each with `evidence` (at least 15 characters: which Razorpay account or keys the payment belongs to) and
   `confirmPaymentOnOtherAccount: true` (it is another, retired account and nothing was sent to the payer from it), on top
   of the existing gates: a verification under ten minutes old, the minimum age since the last send (for **every** kind of
   failure, not only `AMBIGUOUS`), `confirmNoProviderRefund`, and the provider read again inside the call, which repeats the proof.
   If that read finds the payment known after all, the ordinary proof applies (a foreign refund still refuses it: 422); if it
   finds it unknown while the verification said known, nothing is put back and the answer is 409 `REFUND_VERIFICATION_REQUIRED`
   (verify again). The part is `REVERSED` with `verified_result = UNKNOWN_PAYMENT`, the source blocked `PAYMENT_UNKNOWN`. A
   payment that **is** known and shows a foreign refund is unchanged: never put back. **What can still go wrong:** the
   payment is unknown to *these* keys but lives in an account that is live and can still be refunded from (the operator's
   evidence and the second person are the control); or a payment made after this one is itself on a different account than
   the keys (the proof then says nothing); or Razorpay hides a payment it knows for a moment. A refund later found on the
   payment is a CRITICAL double credit like any other, but only *within the 14.5-day late-success watch* (`REVERSED_AUDIT_FOR`)
   and only *if the payment is known to the keys in use by then*: the watch cannot read a payment that stays unknown (it then
   only marks the read and moves on), so nothing detects a refund made after the watch ends or on an account the keys do not reach.
3. *The named refunds of `foreign-refund-not-this-part` are checked at the first approval too (B3)*: listed on the payment now,
   not failed, not ours, else 422 and nothing recorded (before, a garbage id was recorded and waited a day to fail). The second
   person's own read still repeats it.
4. *The withdrawal pre-check tells the truth about how much it looked at (F3).* `details.checkedSources` and `uncheckedSources`
   (and the same two on a successful withdrawal, where `null` only for a replay of one made before); when some were not asked
   about (enough covered, ten sources or twenty seconds used) the message says more may be withdrawable in a further step.
   Sources are asked **oldest credit first, as always** (*amended, see the last paragraph of this record: the first version of this
   change asked the largest first, which changed which sources a withdrawal draws from and broke the "oldest first" allocation
   rule above*); the largest are asked first only as a fallback, when the oldest ten cannot cover the request at all. The budget is
   **not** raised: a longer pre-check holds a request for longer with no money rule to show for it.
5. *The expected stale-update release line is quiet too (F4).* `HHH100503` (INFO) is dropped only as the release of the very
   batch whose `HHH100501` stale-state error was just dropped on the same thread (within five seconds, once).

**Amended after the check of the unknown-payment ops path (round 5; no schema change, V48 untouched).**
1. *The operator's audit and the money move together, and the audit text always fits.* The `REFUND_OPS_RECREDIT` audit of a
   put-back is now written by the reversal itself, inside its transaction (`WithdrawalReversalService.run(..., reversedAudit)`):
   it cannot fail after the money has moved (before, a note and evidence of 400 characters each overflowed
   `audit_log.reason` VARCHAR(500) *after* the commit: the second person was told 409, the part WAS reversed and the audit row
   was lost). Every operator audit reason is also bounded (`OperatorAuditText`, at most 500 characters): the lead, the
   confirmations, then the evidence (its length, the first sixteen hex digits of its SHA-256, its first 120 characters) and as
   much of the note as still fits. The whole evidence text is not kept, only its digest: whoever holds the text can match it.
2. *Each person's evidence and confirmations are recorded.* The first approver's `REFUND_OPS_APPROVAL_REQUESTED` row and the
   completing `REFUND_OPS_RECREDIT` / `REFUND_OPS_TO_WALLET` row carry, for a part on an unknown payment, the confirmation
   ("another, retired account, nothing sent from it") and the bounded evidence (before, the first approver's evidence was
   validated and thrown away, and to-wallet never recorded any).
3. *The "oldest first" allocation is restored (regression of round 2, finding F3).* The pre-check asks the sources in the
   ledger's order, oldest credit first, so a withdrawal draws from the oldest credits exactly as before (sources of 10, 20 and
   500, oldest first, and a withdrawal of 30 take the 10 and the 20, not the 500: small old credits must not age past the refund
   window and become un-refundable). Only when the ledger says the oldest ten open sources hold less than the request, so that
   asking them can never cover it, are the sources that can give back the most asked first (ties keep the ledger's order), so the
   request has a chance it would not otherwise have; the plan is still worked out afterwards from the database, in the ledger's
   order, from the sources that were checked. Which sources a request that was fully checked in the ordinary way uses never
   changes. `checkedSources` and `uncheckedSources` stay.
4. *A part put back on an unknown payment always blocks its source, `PAYMENT_UNKNOWN`,* whatever its own failure kind was
   (before, only a part whose kind was `PAYMENT_UNKNOWN` did, although this document said the source was blocked).
5. *A part that carries a `provider_refund_id` is not closed on the unknown-payment path.* The id proves the part was sent, and
   refused or left unknown, through keys that knew the payment (the "other account"); a "nothing was sent from there" is then
   about that refund, which the keys in use cannot look up. `recredit` and `to-wallet` answer 409 `INVALID_STATE_TRANSITION`
   and say why (also under the reversal's lock). *(Amended after the fifth check, F3 below: `returned-outside` refuses on an unknown
   payment, so the one exit is `mark-completed` with evidence and `confirmProcessedOnOtherAccount`; a part that was not processed
   there needs engineering.)*
6. *The late-success watch marks a read done when the list itself answers not-found* for a part put back on an unknown payment
   (it asked every minute for the whole watch before).
7. *A refund made by hand that covers the part can never be excluded (live round 3, B2: a real double payout).* A payment of
   430.50 was refunded in full at Razorpay by hand after a withdrawal part of 50 on it was refused; two people recorded that
   very refund as "not this part's" (the check only asked that it was listed, not failed, not ours), the verification then read
   "none of ours" because the proof left the excluded refund out, and one person put the 50 back: the payer held the whole
   payment and the wallet got 50 more. *(The gates below are as amended by the fifth check: the exclusion rule is "strictly less
   than the part, each and together", and the room invariant no longer counts validly excluded refunds; see the next record.)*
   (a) *the room invariant:* where anything the provider shows refunded is not ours and not validly excluded, a part is put
   back (by `recredit`, by `to-wallet`, and by the system) only while everything refunded at the provider, counted whole
   (the larger of the payment's `amount_refunded` and the sum of its non-failed listed refunds), less the valid exclusions, plus
   this part does not exceed the payment (equality is allowed). Otherwise the answer is
   422 "the payment has already been refunded to the payer for at least this amount", nothing moves, the part stays in review
   (`verify` records `FOREIGN_REFUND`; the system sends it to `REFUNDED_ANOTHER_WAY`), and the exits are `mark-completed`
   against the payer's refund (`confirmPayerRefundedInFull`) or `returned-outside`. A payment refunded only by refunds of ours
   is unchanged (an over-refund of ours is refused by the provider and put back as before), and the late-success watch does not
   apply it (it would raise new alarms on old reversals). (b) *`foreign-refund-not-this-part` refuses, at the first approval
   and again at the second (and under the lock),* a refund worth the part or more, or refunds that together reach it (the fifth
   check's rule; the first version compared the whole payment and let a refund of exactly the part through). (c) *the `recredit` that follows
   an exclusion always needs two different people, whatever the part is worth, each with evidence* (at least 15 characters:
   what shows the payer was not refunded this part's money). Before, below the threshold one person finished it alone.
8. *The minimum age of a legacy row no longer re-arms itself (live round 3, B1).* A refund from before `last_sent_at` existed
   (every pre-V47 row, refunds 24 and 25) was aged from `updated_at`, which recording or voiding an approval and every
   verification move: the 30-minute minimum could not be met inside the ten-minute freshness of the verification, so the
   two-person re-credit could never complete. Now `last_sent_at`, else `sent_at` (the first send), else `created_at`: timestamps
   no action of a person moves. The consequence for a legacy row is that the age is counted from its first known send; such
   rows are old, so the gate is met at once.
9. *`verify` names the proving payment in its own audit row too* (live round 3, B3): `REFUND_OPS_VERIFY` reason "keys proven by
   reading payment pay_X; ..." beside the note (bounded), as well as `REFUND_UNKNOWN_PAYMENT_PROOF`.
10. *Residual risk, stated plainly.* The proof that the keys work is "a later payment of ours answers". If the keys were moved to
   another account K2 and payments were made on K2 since, an old payment that lives on the old account K1 reads as unknown and
   the proof passes. If an earlier send of this part to K1 was ambiguous and later succeeded there, putting the part back pays
   the restaurant twice and nothing in the code can tell (the failure kind was overwritten by K2's refusal, and the watch cannot
   see K1 while the keys are K2): **only the two operators' evidence guards against it.** A provider key id recorded per payment
   and per send would close it; it is future work. One person holding two operator accounts passes the two-person rule; that is
   organisational.

**Amended after the fifth check (a fourth independent review, `review-api17e.md`; no schema change, V48 untouched).**
1. *F1 (money): which foreign refunds can be excluded.* The fourth round let a refund made by hand for exactly the failed part
   be excluded whenever the whole payment still had room (payment 1000, part 400 refused, 400 refunded by hand: two people
   excluded it and put the part back: wallet 400 and payer 400). **Rule: every refund named, together with those already recorded on the
   part, is worth strictly less than the part, and all of them together are strictly less than it** (`WithdrawalReversalService.exclusionsBelowPart`).
   Refused 422 `REFUND_VERIFICATION_FAILED` ("worth at least this part", or "add up to ...") at the first approval, at the second and
   under the row lock (two requests at once cannot together reach the part), and applied whenever the proof is read: a recorded
   set that does not hold (a record from before the rule) is no record and its refunds are foreign again. Equality is refused
   (400 against 400; 25 + 25 against 50); 399.99 against 400 and 20 + 20 against 50 are allowed. A refund worth the part or more looks like
   compensation for exactly this part; its exit is the plain `mark-completed` (same amount) or `mark-completed` with
   `confirmPayerRefundedInFull` (payment refunded in full) or `returned-outside`. The live B2 case (430.50 refunded in full, part 50)
   is refused at the exclusion (430.50 >= 50) and, forced into the record, is foreign again at the put-back.
2. *F2 (regression): the room invariant applies only to foreign refunds not validly recorded as not this part's.* A payment of 1000
   fully withdrawn as one part and then a goodwill refund of 10 by hand left the part refused over-refund, in review, with every exit
   closed (the exclusion refused by the room rule since 10 + 1000 > 1000, mark-completed needing a full refund, re-credit refused,
   retry looping). The valid exclusions' amounts are now removed from the room total: each is worth less than the part and together they
   are, so they cannot be compensation for it, and the provider's room is no measure of what the wallet is owed (Razorpay cannot take
   the part any more; the wallet gets it back; the payer keeps the unrelated goodwill). Two people with evidence, a fresh verification,
   the minimum age and the put-back's own read still apply. Kept: any non-excluded foreign refund (today in effect a refund that
   parts of ours were closed against), an `amount_refunded` that ours and the recorded ones do not explain (`FOREIGN_REFUND`), a second
   foreign refund after the exclusion (`FOREIGN_REFUND` again), the system path and `verify`.
3. *F3: a part that carries a provider refund id on an unknown payment has an exit.* The 409 pointed at `mark-completed` and
   `returned-outside`, which both refuse there. Now `mark-completed` with `providerRefundId` (the part's own), `evidence` (at least 15
   characters: the other Razorpay account and what shows the refund was processed there) and `confirmProcessedOnOtherAccount: true`:
   **always two different people**, a `verify` under ten minutes old that said `UNKNOWN_PAYMENT`, and inside the call the provider is read
   again (payment still unknown, keys proven); the part ends `COMPLETED` with `review_cause = COMPLETED_OTHER_ACCOUNT`, `review_ref` = the
   refund id (the payer has the money; the wallet is not credited; the source is blocked `PAYMENT_UNKNOWN`). Withdrawal parts only (a
   cancellation refund carrying an id has no exit here: it needs engineering). `recredit` stays refused for such a part; if the refund
   was NOT processed on the other account it needs engineering or a manual step. The 409 names this exit.
4. *F4: tests for the mutations that survived the review* (R1/R2 the room total is the larger of the provider's figure and its list,
   R3 a failed refund on the list does not count, R4 the to-wallet "already covered" branch is reachable, T2 the second person also gives
   evidence after an exclusion).
5. *Known gap, stated plainly (superseded by the sixth check below: `retry` is NOT the exit for a refund that covers the part; `confirmRefundCoversThisPart` is).* A refund made by hand worth the part or more, on a payment that is not refunded in full and is not the
   part's exact amount (500 against a part of 400 on a payment of 1000; two refunds of 300 against a part of 400), can be neither excluded
   (strictly-less rule) nor closed by `mark-completed` (same amount, or the payment refunded in full). `retry` works while Razorpay can still
   take the part; otherwise it stays in review for engineering. The alternative (excluding them) is exactly the double payout of F1.
No schema change (V48 is untouched).

**Amended after the sixth check (a fifth independent review of the same commit, `review-api17f.md`: READY WITH FIXES; no schema change,
V48 untouched).**
1. *F2 (HIGH): `retry` pays a covered part twice, so the gap of item 5 gets a code exit and the runbook stops naming `retry` for it.* Payment
   1000, part 400 refused, support refunded 500 by hand (the 400 and 100 goodwill): the exclusion is refused (500 >= 400), `mark-completed`
   refused (500 != 400, payment not refunded in full) and `retry`, with 500 of room at Razorpay, sends 400 more: the payer holds 900 against 400 owed.
   New: **`mark-completed` with `confirmRefundCoversThisPart: true`** closes a withdrawal part against a listed, processed, not-ours refund
   whose **unclaimed remainder** (its amount less what other parts already claimed: `requireCoversPart`, `claimedAgainst`) covers the part, although the
   amounts differ and the payment is not refunded in full. Needs `evidence` (>= 15 characters, every call), a fresh `verify` that found a
   foreign refund, and **always two different people**; the part ends `COMPLETED` (the payer has the money), the wallet is not credited, the source is
   blocked `REFUNDED_ELSEWHERE`, `provider_refund_id` stays empty (no unique-index claim), claims never exceed the refund's amount (checked at
   the first approval and again under the lock). The system never does it by itself. The company cannot pay twice by it **provided the minimum age is kept**: for a part
   whose send was never answered (`AMBIGUOUS`) the close, like the `confirmPayerRefundedInFull` one, is refused within 30 minutes of the last send (every call and
   again under the lock), because our own lost send may still land and the payer would hold it and the covering refund; the close itself sends and credits nothing.
   Its only risk after that is the restaurant's, if the judgement is wrong (`COMPLETED` is terminal), the same class as the other claim path. A plain `mark-completed`
   (amount equal) of another part never adopts a provider refund that parts are already closed against (payment 1000, parts 300 and 400, hand refund 400: 300 closed
   against it, the 400 part could take it as its own and 700 would be closed against 400): refused, checked again under the lock. **`retry` is only for
   a refund unrelated to the part**, never for one that covers it; every refusal says so. Remaining for engineering: a refund worth the part or
   more that is genuinely unrelated, with no room left at the provider. A refund that was claimed only in part (400 of 500) stays foreign for other
   parts of that payment (conservative: they stay in review until closed against the remainder or by engineering).
2. *F1 (MEDIUM): one refund counted twice.* A refund recorded as not X's and later closed against by another part is ours; the proof added it as ours
   and again as excluded, so a second, hidden refund of the same amount went unseen (payment 1000, X 500, Y 300, H 300 excluded for X then Y closed
   against H, another 300 in `amount_refunded` but not yet listed: NONE_OF_OURS, X put back). `booksOf` (and the exclusion's own sum) now drop recorded ids
   that are ours before validating and summing them.
3. *F3 (LOW): a recorded refund that later fails.* It counts as 0 in the exclusion sum (it moved no money) instead of voiding the whole record; a
   new exclusion of a failed id is still refused; the 422 for a recorded or named id the provider no longer lists names that id instead of the false "add up to".
4. *F4 (LOW): the other-account exit's second call that finds the part adopted* now answers `ADOPTED` (part `COMPLETED`, audited), not 409 "nothing
   was closed"; a put-back refused after an exclusion because a new refund appeared names that refund.
5. *F5 (tests) and a note.* The room rule with an exclusion and a claim (700 claimed, 200 excluded, part 400: refused; 500 claimed: put back), the clause
   "each refund below the part" has its own unit test (a negative amount; for non-negative amounts it is implied by the sum). **A hand refund 0.01 below
   the part passes the rule: it is a judgement the rule cannot catch** (the bound is S < the smallest part put back, once over all parts).
6. *B1 (wording, live round 5).* A refund still `PENDING` at Razorpay is answered "exists but not processed yet"; an id the list does not show says a refund
   made a moment ago may not be listed until processed (the list shows a refund only once processed).
7. *H1 (HIGH, the plain path).* The plain `mark-completed` (the provider refund is for exactly the part's amount) had no minimum age: a hand refund of the
   part's amount, closed within 30 minutes of the last send of an `AMBIGUOUS` part, adopted that refund while our own lost send could still land (the
   payer paid twice). It now keeps the same minimum as the other closes: refused (409 `REFUND_VERIFICATION_REQUIRED`) within `recredit-min-age` (30 minutes)
   of the last send of an `AMBIGUOUS` part, before the transaction and again on the locked refund under the locks, before the adoption. Parts that failed another way are unaffected.
   Decided not to change two neighbours: `returned-outside` (a cancelled order's payment has no refund of ours to land: it is refused when the cancellation
   refund exists, and no withdrawal draws on that payment) and the other-account exit (the part already carries the provider refund id of its own send, so
   there is no lost send left to land; it also needs a verification of an unknown payment and an in-call read).
New request field: `confirmRefundCoversThisPart` on `mark-completed`. No schema change.

**Tests.** `PaymentFlowIT$WithdrawalFailures` (the pre-check, every kind of refusal, one reversal under concurrency, crash
recovery, blocked sources, the late-success watch, the breaker, the ops actions and their authorisation, second approver
and tenant safety), `RazorpayPaymentProviderTest` (classification, list, wording never logged), `WithdrawalSourcesTest`,
`RefundMatchingTest`, `PaymentLifecycleTest`, `ReversalAuditScheduleTest`, `ProductionProviderGuardTest`. Each guarding test was proved by a
mutation (see the PR); the tests added after the second and third reviews were each shown to fail on the code before their fix.

**Open verifications (test mode, before sign-off).** V-5 the idempotency key's retention (no longer relied on). V-6 the
refund window. V-7 the exact 400 text for each row of the table, and whether a `failed` refund can become `processed`. V-8
whether `amount_refunded` includes a pending refund.

## D-111 — QuickScan, top-ups, history and statements meet the money-safety stack
**Raised 2026-09-30 · Settled 2026-09-30**

D-106 to D-108 were built on the code as it stood before D-109 and D-110, and share one wallet with them. Put together, what
each side must do for the other:

1. **Only card refund money can go back to a card.** What a wallet can give back is computed from the `refund` table alone
   (`RefundRepository.withdrawableByPayment`): wallet refunds credited, less withdrawals not reversed. A top-up
   (`TOP_UP`) and a returned QuickScan payment (`QUICKSCAN_RETURN`) never appear there, so neither is withdrawable. Refund money
   that QuickScan spent and that came back (a declined payout) is still that card's refund, so it can go back once and no more
   than was refunded; the balance still caps every withdrawal. A `WITHDRAWAL_REVERSAL` credit puts refund money back into what
   can be withdrawn, because its refund is `REVERSED` and no longer counted.
2. **One way to move a balance.** History and statements read the wallet through `WalletService.find`, not the repository:
   `WalletBalanceWritersTest` says only `WalletService` holds `WalletRepository`. QuickScan's debit and return and the top-up
   credit are `WalletService` methods, each an atomic update with its ledger row.
3. **Lock order.** The wallet is locked first (`WalletService.lock`, a locking read before anything is loaded); a top-up
   then locks its own row, a QuickScan payment inserts its own new row. A QuickScan return runs holding its payment row and
   then takes the wallet by an atomic credit; nothing that holds the wallet ever waits for a QuickScan row, so the order
   wallet, refund, payment of the withdrawal is not crossed.
4. **What a person reads.** A withdrawal whose refund is `REVERSED` is `RETURNED` (the money is back, its own
   `WITHDRAWAL_REVERSAL` credit says so); `REJECTED`, `FAILED`, `NEEDS_REVIEW` and the rest not yet complete are
   `IN_PROGRESS`. A statement labels the reversal "Withdrawal returned to your wallet" and still reconciles: it is a ledger
   credit like any other. The mobile `entryCopy.ts` must carry the same wording (see D-108).
5. **The provider port carries both sides' fields.** `AuthorizationRequest` has `holdMinutes` (orders, D-109) and `autoCapture`
   (top-ups, D-107); a top-up sends no hold. A top-up's refund to its source goes with a receipt and notes of its own
   (`mandi-topup-refund-{id}`, `mandi_topup_id`), which the order refunds' matching (`mandi-refund-`) does not mistake for a refund of
   an order's payment. A `payout` provider left at MOCK under a production profile refuses to start, as does `withdraw-precheck`
   set to anything but true.

**Migrations.** V48 to V46 (QuickScan, top-ups, top-up payment method) and V47 and V48 (the stack) are independent and apply in that order
on a fresh database and on one already at V46, where V47 and V48 are simply the next two.

**Tests.** `PaymentFlowIT$WalletInterplay`: top-up money and QuickScan returns against the withdrawal pre-check, a reversal on
the history and a statement, a QuickScan payment and a withdrawal of one wallet at once, and top-ups, QuickScan, withdrawals and a
reversal together (ledger equals balance). `WalletEntryCopyTest`.

---

## D-117 — Borzo joins the auction alongside Pidge, verified live instead of assumed
**Raised 2026-10-01 · Settled 2026-10-01**

Pidge's contract was never checked against a real response — its own
implementation plan required that and it was skipped, and `PidgeApiClient`
silently defaults a missing or renamed field to a plausible fake value
(`new BigDecimal(body.path("total_fare").asText("50.00"))`) instead of
failing. Borzo sandbox credentials were materially easier to obtain, so it is
added as a second `DeliveryProvider` — **alongside** Pidge, not replacing it —
with every request/response field checked against the live sandbox
(`robotapitest-in.borzodelivery.com`) before being relied on.

### Two independent switches, not one shared one
`PidgeDeliveryProvider` is `@ConditionalOnProperty(name =
"costonomy.mp.providers.delivery", havingValue = "PIDGE")` — a single-valued
switch that cannot also equal `"BORZO"`. Rather than widen that property into a
list (touching Pidge's tested activation path for no reason), Borzo gets its
own flag, `costonomy.mp.borzo.enabled`, so it can run next to Pidge, next to
the mocks, or alone. Both still need the matching `delivery_provider.enabled`
row (V53, seeded `0`) before `DeliveryProviderRegistry` actually offers the
adapter a quote — the same dual-gate Pidge already uses.
`BorzoPidgeCoexistenceTest` proves the two switches don't interfere.

### Borzo has no idempotency-key mechanism of its own — ours is what protects a retry
Pidge's `createOrder` sends `idempotency_key` in the payload, trusting Pidge to
deduplicate server-side — unverified, but at least a documented field. Borzo's
`create-order` has no equivalent. What it does have is `client_order_id`, an
echoed-back per-point reference with no documented dedup semantics. Rather than
lean on an unconfirmed provider behaviour, protection against a duplicate
booking stays where it already lived for every provider: `uk_delivery_order`
(one delivery per supplier order), `DeliveryService.request()`'s pre-check for
an existing delivery, `DeliveryBookingService`'s per-attempt idempotency key,
and no automatic HTTP retry client. `BorzoApiClient.createOrder` sends that
same deterministic key as `client_order_id` on every point anyway — it costs
nothing and gives a reconciliation handle if a human ever has to match a
Borzo order back to an attempt — but it is not treated as the thing preventing
a double-booking. The residual gap is identical to Pidge's, not new: a
read-timeout on `create-order` after Borzo already created the order leaves
our system unable to tell, and both adapters fail loud into "try the next
provider" rather than silently retrying.

### Quoting needed an address, which `QuoteRequest` never carried
`calculate-order` rejects a point with no `address` string even when lat/lng
are both present — confirmed live: a coordinates-only request comes back
`is_successful: true` with an empty `points` array and
`parameter_warnings.points[].address: ["required"]`. `DeliveryProvider.QuoteRequest`
carried only coordinates, because Pidge and the mocks never needed more.
Extended it with `pickupAddress`/`dropAddress` — sourced from `Delivery`'s own
(already-`NOT NULL`) address columns in `DeliveryQuotingService`, and from
`DeliveryDirectory.Place.address()` in `DeliveryFeeQuoteService` — via a new
constructor overload, so Pidge and the mocks, which never read the field,
are unaffected.

### No duration ETA exists; a per-point deadline stands in for one
`calculate-order` never returns an ETA field at all — confirmed by multiple
live calls, not an undocumented gap assumed from reading the docs. What it
does return is `points[].required_finish_datetime`, a deadline that moves with
real route distance (a 19 km test route pushed it out further than a 6 km
one, confirmed side by side). `BorzoApiClient` reports `etaMinutes` as the
minutes between now and the **drop point's** `required_finish_datetime`. This
is a real, provider-computed figure in a different shape — not the invented
value doc 06 §8 forbids, and not Pidge's own `eta_minutes.asInt(25)` default
either.

### Only vehicle_type_id 8 is verified; everything else declines rather than guesses
Borzo's `vehicle_type_id` presumably has values for larger vehicles, but none
were confirmed against the sandbox. `BorzoApiClient` answers `Quote.unserviceable`
for any `VehicleType` other than `TWO_WHEELER` rather than sending an
unverified id — a decline doc 06 §4 already treats as a normal answer, not a
new failure mode.

### Status polling synthesizes events so DeliveryJobs advances delivery and order
Borzo's `GET /orders` reports a single current status rather than an append-only event log.
`BorzoApiClient` synthesizes a deterministic `ProviderEvent` (e.g. `borzo_evt_{id}_driver_assigned`,
`borzo_evt_{id}_delivered`, with `PICKED_UP` preceding `DELIVERED` newest-first) so `DeliveryJobs.pollActiveDeliveries()`
can apply events through `DeliveryEventService`, advancing the delivery state machine and moving the supplier
order through `OUT_FOR_DELIVERY` to `DELIVERED` while relying on `uk_delivery_event_provider` for duplicate suppression.

### Still open, blocking a second PR
The webhook/callback payload and signature scheme are not verified — Borzo's
documentation is too thin to trust, the same mistake this whole effort exists
to avoid repeating for Pidge. `BorzoWebhookService` and the `/borzo` webhook
route are not implemented until that contract is confirmed live (a reachable
callback URL against a real sandbox order, or direct confirmation from Borzo
support). Until then, `BorzoDeliveryProvider` reports status only by polling
`GET /orders`, same as doc 06 §9 says any provider should be able to fall back
to.

---

## D-118 — Shadowfax delivery provider integration alongside Pidge and Borzo
**2026-10-02 · Settled**

Shadowfax is integrated as a third carrier in the multi-carrier delivery auction,
joining Pidge and Borzo. The implementation follows the provider SPI pattern
established in doc 06 §4 and decisions D-117.

### Dual-gate activation
Like Borzo, Shadowfax is protected by two distinct gates:
1. **Application configuration gate**: `costonomy.mp.shadowfax.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `ShadowfaxDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`provider_code = 'SHADOWFAX'`),
   seeded disabled (`is_active = 0`) via migration `V46__delivery_provider_shadowfax.sql`.
   Both gates must be active for Shadowfax to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: `Authorization: Token {token}` header on every request.
- **Serviceability & Quoting**: `GET /v1/clients/serviceability/?service=Regular&pincodes={pincode}`
  validates drop point serviceability. Distance-based delivery fees and ETAs are computed
  from pickup/drop coordinates and configured baseline rates.
- **Booking**: `POST /v3/clients/orders/` with `order_type: "marketplace"`. The response
  `awb_number` serves as the platform's `providerDeliveryId`.
- **Status tracking & Polling**: `GET /v4/clients/orders/{awb_number}/track/` inspects
  `order_details.status` and `order_details.tracking_details`.
  The status mapper transforms Shadowfax states (`allocating`, `assigned`, `arrived`,
  `picked_up`, `out_for_delivery`, `delivered`, `cancelled`) into platform `DeliveryStatus`.
  Historic events from `tracking_details` are synthesized newest-first with deterministic IDs
  (`sfx_evt_{awb}_{status}_{timestamp}`), guaranteeing that `PICKED_UP` precedes `DELIVERED`
  so that `DeliveryOrderBridge` transitions the supplier order through `OUT_FOR_DELIVERY`
  to `DELIVERED`.
- **Cancellation**: `POST /v3/clients/orders/cancel/` sending `request_id: {awb_number}`.
  Mapped errors (e.g. already picked up or out for delivery) translate into `CANCEL_WINDOW_ELAPSED`.

### Webhook ingestion deferred
Shadowfax webhook ingestion is deferred pending live payload and HMAC verification confirmation,
relying on polling via `DeliveryJobs.pollActiveDeliveries()` for status advancement.

*Corrected by D-121: Shadowfax fares and ETAs are no longer computed from baseline rates; quote and booking fail closed until a carrier fare is verified.*

---

## D-119 — Porter delivery provider integration alongside Pidge, Borzo and Shadowfax
**2026-10-02 · Settled**

Porter is integrated as a fourth carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, and Shadowfax. The implementation adheres to the provider SPI pattern
established in doc 06 §4 and decisions D-117 and D-118.

### Dual-gate activation
Porter is gated by:
1. **Application configuration gate**: `costonomy.mp.porter.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `PorterDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`provider_code = 'PORTER'`),
   seeded disabled (`enabled = 0`) via migration `V47__delivery_provider_porter.sql`.
   Both gates must be active for Porter to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Header `x-api-key: {apiKey}` and `Authorization: Bearer {apiKey}`.
- **Serviceability & Fare Estimation**: `POST /v1/orders/cost` with `pickup_details`,
  `drop_details`, and mapped `vehicle_type` (`2_wheeler`, `three_wheeler`, `tata_ace`).
  Parses fare amount, distance, and ETA.
- **Booking**: `POST /v1/orders/create` with pickup/drop addresses, contact information,
  coordinates, and `request_id` (idempotency key). Returns Porter `order_id` as `providerDeliveryId`.
- **Status tracking & Polling**: `GET /v1/orders/{order_id}` inspecting `status` and `partner_details`.
  The status mapper transforms Porter states (`created`, `allocating`, `assigned`, `driver_arrived`,
  `started`, `picked_up`, `in_transit`, `arrived_at_destination`, `delivered`, `cancelled`) into platform
  `DeliveryStatus`.
  Historic events or synthetic transitions guarantee that `PICKED_UP` precedes `DELIVERED`
  newest-first, allowing `DeliveryOrderBridge` to advance the supplier order through `OUT_FOR_DELIVERY`
  to `DELIVERED` while `uk_delivery_event_provider` suppresses duplicate event rows.
- **Cancellation**: `POST /v1/orders/{order_id}/cancel` sending `cancellation_reason`.

### Webhook ingestion deferred
Porter webhook ingestion is deferred pending live payload and HMAC verification confirmation,
relying on polling via `DeliveryJobs.pollActiveDeliveries()` for status advancement.

*Corrected by D-121: Porter's quote and booking no longer fall back to a configured rate card; both fail closed until a carrier fare is verified.*

---

## D-120 — Intra-city 30 km radius boundary and tiered assignment deadlines
**2026-10-02 · Settled**

Initial marketplace delivery operations focus strictly on intra-city fulfillment within municipal limits (maximum 30 km radius).

### 30 km Intra-city Hard Radius Ceiling
1. **Pre-order Quoting (`DeliveryFeeQuoteService`)**:
   - Rejects checkout fee requests exceeding 30.0 km with `BusinessException(ErrorCode.VALIDATION_ERROR, "Delivery location exceeds the 30 km intra-city limit (distance: %.1f km). Choose pickup, or ask the supplier to deliver.")`.
2. **Auction Gatherer (`DeliveryQuotingService`)**:
   - Evaluates Haversine distance before querying carriers (`costonomy.mp.delivery.max-radius-km=30.0`).
   - Deliveries exceeding 30 km save a single `UNSERVICEABLE` quote with failure reason `"Exceeds 30.0 km intra-city radius limit"` and immediately return empty without polling 3rd-party carrier APIs.
3. **Carrier Adapters (Borzo, Porter, Shadowfax)**:
   - Each client independently validates distance $\le 30.0$ km and returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")` if exceeded, preventing accidental out-of-city dispatch.

### Tiered Driver-Assignment Deadlines
Commercial vehicles take longer to match in Indian metropolitan traffic than two-wheeler bike couriers:
- **Two-Wheelers (`TWO_WHEELER`)**: `PT3M` (3 minutes) waterfall timeout (`costonomy.mp.delivery.bike-assignment-timeout`). Bike couriers match within 1–3 minutes; lingering longer delays re-bidding.
- **Three-Wheelers & Trucks (`THREE_WHEELER`, `FOUR_WHEELER_TRUCK`)**: `PT12M` (12 minutes) waterfall timeout (`costonomy.mp.delivery.truck-assignment-timeout`). Auto-rickshaw cargo and mini-trucks (Tata Ace, Mahindra Bolero Maxi Truck) have sparser fleet density and take 8–12 minutes to assign. A 3-minute timeout prematurely cascaded through all providers before drivers could accept.
- `DeliveryBookingService` computes `assignmentDeadline = bookedAt.plus(isTruck ? truckAssignmentTimeout : bikeAssignmentTimeout)` and emits `DeliveryBookedEvent`, which `DeliveryWaterfallService` schedules via `TaskScheduler` for one-shot timeout evaluation.

---

## D-121 — Shadowfax and Porter quote and book only on a carrier fare; until then they decline
**2026-10-02 · Settled** — corrects D-118 and D-119

### What was wrong
D-118 describes Shadowfax fees and ETAs as "computed from configured baseline rates". Porter's quote (D-119) fell back to the same kind of rate card when a response field was missing, and both clients filled `Booking.amount` from `base-fee` / `per-km-fee` properties. That put a price we invented into `delivery.fee` and the delivery ledger, and contradicts doc 06 §8 ("never fabricate") and the rule in CLAUDE.md. Both carriers are seeded disabled, so nothing was charged this way, but enabling a row would have started doing so.

### No carrier fare has been verified
- Shadowfax: the serviceability response lists pincodes and services only. The create-order response carries `awb_number`, `promised_delivery_date` and `product_value` (our own declared value echoed back), and no fare.
- Porter: the quote fixture was written by the same author as the client, with no live check (unlike Borzo, D-117). The client guessed three field names for the fare (`cost.amount`, `fare`, `estimated_fare`). The create-order fixture has no fare.

### Decision
1. **Quotes decline.** Shadowfax returns `Quote.unserviceable` with a reason saying it publishes no fare. Porter returns `Quote.unserviceable` without any HTTP call. A decline is recorded in `delivery_quote` as UNSERVICEABLE; a failed serviceability check is recorded as FAILED. Neither can win the auction.
2. **Booking refuses before any network call.** `createOrder` validates our own data, then throws a non-retryable `ShadowfaxContractException` / `PorterContractException`. It must fail before the POST: a booking that throws after the carrier accepted it would leave a live consignment nobody owns or cancels. `DeliveryBookingService` records a FAILED attempt and fails over to the next carrier.
3. **Shadowfax serviceability is fail-closed.** True only when Shadowfax lists both pincodes with the `Regular` service. A pincode not listed, or without `Regular`, is a decline. An unreachable carrier (timeout, 5xx) is a retryable failure, a 4xx is a non-retryable failure, and a body that is not an array, or an entry without a `services` array, is a contract failure. The check is never skipped by catching an exception. A missing pincode in an address is a decline with no HTTP call. The pincode is the last 6-digit group in the address, because our addresses are built city, state, pincode.
4. **Booking data comes from our own records or the booking is refused.** `BookingRequest` gains `pickupLocality`, `dropLocality` and `goodsValue`, read by `DeliveryDirectory` at booking time:

| Value | Source |
|---|---|
| City, state | `outlet` / `supplier_store` (NOT NULL) |
| Pincode | same tables (nullable, so a missing one is rejected) |
| Weight | `delivery.weight_kg` |
| Goods value | `supplier_order.accepted_amount - delivery_fee`, as in commission |
| SKU id | `SO-<supplier order id>` |
| Contacts | the delivery row; missing or invalid is rejected |

   Removed: the fallback pincodes 560038/560034, the city "Bengaluru" and state "Karnataka", 1000 g, the Rs 500 value, the placeholder phone numbers 9876543210, the placeholder names ("Customer", "Seller", "Supplier", "Outlet"), the SKU default 101, the copied `volumetric_weight`, and Porter's `customer.name` "Costonomy Mandi" (now "Costonomy", per the naming rule).
5. **`base-fee` / `per-km-fee` are removed** from `ShadowfaxProperties`, `PorterProperties` and `application.properties`. The platform rate card (`delivery.baseFee` etc. in `DeliveryFeeQuoteService`) is separate and unchanged: it is our own price and is labelled `ESTIMATED`.
6. When no carrier can answer, checkout falls back to the rate card and dispatch ends as QUOTE_FAILED / `NO_SERVICEABLE_PROVIDER`, as before.

### What would re-enable each carrier
A fare and ETA field verified against a live sandbox response, read through a required-field helper as Borzo does (D-117), plus a booking path that carries that fare into `Booking.amount`. For Shadowfax also confirm the `actual_weight` unit (assumed grams), whether `volumetric_weight` is required, what `total_amount` means for Prepaid, and the `category` values. For Porter also confirm auth (the client sends both `x-api-key` and a Bearer token, which is a guess), the endpoints and the address fields.

### Still open (not changed here)
- Porter `getStatus` invents a PICKED_UP event timestamped five minutes in the past when DELIVERED is the first status seen. Both Porter and Shadowfax give an event the current time when the carrier's timestamp cannot be read. Both break doc 06 §8.
- Tracking URLs for Shadowfax and Porter are built from unverified patterns.
- Two weight calculations disagree (`DeliveryDirectory.calculateWeightKg` counts 1 kg per unit of unknown type; `consignmentWeightGrams` uses 500 g per piece). Decide which is authoritative before any carrier is re-enabled; declaring an estimated weight can cause re-weigh charges.
- Pidge still defaults missing fields (D-117). Borzo sums distance with a default of 0.
- `ShadowfaxDeliveryFlowIT` leaves the SHADOWFAX row enabled for later tests that share the database.

---

## D-122 — Shiprocket delivery provider integration alongside Pidge, Borzo, Shadowfax and Porter
**2026-10-02 · Settled**

Shiprocket is integrated as a fifth carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, Shadowfax, and Porter. The implementation adheres to the provider SPI pattern
established in doc 06 §3 and decisions D-117, D-119, D-120, and D-121.

### Dual-gate activation
Shiprocket is gated by:
1. **Application configuration gate**: `costonomy.mp.shiprocket.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `ShiprocketDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`code = 'SHIPROCKET'`),
   seeded disabled (`enabled = 0`) via migration `V48__delivery_provider_shiprocket.sql`.
   Both gates must be active for Shiprocket to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Token authentication via `Authorization: Bearer {token}` using direct API token
  or retrieved dynamically via `POST /v1/external/auth/login` and cached for 230 hours.
- **Serviceability & Fare Estimation**: `GET /v1/external/courier/serviceability/` passing
  `pickup_postcode`, `delivery_postcode`, `weight`, and `cod=0`.
  Parses `data.available_courier_companies`, selecting the lowest available carrier rate,
  distance, and ETA.
- **30 km Intra-City Boundary (D-120)**: Distance $> 30.0$ km returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")`
  without making an HTTP call.
- **Booking**: `POST /v1/external/orders/create/adhoc` with structured pickup/drop addresses,
  pincodes, contact details, and item details. Returns Shiprocket `shipment_id` as `providerDeliveryId`.
- **Status tracking & Polling**: `GET /v1/external/courier/track/shipment/{shipment_id}` inspecting
  `current_status` and activities. The status mapper transforms Shiprocket statuses
  into domain `DeliveryStatus`. Historic events or synthetic transitions guarantee that `PICKED_UP`
  precedes `DELIVERED` newest-first, allowing `DeliveryOrderBridge` to advance the supplier order
  to `DELIVERED` while `uk_delivery_event_provider` suppresses duplicate event rows.
- **Cancellation**: `POST /v1/external/orders/cancel` sending `ids: [shipment_id]`.

---

## D-123 — LoadShare Networks delivery provider integration alongside Pidge, Borzo, Shadowfax, Porter and Shiprocket
**2026-10-02 · Settled**

LoadShare Networks is integrated as a sixth carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, Shadowfax, Porter, and Shiprocket. The implementation adheres to the provider SPI pattern
established in doc 06 §3 and decisions D-117, D-119, D-120, and D-121.

### Dual-gate activation
LoadShare is gated by:
1. **Application configuration gate**: `costonomy.mp.loadshare.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `LoadshareDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`code = 'LOADSHARE'`),
   seeded disabled (`enabled = 0`, `priority = 22`) via migration `V45__delivery_provider_loadshare.sql`.
   Both gates must be active for LoadShare to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Secured via `Customer-Code: {customer-code}` and `Checksum: {sha256}` headers calculated via
  `SHA-256(${authToken}|${customerCode}|${orderId})`.
- **Serviceability & Fare Estimation**: `POST /hyperlocal/v2/order/checkServiceability` passing structured pickup and drop
  tasks with coordinates, address, and goods value. Extracts real carrier fare (`fare.value`, `unit`), distance (`predictedDistanceInMetre`),
  and SLA (`promisedSlaInEpoch`). Fails closed (D-121) if no fare is returned.
- **30 km Intra-City Boundary (D-120)**: Distance $> 30.0$ km returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")`
  without making an HTTP call.
- **Booking**: `POST /hyperlocal/v2/order` with task payloads, normalized phone numbers (`+91XXXXXXXXXX`), and coordinates. Returns LoadShare `orderId` as `providerDeliveryId`.
- **Status Tracking & Polling**: `GET /hyperlocal/v2/order/{orderId}/track` retrieving status and `statusHistory`.
  `LoadshareStatusMapper` transforms status codes (`assigned`, `arrived_at_pickup`, `picked_up`, `in_transit`, `reached_drop`, `delivered`, `cancelled`, etc.)
  into `ProviderDeliveryStatus`. Deduplicated on `uk_delivery_event_provider`. Synthetic event sequencing ensures `PICKED_UP` precedes `DELIVERED`.
- **Driver Location**: `GET /hyperlocal/v2/order/{orderId}/track` extracting `currentLocation` (`latitude`, `longitude`, `bearing`, `speed`).
- **Cancellation**: `POST /hyperlocal/v2/order/{orderId}/cancel` sending `cancellationReason`.
- **Resilience & Rate Limiting**: 20 RPS local token-bucket throttle; retryable `DeliveryProviderException` on network timeout and 5xx errors.

---

## D-124 — Blowhorn delivery provider integration alongside Pidge, Borzo, Shadowfax, Porter, Shiprocket and LoadShare
**2026-10-02 · Settled**

Blowhorn is integrated as a seventh carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, Shadowfax, Porter, Shiprocket, and LoadShare Networks. The implementation adheres to the provider SPI pattern
established in doc 06 §3 and decisions D-117, D-119, D-120, and D-121.

### Dual-gate activation
Blowhorn is gated by:
1. **Application configuration gate**: `costonomy.mp.blowhorn.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `BlowhornDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`code = 'BLOWHORN'`),
   seeded disabled (`enabled = 0`, `priority = 23`) via migration `V46__delivery_provider_blowhorn.sql`.
   Both gates must be active for Blowhorn to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Secured via `API_KEY: {apiKey}` and `Authorization: Bearer {apiKey}` headers.
- **Serviceability & Fare Estimation**: `POST /v1/serviceability` passing structured coordinates, vehicle type, weight, and addresses.
  Extracts real carrier fare (`fare.amount`, `currency`), distance (`distance_km`), and ETA (`estimated_delivery_time_minutes`).
  Fails closed (D-121) if no carrier fare is returned.
- **30 km Intra-City Boundary (D-120)**: Distance $> 30.0$ km returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")`
  without making an HTTP call.
- **Booking**: `POST /v1/orders` with pickup/delivery points, vehicle type (`2_WHEELER`, `3_WHEELER`, `TATA_ACE`), normalized phone numbers (`+91XXXXXXXXXX`),
  and coordinates. Returns Blowhorn `awb_number` / `order_id` as `providerDeliveryId`.
- **Status Tracking & Polling**: `GET /v1/orders/{orderId}/track` retrieving status, driver details (`name`, `phone`, `vehicle_number`), and `events`.
  `BlowhornStatusMapper` transforms status codes (`assigned`, `arrived_at_pickup`, `picked_up`, `in_transit`, `reached_drop`, `delivered`, `cancelled`, etc.)
  into `ProviderDeliveryStatus`. Deduplicated on `uk_delivery_event_provider`. Synthetic event sequencing ensures `PICKED_UP` precedes `DELIVERED`.
- **Driver Location**: `GET /v1/orders/{orderId}/track` extracting `current_location` (`latitude`, `longitude`, `bearing`, `speed`).
- **Cancellation**: `POST /v1/orders/{orderId}/cancel` sending `cancellation_reason`.
- **Resilience & Rate Limiting**: 20 RPS local token-bucket throttle; retryable `DeliveryProviderException` on network timeout and 5xx errors.

---

## D-125 — Delhivery delivery provider integration alongside Pidge, Borzo, Shadowfax, Porter, Shiprocket, LoadShare and Blowhorn
**2026-10-02 · Settled**

Delhivery is integrated as an eighth carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, Shadowfax, Porter, Shiprocket, LoadShare Networks, and Blowhorn. The implementation adheres to the provider SPI pattern
established in doc 06 §3 and decisions D-117, D-119, D-120, and D-121.

### Dual-gate activation
Delhivery is gated by:
1. **Application configuration gate**: `costonomy.mp.delhivery.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `DelhiveryDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`code = 'DELHIVERY'`),
   seeded disabled (`enabled = 0`, `priority = 24`) via migration `V47__delivery_provider_delhivery.sql`.
   Both gates must be active for Delhivery to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Secured via `Authorization: Token {apiToken}` header.
- **Serviceability & Fare Estimation**: `GET /api/kinko/v1/invoice/charges.json` querying charges with origin and destination pincodes and weight in grams.
  Extracts real carrier fare (`total_amount` or `gross_amount`). Fails closed (D-121) if no carrier fare is returned.
- **30 km Intra-City Boundary (D-120)**: Distance $> 30.0$ km returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")`
  without making an HTTP call.
- **Booking**: `POST /api/cmu/create.json` pushing shipment and pickup location data with normalized phone numbers (`+91XXXXXXXXXX`) and pincodes. Returns Delhivery `waybill` as `providerDeliveryId`.
- **Status Tracking & Polling**: `GET /api/v1/packages/json/?waybill={waybill}` retrieving `ShipmentData.Shipment.Status` and `Scans`.
  `DelhiveryStatusMapper` transforms status codes (`manifested`, `pickup_scheduled`, `reached_pickup`, `picked_up`, `in_transit`, `out_for_delivery`, `delivered`, `cancelled`, etc.)
  into `ProviderDeliveryStatus`. Deduplicated on `uk_delivery_event_provider`. Synthetic event sequencing ensures `PICKED_UP` precedes `DELIVERED`.
- **Cancellation**: `POST /api/p/edit` sending `cancellation: true`.
- **Resilience & Rate Limiting**: 20 RPS local token-bucket throttle; retryable `DeliveryProviderException` on network timeout and 5xx errors.

---

## D-126 — Xpressbees delivery provider integration alongside Pidge, Borzo, Shadowfax, Porter, Shiprocket, LoadShare, Blowhorn and Delhivery
**2026-10-02 · Settled**

Xpressbees is integrated as a ninth carrier in the multi-carrier delivery auction,
joining Pidge, Borzo, Shadowfax, Porter, Shiprocket, LoadShare Networks, Blowhorn, and Delhivery. The implementation adheres to the provider SPI pattern
established in doc 06 §3 and decisions D-117, D-119, D-120, and D-121.

### Dual-gate activation
Xpressbees is gated by:
1. **Application configuration gate**: `costonomy.mp.xpressbees.enabled=true`
   (defaults to `false` in base `application.properties`). When `false`,
   `XpressbeesDeliveryProvider` bean is not registered (`@ConditionalOnProperty`).
2. **Database registry gate**: A row in `delivery_provider` (`code = 'XPRESSBEES'`),
   seeded disabled (`enabled = 0`, `priority = 25`) via migration `V48__delivery_provider_xpressbees.sql`.
   Both gates must be active for Xpressbees to participate in delivery quote auctions.

### API Contract mapping
- **Authentication**: Secured via `Authorization: Bearer {token}` header.
- **Serviceability & Fare Estimation**: `POST /v1/courier/serviceability` querying serviceability with origin and destination pincodes, order amount, and weight in kg.
  Extracts real carrier fare (`data.rate` or `charges.total_amount`). Fails closed (D-121) if no carrier fare is returned.
- **30 km Intra-City Boundary (D-120)**: Distance $> 30.0$ km returns `Quote.unserviceable("Exceeds 30 km intra-city radius limit (...)")`
  without making an HTTP call.
- **Booking**: `POST /v1/shipments/create` pushing order and pickup/delivery details with normalized phone numbers (`+91XXXXXXXXXX`) and pincodes. Returns Xpressbees `awb_number` as `providerDeliveryId`.
- **Status Tracking & Polling**: `GET /v1/shipments/track/{awb_number}` retrieving `status` and `history`.
  `XpressbeesStatusMapper` transforms status codes (`manifested`, `pickup_scheduled`, `reached_pickup`, `picked_up`, `in_transit`, `out_for_delivery`, `delivered`, `cancelled`, etc.)
  into `ProviderDeliveryStatus`. Deduplicated on `uk_delivery_event_provider`. Synthetic event sequencing ensures `PICKED_UP` precedes `DELIVERED`.
- **Cancellation**: `POST /v1/shipments/cancel` sending `awb_number` and cancellation reason.
- **Resilience & Rate Limiting**: 20 RPS local token-bucket throttle; retryable `DeliveryProviderException` on network timeout and 5xx errors.

---

## D-127 — Delivery Slots, Recurring Subscriptions, and Delivery Mode Gating
**2026-10-02 · Settled**

**Decision:**
1. **Delivery Mode Flexibility & Gating**:
   - Buyers and suppliers can trade via three delivery modes: Store Pickup (`PICKUP`), Supplier Own Delivery (`SUPPLIER_DELIVERY` / `SUPPLIER_OWN`), and Costonomy Marketplace Delivery (`COSTONOMY_DELIVERY` / `COSTONOMY`).
   - **Logistics Dispatch Gating Rule**: Automated courier booking and quote auctions (`quoteAndBook`) kick off *only* if `COSTONOMY` delivery mode is selected. For `PICKUP`, courier booking is bypassed completely. For `SUPPLIER_OWN`, the delivery record assigns the store contact as driver without dispatching to external third-party couriers.
2. **Delivery Slots**:
   - Suppliers configure daily time windows (e.g., Morning 06:00 - 10:00) with daily order capacity (`max_orders_per_day`) and same-day order cutoff times (`order_cutoff_time`).
   - Slot availability endpoint evaluates cutoff for today's date and remaining capacity against active orders before allowing checkout.
3. **Recurring Subscriptions (BigBasket Daily Model)**:
   - Buyers can subscribe to SKUs with frequencies (`DAILY`, `WEEKDAYS`, `ALTERNATE_DAYS`, `WEEKLY`), preferred delivery slots, skip dates, pause, resume, and cancellation.
   - Suppliers receive an operational Daily Manifest aggregating bulk SKU packing volumes and scheduled dispatches grouped by time slot.
   - Daily replenishment orders are generated deterministically and idempotently via `generateDailyOrders`.
---

## D-112 — One wallet entry in full: `GET /outlets/{outletId}/wallet/transactions/{entryId}`

The transaction-details page (money received or paid, with a receipt to share) needs one entry explained, not a list row.
Read-only: no schema change, no migration, no change to the history list.

1. **Our transaction id is the ledger entry id**, a plain decimal string (`"184"`). `entryId` in the path accepts that or
   the list's `key` (`L184`). A returned top-up (`T12`) never touched the ledger, has no transaction id, and is a 404.
2. **Same door as the history.** `ORDER_VIEW` on the outlet; the entry is looked up in the outlet's own wallet, so another
   outlet's entry, a missing one and a malformed id are all `404`, never `403`. The status uses the list's rule
   (`WalletHistoryService.statusOf`), so list and detail cannot disagree.
3. **Counterparty by kind.** QuickScan payment/return: payee name and the VPA masked to the first two characters of the
   handle, bullets, and the `@bank` (`sr••••@okhdfc`). Order payment/refund/dispute refund: the supplier shop and the order
   number. Top-up: the instrument in the history's form (`Card •1111`, `UPI`). Withdrawal and its reversal, refund: the
   statement's plain words.
4. **References are only what we hold.** Order number, QuickScan payment id, Razorpay payment id (top-up, or an order paid
   through Razorpay, or the card payment a withdrawal refunds), our refund id and the provider's refund id once sent, and a
   payout id only when it is a real provider id (a mock's is not shown). **No UTR or bank reference is stored anywhere**, so
   none is returned. Our own transaction id is not repeated in `references`. No keys, signatures or full card/UPI data.
5. **The full VPA leaves only as `actions.payeeVpa`**, and only when `actions.canPayAgain`: a QuickScan payment (not a
   return) whose payee VPA still passes QuickScan's own validation. It is the caller's own past payment; the app needs it to
   prefill a new one. Everywhere else the VPA is masked.

**Tests.** `WalletTransactionDetailIT` (every kind from seeded rows, masking, pay-again, tenancy, 401, 404s) and
`WalletEntryDetailMaskTest`.


## D-113 — The shop's bill on a wallet payment: private pages, read by the cost app we already have

A restaurant that paid from the wallet can keep the shop's bill with that payment: 1 to 5 photo or PDF pages, and what
was read from them, next to what the wallet paid. `POST|GET|DELETE /outlets/{outletId}/wallet/transactions/{entryId}/invoice`.

1. **Only payments made from the wallet take a bill**: `ORDER_PAYMENT` and `QUICKSCAN_PAYMENT`. Any other kind is a 422 with a
   plain sentence. One bill per entry (a unique key, and a 409 `INVOICE_EXISTS` before it is ever hit). Same door as the
   transaction details: `ORDER_VIEW` on the outlet, the entry looked up in the outlet's own wallet, so another outlet's entry
   is a 404, never a 403.
2. **We reuse the cost app's extraction, and keep no prompt.** `HttpInvoiceReader` calls the existing
   `POST {base}/item-purchase/invoice/extract?pageName=WalletBill&outlet=..&userId=..` with a bearer token and one multipart
   `file` part per page. The reading logic stays in one place; if it is improved there, we gain it. Nothing in this repository
   describes how to read a bill.
3. **Only plain fields cross over** (widened on purpose by D-114: the SKU and supplier matches, as id and name only). The cost app's answer carries its own item master (`sku`), supplier records, user ids and
   names and costs, for its own outlet. None of that is ours and none of it may reach our database or our API. The reader maps
   into a small dedicated type (`CostAppInvoice`) that declares only the plain fields; Jackson ignores everything else, so it is
   never parsed into a value we could store. Adding a field there is a decision about what we keep. The first entry in the
   answer without an `error` is taken; if every entry has one, or the array is empty, there is no reading (the attempt failed,
   it is not a reading of nothing). Every string is capped at 500 characters and items at 100: it is text from someone's paper.
4. **Private storage, separate from public images.** `FileStorage` is for public images (permanent URLs, year-long cache), so bills
   get their own `InvoiceStorage`: local disk for development (`var/invoices`, ignored by git) and S3 for production
   (server-side encryption AES256 on every put, no ACL, credentials from the default chain, never properties). Keys are
   `invoices/<outletId>/<yyyy-MM>/<uuid>-p<page>.<ext>`: never the customer's file name. A file is judged by its first bytes
   (JPEG, PNG, WebP, PDF), never by its name or declared type; each is at most 5 MB. The request ceiling was raised from 6 MB to
   26 MB so five such files fit; every file is still checked against 5 MB.
5. **Pages are reached by short-lived links only.** S3: a presigned GET for 5 minutes. Local: a signed token route
   (`/invoice-files/{token}`; token = outlet, page id and expiry under an HMAC) that checks signature, expiry and that the page
   belongs to the outlet in the token. It takes a page id, never a storage key. The response never contains a key, bucket,
   token or provider data.
6. **Reading never blocks the upload.** The upload commits with status `READING` and returns 201; the reading runs afterwards on
   a small bounded executor. A failure (reader down, slow, or no bill found) keeps `READING` and counts an attempt; a job
   (every 2 minutes, ShedLock) retries bills not tried for a minute, up to 5 attempts, then `UNREADABLE` with "We could not read
   this bill. You can still view the photo." The pages are never touched by any of this. No database transaction is held while
   the reader runs.
7. **The check is the point.** `check.paid` is what the wallet paid, `check.billTotal` what was read; `matches` is true when they
   differ by at most 0.50 rupee, and null while there is no bill total. `difference` is bill total minus paid.
8. **A cap per outlet per day** (default 20 bills, Asia/Kolkata day) returns 429 with a plain sentence. It counts bills that
   exist: a bill removed and added again is not counted twice, so the cap limits what is held per day, not total uploads.
9. **Production guard.** `ProductionProviderGuard` refuses to start a production profile on LOCAL bill storage or the FAKE reader.
   Both are the defaults, so a deploy that forgets them is refused rather than quietly run on them.
10. **Migration V49.** V45 to V48 are reserved for the renumbering of the payments migrations, so this is `V49__wallet_entry_invoice.sql`
    (two new tables, nothing existing changes). Adding the transaction-details `invoice` and `actions.canAddBill` needs no schema change.
11. **Not done: duplicate detection.** A bill whose file hash already exists on another entry of the same outlet is stored like any
    other. The hash is kept per page, so it can be added later with an index on it.

**Static token.** The reader's bearer token is a setting read on every call, so it can be replaced at runtime later. For now it is
one static token; a production service account is still to be arranged.

**Tests.** `WalletInvoiceIT`, `WalletInvoiceRetryIT`, `WalletInvoiceHttpReaderIT` (against a local stub of the cost app that returns
`sku`, `supplier` and user data and asserts none of it is stored or returned), unit tests for the byte check, key layout, link
signing, mapping and caps, the check, the S3 adapter (mock client, offline presigner) and the production guard.

## D-114 — Reviewing the bill: a service sign-in to the cost app, its matches kept, the user's version saved here

The mobile app gets a copy of the cost app's "Upload Invoice" review screen for the bill on a wallet payment: the user
checks and edits the supplier, invoice number, payment status, invoice and stock-in dates, the lines (each matched to a
cost-app SKU, with its price per unit and a price-deviation warning) and delivery. The owner authorised calling the
**production** cost API from our server for the extraction and for read-only lookups. **No writes to the cost system**
are authorised: "Create Supplier", "Create SKU" and saving the review only record the user's choice on our bill row.

1. **A service sign-in replaces the static token** (`CostApiSession`). The cost app issues 15-minute access tokens:
   `POST /auth/login {username, password, provider:"local"}` answers `{accessToken, refreshToken, tokenType, expiresIn, user}`;
   `POST /auth/refresh {refreshToken}` answers `{accessToken, tokenType, expiresIn}` (a new access token only, so the refresh
   token we hold is kept). We sign in lazily, keep the token in memory with its expiry (`expiresIn`, else the JWT `exp` read
   without verifying it, else 15 minutes) and renew it 60 s before it expires. A 401 on any call: refresh and retry once, then
   sign in afresh and retry once, then give up. One sign-in at a time (a lock; the others wait and reuse its token). A refused
   or failed sign-in is not repeated for 30 s: callers in that window are told "unavailable" without the cost app being asked.
   Tokens and the password are never logged, never in an exception message, never in an answer; `toString` masks them. The
   static `token` setting stays as an override for quick tests (read per call, never renewed, a 401 with it is final).
   Settings: `INVOICE_READER_USERNAME`, `INVOICE_READER_PASSWORD` (a private env file, never committed), `INVOICE_READER_USER_ID`
   and `INVOICE_READER_OUTLET` (the cost-app ids of that account, sent with every extraction and lookup).
2. **An unavailable cost app is not the bill's fault.** A reading that fails because the sign-in is refused, backing off, or
   every token is refused keeps the bill READING with "We could not read this bill yet. We will try again." and does **not** use
   up one of its 5 attempts; the bill is read once the sign-in works again. The production guard refuses the HTTP reader
   without a username and password (or the token override); it only checks that they are set, never prints them.
3. **The reading keeps the cost app's matches, and nothing else of it** (replaces "plain fields only" in D-113 §3, on purpose).
   Per line `skuMatch {id, name, unit, unitPrice, categoryName}` from the line's `sku` (`SKUDetailFullResponse`) and per bill
   `supplierMatch {id, name}` from `supplier` (`SupplierResponse.supplierName`). Per line also `amount` (the cost app's
   `taxableAmount`) and `tax` (`taxAmount`). **The SKU's price per unit is `itemPrice`** (else `initialItemPrice` for a SKU never
   bought): that is what the cost app's own screen shows under the SKU ("₹360.00/KG") and checks deviations against; the SKU's
   `unitPrice` field is not used. Everything else (users, costs, yield, wastage, HSN, item master, contact and tax ids) is still
   never parsed. Names are capped at 150, units at 20, categories at 100, items at 100. `sno`, `description` and `hsn` are not
   kept: the screen does not need them.
4. **The review is ours, the reading is never overwritten.** V50 adds `review_json`, `reviewed_at`, `reviewed_by` to
   `wallet_entry_invoice` (V45 to V48 stay reserved). The invoice answer gains `version`, `draft` and `review`. `draft` is what
   the screen starts from when there is no review: built from the reading (supplier match, else the vendor name without an id;
   each line with its SKU match; stock-in date today in India; PENDING; delivery as read, else 0), or an empty form for an
   UNREADABLE bill (supplier name '', no lines: the user fills it in by hand). `PUT .../invoice/review` saves the user's version
   when the bill is READ or UNREADABLE (409 `INVOICE_STILL_READING` while reading), with `version` for optimistic concurrency
   (409 `INVOICE_CHANGED` when stale), audited as `WALLET_INVOICE_REVIEW`. A line's `lineNo` points at the bill line it came
   from; its `fromInvoice` is then taken from the reading by the server, never from the request. Unknown fields, and computed
   fields sent back, are ignored.
5. **The money follows the cost app's screen, and only the server adds it up** (BigDecimal, 2 decimals, half up). A line's
   amount is before tax, its total = amount + tax, its item price = total / quantity. Subtotal = sum of amounts, tax = sum of
   taxes, total = subtotal + tax + delivery. The screen's "Auto-summed from the line items — edit to override" caption is about
   **subtotal and tax**, not delivery: delivery is a charge of its own on the bill (tax inclusive), pre-filled from what was read
   and typed by the user, never derived from the lines; `deliveryOverridden` says it differs from what was read. A line is flagged
   `ABOVE`/`BELOW` when its item price is more than 50% away from the SKU's price per unit, as on the cost app; the user can mark
   it `ignoredDeviation`. **Not copied:** the cost app also lets the user type over the subtotal and the tax; we do not (the sums
   are always the lines'), and its third payment status `CANCELLED` is not offered. `check` compares what was paid with the
   reviewed total when there is a review, else the read total; the transaction page's bill line shows the reviewed supplier and total.
6. **Pickers read the cost app, read only.** `GET /outlets/{outletId}/invoice-lookups/suppliers?q=&limit=` and
   `.../skus?q=&supplierId=&limit=` (ORDER_VIEW on the outlet, as for the bill). The cost app has no search: we call
   `GET /supplier/list` and `GET /sku/list/expand` with `outlet=<configured cost outlet>&userId=<configured cost user>&status=false`
   as its own screen does, keep id and name (SKUs: unit, price per unit, category; suppliers: no city, the cost app only has
   `cityId`), drop disabled rows, keep the lists 60 s, filter by `q` (case-insensitive, names starting with it first) and cut to
   `limit` (default 20, 1 to 50; `q` at most 60 characters). Only GET, only those two paths (a hard-coded allow-list). The caller's
   outlet is checked on our side and never sent. A per-outlet limit (60 a minute, `INVOICE_LOOKUPS_PER_MINUTE`) answers 429; the
   cost app down is a plain 503. `supplierId` is accepted but not applied: the cost app's SKU list carries no supplier. The FAKE
   provider answers fixed lists (Kosta Delights - Sea Food; Prawns 16/20 360/KG id 9465, PRAWNS 21/25 300/KG id 152,
   Prawns 30/40 270/KG id 9001, Prawns 30/50 250/KG id 9002, ...) and the fake reader now carries the matching ids.

**Not done.** No write of any kind to the cost system. The live production check (sign-in, extraction, lookups) is the owner's
to run (`27-live-test-runbook.md`); everything here is tested against a local stub of the cost app only.

**Tests.** `CostApiSessionTest` (sign-in and extract, renewal before expiry, 401 then refresh, refused refresh then sign-in,
three 401s, wrong password with back-off, 10 callers and one sign-in, timeout, no secret in any log line or error at TRACE,
JWT `exp`), `HttpInvoiceReaderTest` (allowed fields only, matches from `itemPrice`), `CostLookupsTest` (GET only, configured outlet,
allow-list, fields, filter, cache, limits, 503), `InvoiceReviewsTest` (draft, money to the paisa, every validation sentence),
`WalletInvoiceReviewIT`, `WalletInvoiceReaderLoginIT` and the extended `WalletInvoiceHttpReaderIT`. Mutations: logging the access
token once fails `secretsNeverLogged`; passing the whole `sku` object through fails `noInternalDataKept` and
`internalDataNeverStoredOrReturned`.

## D-115 — Review fixes for the bill: bounded reading, bill-level tax, fixed calls, an outlet map, safe retries

Two independent reviews of D-113/D-114 (one of the API, one of the mobile review screen) found two serious problems and a set
of contract gaps. This entry changes D-114 where it says so. V51 adds seven nullable or defaulted columns to
`wallet_entry_invoice`; nothing existing changes.

1. **A reading always ends, and one bill can cause at most `max-attempts` (5) extraction calls.** A currency printed as
   "Indian Rupees (INR)" (19 characters for a `VARCHAR(16)`) or a total of 10^15 (for `DECIMAL(19,4)`) made the result write
   fail; the attempt was never counted, and the bill was sent to production extraction again every two minutes, forever.
   Now: the currency is a three-letter code when one is printed (`INR`), else the text when it fits 16 characters, else
   none; any number of 10^14 or more either way is unreadable and dropped; at most 4 decimals are kept
   (`InvoiceReading.capped()`). The claim counts the call (`extract_calls`) in its own committed transaction before the
   reader runs and refuses once 5 were made (the bill is then UNREADABLE). If the result still cannot be written, a failed
   attempt is counted in a fresh transaction (`REQUIRES_NEW`), so the attempts always move on.
2. **The cost app unavailable uses no attempt, and waits.** 401 is handled by the session as before. 403, 408, 429 and 5xx
   from the cost app (and a sign-in that fails or backs off, and a dropped connection) mean "unavailable": the bill stays
   READING with "We could not read this bill yet. We will try again.", `unavailable_count` goes up and `next_try_at` is set
   1, 2, 4... minutes ahead, at most 30. A refusal that started no reading (sign-in, 403, 429, connection refused) is given
   back from `extract_calls`; a 408, a 5xx or a broken answer may have started one and counts. After `max-unavailable` (48,
   about a day) such tries the bill is UNREADABLE. Only a 200 that cannot be used (no invoice, every entry with `error`,
   not JSON), another 4xx, or a timeout uses an attempt. The invoice answer shows `unavailableCount` and `nextTryAt`.
   Worst case for one bill: 5 extraction calls that may have done work, plus at most 48 refused ones spread over a day.
3. **No immediate second extraction.** One call per reading; after a timeout the retry job decides. The timeout grows with
   the pages: 60 s, plus 45 s per page after the first, at most 5 minutes (`INVOICE_READER_TIMEOUT`,
   `..._TIMEOUT_PER_EXTRA_PAGE`, `..._TIMEOUT_MAX`). The duration of each call is logged.
4. **Bill-level tax (`taxOverride`).** The cost app uses the bill's tax when the lines carry none, and lets the user type over
   it. The review request, the review and the draft gain `taxOverride` (number or null). `tax` = `taxOverride` when set, else
   the sum of the line taxes; total = subtotal + tax + delivery, as before. The draft sets `taxOverride` = the bill's tax when
   the line taxes add up to 0 and the bill shows a tax above 0; otherwise null. Validated like money (0 or more, 12 digits,
   2 decimals). The reviewer's case (subtotal 2640, tax 180, total 2820, paid 2820) now drafts 2820 and still matches after
   the untouched draft is saved.
5. **Only five fixed calls can be made to the cost app.** `CostApiSession.call(Call, query, body, timeout)` replaces the
   public `send(Function<String, HttpRequest>)`: `Call` is an enum of `LOGIN` and `REFRESH` (made by the session only),
   `EXTRACT` (POST `/item-purchase/invoice/extract`), `SUPPLIER_LIST` (GET `/supplier/list`) and `SKU_LIST` (GET
   `/sku/list/expand`). The session builds every URL from the configured base URL (query values encoded, scheme, host and
   port checked), refuses a base URL that is not https (http only for localhost and 127.0.0.1, for tests) or carries user
   info, query or fragment, and never follows a redirect. No other method or path can be written in code. **Operationally the
   cost-app account must be least-privilege**: a dedicated, non-admin user whose role holds only reading purchase data and
   the invoice-extract permission, for the mapped cost outlets only. The cost app's extract endpoint needs `WRITE_PURCHASE`,
   so that account could create purchases through the cost app's own API; we never call one, but the account must not be a
   super admin (which skips the cost app's outlet check).
6. **Each outlet sees only its own cost outlet** (`INVOICE_COST_OUTLET_MAP`, `mpOutletId:costOutletId,...`). Lookups, the
   extraction and the cache use the mapped cost outlet; lists are cached per cost outlet; a list is loaded under a lock of its
   own (never one lock for all, never network I/O under a global lock) and a failure is remembered for 10 s. An outlet without
   a mapping gets `403 INVOICE_LOOKUP_NOT_AVAILABLE` ("Supplier and SKU lists are not available for this outlet. You can still
   type a name.") before the cost app is asked, and its bills are still read (with the reading account's own outlet,
   `INVOICE_READER_OUTLET`) but without the cost app's supplier and SKU matches, in the reading, the stored JSON and the
   draft. The old single `reader.outlet` for everything is now only a fallback when the map is empty **and**
   `INVOICE_COST_OUTLET_FALLBACK=true` (default false; refused in production). The settings are read on each use.
7. **Writing a bill needs the payment permission.** Upload, `PUT .../invoice/review` and `DELETE` need `QUICKSCAN_PAY` on the
   outlet (owner, admin, purchase manager, finance staff: the roles that pay a shop from the wallet) after the usual
   `ORDER_VIEW` scope check; a user who only sees the outlet gets 403 `FORBIDDEN`. GETs and lookups keep `ORDER_VIEW`.
8. **An edit cannot hide a difference.** `check` gains `readingTotal` and `matchesReading`, always against the total as read.
   The `WALLET_INVOICE_REVIEW` audit's reason records the old and new total and the versions ("total 2820.00 -> 2126.09,
   version 3 -> 4"). `review_history_json` keeps the earlier reviews' `{at, by, total, version}`, newest last, at most 20.
9. **Safe retries of a save** (`Idempotency-Key` on PUT, optional, at most 128 characters). The last key per bill is kept
   with the SHA-256 of the parsed request and the version it produced. The same key with the same body again answers 200 with
   the bill as it is now (no new version, no 409, no second audit); the same key with another body is 422
   `IDEMPOTENCY_KEY_REUSED`. A lost answer is therefore no longer reported to the user as "someone else changed this bill".
10. **Contract details for the mobile app.** `draft` is present whenever the bill is READ or UNREADABLE, also next to a
    `review`, so "start over" and "reset delivery" go back to what was read. `lineNo` is null for a line the user added and
    1..N, each once, for a bill line; `fromInvoice` and `deliveryOverridden` are never read from a request. A SKU's
    `unitPrice` may have 4 decimals (cost-app per-gram prices); quantity stays at 9 integer digits and 3 decimals (the app
    uses 9); amounts, tax and delivery 12 and 2. A `MALFORMED_REQUEST` names the field when it is known
    (`details.fields["items[1].quantity"]`, or the query parameter or header) with a plain sentence, never the value; an
    empty PUT body is a `VALIDATION_ERROR` with `details.fields.body`. The stock-in date may be at most one day after today
    in Asia/Kolkata ("The stock-in date cannot be later than tomorrow."). The SKU lookup has no `supplierId` (unknown
    parameters are ignored); `q` over 60 characters is a 400; suppliers are `{id, name}` only.
11. **Smaller fixes.** Money in the JSON columns is stored as strings, because MySQL's JSON type turns decimals into doubles;
    GET and PUT now give the same figures with the same scale. Amounts written as text drop a leading `Rs.`, `INR` or `₹`
    and grouping commas, and anything that is then not a plain decimal is no value ("Rs. 500" is 500, never 0.5). When the
    answer holds several bills the first is kept and `reading.invoiceCount` says how many there were. A sign-in answered 200
    with something that is not JSON (a proxy's page) backs off 30 s like any failed sign-in. The production guard also
    refuses: a static token, a blank username or password, a non-https base URL, user id or reader outlet 0, the outlet
    fallback, and an empty or unreadable outlet map; its messages name the setting, never the value.

**Declined or left as they are.** L6's other validation edges (no lower bound on dates, control characters in names, amount
0, negative credit lines, `description` when `itemName` is missing), L7 (deviation compared after rounding to 2 decimals; the
app's preview is the stricter one), L8's `categoryName` on a review line, and L9 (the retry batch of 50; with the
per-bill ceiling and the back-off a run is bounded, but it can still take long when the cost app is slow).

**Tests.** Unit: `CostApiSessionTest` (+ only the enum's calls, https and no redirects, a non-JSON sign-in backs off),
`HttpInvoiceReaderTest` (+ status mapping, no immediate re-post, timeout by pages, unmapped outlet without matches, values
made to fit, invoice count, no secret in any log line at the call site), `CostLookupsTest` (+ per-cost-outlet lists and cache,
403 for an unmapped outlet, the fallback rule, a failure remembered 10 s, no lock across cost outlets, 4-decimal prices, no
secret logged at the call site), `InvoiceReviewsTest` (+ bill-level tax, 4-decimal SKU prices, an untouched draft always
saves), `InvoiceReadingTest` (+ currency and number limits), `ProductionProviderGuardTest` (+ every new refusal).
Integration: `WalletInvoiceRetryIT` (+ the H1 bill reaches READ with `INR`; an unwritable reading ends UNREADABLE with 5
attempts after exactly 5 calls; back-off 1 then 2 minutes; at most 5 extraction calls; refusals capped at 48),
`WalletInvoiceReviewIT` (+ bill-level tax end to end, the lost-answer retry, two saves at once, view-only 403, ignored
fields, malformed and empty bodies, history and audit), `WalletInvoiceHttpReaderIT` (+ outlet map).

## D-116 — Bill status in the wallet History: one rule, a start date, 'No bill needed', a filter, counts and statements

A kitchen must see at a glance which payments still need the shop's bill. The owner chose the words, the five values, the
start date, the waiver with undo, a server-side filter, counts for a banner and the month sheet, and a Bill column in
statements. V52 adds one table; nothing existing changes.

1. **Which payments need a bill.** Only payments from the wallet of kinds `ORDER_PAYMENT` and `QUICKSCAN_PAYMENT` whose entry
   status is COMPLETED. For these two kinds the History's status (`statusOf`) is always COMPLETED (only a withdrawal is ever
   IN_PROGRESS or RETURNED, and a returned top-up is not a payment); the one paid entry that ended the other way is a QuickScan
   payment whose payout failed and whose money came back, recognised by its `QUICKSCAN_RETURN` (reference
   `quickscan-return-<id>` next to the payment's `quickscan-<id>`, a unique key): it is not eligible. Nor is an order payment
   whose order was cancelled and paid back in full: an `ORDER_REFUND` with the same `supplier_order_id` on the same wallet
   (written at most once per order by `WalletService.refundFor`; found with a `not exists` on `ix_wallet_txn_order`, not a
   join, as (supplier_order_id, kind) has no unique key). A partial money-back (`DISPUTE_REFUND`) keeps the payment eligible:
   the goods came, so the bill is still owed. Top-ups, refunds, withdrawals and their reversals, and returns never are.
2. **One rule, written once** (`BillStatuses`). A bill that exists always shows its own status, whatever the date: `READING`,
   `UNREADABLE`, and `READ` as `REVIEWED` once a review was saved (`reviewed_at`, written with `review_json`), else `ADDED`.
   An `UNREADABLE` bill that a person then filled in by hand (a review saved) is `REVIEWED` too (owner's decision after the
   review, M3; in `resolve`, the filter and the count alike, so the statement says Reviewed). A bill that exists wins over a
   waiver row should both ever exist. Without a bill: 'No bill needed' is `NOT_REQUIRED`; an eligible payment created on or after the tracking start is `PENDING`;
   anything else has none. The same rule is expressed as JPQL over a ledger row with three left joins on unique keys (bill,
   waiver, QuickScan return) and one `exists` (the order's cancellation), so the list, its filter, the counts, the details and
   the statement agree by construction.
3. **The tracking start** (`INVOICE_TRACKING_START`, ISO date, from midnight Asia/Kolkata). Older payments would all say
   pending on the first day; before the start they show nothing, and a bill can still be added from the details page
   (`canAddBill` unchanged). Blank turns tracking off: nothing is PENDING anywhere, bills that exist still show. A value that
   is not a date refuses to start the application.
4. **The list.** Each item gains `bill`: null or `{"status": "PENDING|READING|ADDED|REVIEWED|UNREADABLE"}`; 'No bill needed'
   shows null (no chip). The status is selected by the page's own query (no extra query at all, never one per row; a test
   counts the statements of a 2-row and a 40-row page and requires them equal). The answer gains `billSummary`
   `{pending, reading, unreadable}`, all months, whatever the filters, each equal to what its `bills=` filter returns (M2):
   `pending` counts PENDING (eligible, on or after the start, no bill, not waived); `reading` and `unreadable` count every bill
   of this wallet in that state (unreadable: not yet filled in by hand), whatever its payment's date or eligibility, waived or
   not, because a bill that exists always shows. Each `monthTotals[]` item gains `billsPending` for its Asia/Kolkata month.
   Both come from **one** grouped query (conditional sums, one per month asked for) over the rows that have a bill or can be
   PENDING, never a database time-zone conversion. `billSummary` is sent on the first page only (no `cursor`); later pages
   send it null and skip its work, keeping the month counts (L2). The app reads the banner from the first page only and maps
   a missing or null `billSummary` to null, so later pages change nothing.
5. **The filter.** `bills=` (comma list of the five values, any case; anything else, `NOT_REQUIRED` included, is a 400 with a
   plain sentence) is a where-clause term of the page query, so it composes with `months`, `kinds`, `statuses` and the cursor
   and pages exactly. A row without a bill status never matches (returned top-ups are skipped entirely). `monthTotals` stay the
   ledger's, unfiltered, as D-108 says.
6. **'No bill needed'.** `PUT .../wallet/transactions/{entryId}/invoice/waiver` answers 200 `{"waived": true}` (again 200 when
   already set, nothing written); `DELETE` the same path answers 204 (also when nothing was set). Same doors as adding a bill:
   `ORDER_VIEW` scope (another outlet's or a missing entry is 404) and `QUICKSCAN_PAY` (else 403). Only for an eligible payment
   (422 `INVOICE_NOT_ALLOWED`) without a bill (409 `INVOICE_EXISTS`). A waiver is allowed before the tracking start too
   (eligibility is the kind and status, not the date). Both are audited as `WALLET_INVOICE_WAIVER` on the wallet entry
   (`WAIVED`, then `CLEARED`). Adding a bill removes the waiver in the bill's own transaction (audited, reason "a bill was
   added"); waive and upload lock the entry's ledger row, so they cannot interleave. The row is deleted on undo; the audit log
   keeps who and when. V52: `wallet_entry_invoice_waiver` (unique `wallet_transaction_id`, `outlet_id`, `waived_by`,
   `waived_at`, `version`), V45 to V48 still reserved.
   **The race (M1).** The database runs REPEATABLE READ and takes a transaction's snapshot at its first plain read. Waive used
   to read the wallet and the entry before taking the lock, so a bill committed while it waited was invisible to it and both
   rows could stay. Now the entry's lock (`select ... for update`, scoped to the outlet's wallet) is the **first** statement of
   the waive (and undo) transaction, as it already was for upload's; the bill check reads after it. Whichever comes second
   sees what the first committed: a waive behind an upload answers 409 `INVOICE_EXISTS`; an upload behind a waive removes the
   waiver in its own transaction. Same lock, same order, so no deadlock. A unique-key failure on the waiver insert is taken as
   success only when a waiver row is really there afterwards; anything else is rethrown (L4).
7. **The details page** gains `billStatus` (the full rule, `NOT_REQUIRED` included; an eligible payment before the start is
   null), `actions.canWaiveBill` (only when `billStatus` is PENDING: before the start the app offers just "Add bill", L1) and
   `actions.canUndoWaiver` (waived). The waive endpoint itself still accepts any eligible payment without a bill. Every existing field stays.
8. **Statements.** CSV and PDF gain `Bill` (Pending, Reading, Added, Reviewed, Unreadable, No bill needed, or blank), `Shop` and
   `Bill no.` (the review's when saved and not blank, else as read), after the existing columns, which do not move and still
   reconcile. One query for the period's bill columns, however many rows. The PDF is now A4 landscape to fit them.
9. **The wallet's recent list** (`GET /outlets/{outletId}/wallet`, `recent[]`, the Wallet screen's 'Recent' card; `POST
   .../wallet/top-ups/{topUpId}/confirm` answers the same wallet, built by the same code). Each entry gains the same `bill` as the History list item for that
   entry: null or `{"status": ...}`, 'No bill needed' null. Read by `BillStatuses.forListOf` (the `forEntries` query, then
   `forList`, the History's own rule) through `WalletHistoryService.billsOf`: one query for the whole list, never one per row.
   These are the only wallet-entry lists the API returns: QuickScan's payment list and receipt return QuickScan payments, not
   ledger entries, and carry no bill (the app shows a bill from the transaction details); the mock top-up answers an empty
   `recent`.

**After the independent review.** H1 (a cancelled order asked for a bill), M1 (the waive/upload race), M2 (counts that
disagreed with their filters), M3 (a hand-filled unreadable bill, now REVIEWED), L1, L2 and L4 are fixed as described above.
Left as it is: a sparse `bills=` filter walks the wallet's keyset until it finds a page (L3; fine at today's volumes), and a
READING bill with no pages has no guard in the reader (L5; upload writes the bill and its pages together).

**Tests.** `WalletBillStatusIT` (a seeded matrix: every kind, a returned QuickScan payment, a waiver, a bill in each status, a
microsecond either side of the start, an IST month boundary, a second outlet; summary under every filter; month counts; tracking
off; filter and composition and 400s; a filtered walk of more than two pages over shared instants; the statement count of a
2-row and a 40-row page; details; statement columns, reconciliation and one query), `WalletInvoiceIT` (+ waiver set, again,
undo, again; 422 and 409; a bill clears it; 404/401/403), `BillStatusesTest`, `WalletStatementFilesTest` (+ columns, landscape
pagination), `WalletHistoryIT` (item shape). Mutations: computing the bill with one query per row fails `oneQueryPerPage`
(13 statements became 51); dropping the start date from the SQL fails `summary`, `filter` and `monthTotals`; dropping it from the
Java rule fails `BillStatusesTest.withoutBill`, `listStatuses`, `details` and `statementColumns`. Review fixes:
`cancelledOrderNeedsNoBill` (H1: list, filter, counts, month, details, waive 422, statement; DISPUTE_REFUND stays PENDING; a
bill on a cancelled payment still shows), `countsMatchFilters` (M2: `bills=UNREADABLE` returns 4, `unreadable` is 4; a bill
next to a waiver counts and shows), `unreadableReviewedIsReviewed` and `WalletInvoiceReviewIT.unreadableFilledByHand` (M3,
through the real review endpoint), `summaryOnFirstPageOnly` (L2), `WalletInvoiceIT.waiverWaitingBehindABill` and
`uploadWaitingBehindAWaiver` (M1: a second connection or a waive holds the entry's lock; the other side is shown to be
waiting, then ends with a bill and no waiver), `WalletInvoiceWaiveTest` (L4). Mutations: dropping the cancellation from the
rule fails `cancelledOrderNeedsNoBill`; reading before locking in waive fails `waiverWaitingBehindABill` (200 and a waiver
next to the bill). The recent list (9): `recentCarriesBill` (every status and null among the ten newest, each equal to the
History's item for the same entry; a second outlet's own), `recentTrackingStart` (a microsecond either side of the start, a bill
before it, a returned QuickScan payment, a cancelled order, a dispute; tracking off), `recentOneQuery` (equal statement counts
for 2 and 10 entries). Mutations: one query per entry fails `recentOneQuery` (9 statements became 17); the full rule instead of
the list's fails `recentCarriesBill` (a waived payment showed `NOT_REQUIRED`).

---

---

## D-128 — Catch-weight settles at dispatch; weighing moves no money
**2026-10-04 · Settled** — also records the catch-weight and doorstep parts of V63, V66 and V67. Billing (V64) and cold chain (V65) still have no record.

### What was wrong
Weighing could happen before or after a card was captured, and it moved wallet money immediately. That produced money that nobody had paid or been owed:
- **Duplicate lines** in one request each added their own delta, so 50 copies of a near-zero reading credited the wallet about fifty lines' worth.
- **A partial re-weigh** summed only the lines in the request but applied the result as the whole order's, so re-weighing one line took back another line's refund.
- **Weigh then cancel** released the card hold in full and left the wallet credit in place, so the platform paid the difference.
- **An over-weight debit that could not be covered** was logged and skipped after the order's figures were saved, so the supplier was paid for money never collected.
- **Receiving checked against the ordered quantity**, so at 9.6 kg the buyer had to enter 0.4 kg as missing and was refunded for it a second time.
- **Doorstep refunds always went to the wallet**, whatever paid for the order, turning card money into a closed balance and leaving a credit invoice at its full amount.

### Decision
**Weigh before ready; settle once, at ready; money only ever moves down afterwards.**

1. **Weighing** (`SupplierOrderService.recordDispatchWeights`) is allowed only in CONFIRMED and PREPARING, locks the order first, and refuses a repeated line in one request. It moves no money: it fixes each line's billed quantity, and recomputes the order's subtotal, GST, weight adjustment and final payable from **all** lines, so a partial re-weigh cannot corrupt the total. The line must be flagged catch-weight and sold in a mass unit (GM, KG, OZ, LB); the reading is taken in the line's own unit.
2. **Billed quantity** is `min(reading, accepted)` (`CatchWeight`, the only place the rule lives). Overweight within the band is the supplier's giveaway: billing it would need money beyond a card's authorisation, and the price the restaurant saw was one number. A reading below **-20%** or above **+10%** of accepted is refused as a probable scale error (`costonomy.mp.catch-weight.max-under-percent` / `max-over-percent`). A scale reads to the gram: at most three decimals. All money goes through `Pricing`.
3. **Ready requires every catch-weight line to be weighed.** Otherwise an unweighed line is billed at the ordered quantity on nobody's measurement.
4. **Settlement at ready** goes through the one funding port: `OrderFundingPort.onOrderDispatched(orderId, finalPayable)`, with the order locked.
   - Card: captures `min(finalPayable, authorised)`; the rest of the hold is released, never refunded.
   - Wallet: the wallet paid the accepted total up front; the difference comes back as one `ORDER_ADJUSTMENT` credit, reference `order-settle-{orderId}`, so a repeat credits nothing.
   - Credit: the invoice and the amount drawn come down to the final payable (`CreditInvoiceService.reduceTo`, state-based and so idempotent), recorded as a credit `ADJUSTMENT`. It never takes an invoice below what has already been repaid.
5. **After ready, money only goes down**, through `OrderFundingPort.reduceAfterDispatch` (a doorstep rejection). Card: a refund of the captured payment to the wallet, kind `REFUND` and withdrawable (D-104), reason `DOORSTEP_REJECTION`. Wallet: an `ORDER_ADJUSTMENT` credit. Credit: the invoice comes down. The supplier bears it through `final_payable`; Costonomy never funds a refund.
6. **Receiving** checks received + damaged + missing against the **billed** quantity on a weighed catch-weight line (`TrustDirectory.OrderLine.receivableQuantity`), and against the accepted quantity otherwise. The accepted quantity on the order is untouched. A whole-line rejection refunds the line's stored total exactly; a partial one is priced through `Pricing` and capped at it. The refund goes through the funding port, never straight into the wallet. No credit-note number is invented: the response carries the number actually issued, or none.
7. **Guards where the figures are written.** `supplier_order.final_payable_amount` has a CHECK `>= 0` (V67) and the doorstep update refuses to take it below zero; `CommissionService` throws on a negative base instead of clamping it to zero, which would have paid the supplier nothing and quietly made the platform whole.
8. **Settlement figures:** `SettlementDirectory.orderFigures` now uses `coalesce(final_payable_amount, accepted_amount)`, so dispute-refund coverage is computed on what the supplier is actually owed.

### Who pays what
Rs 100/kg, 5% GST, 10 kg accepted (Rs 1,050).

| Scale reading | Billed | Final payable | Card (held 1,050) | Wallet (paid 1,050) | Credit (invoice 1,050) | Supplier paid on |
|---|---|---|---|---|---|---|
| 9.6 kg | 9.6 | 1,008 | capture 1,008; 42 released, never charged | +42 credited | invoice and draw 1,008 | 1,008 |
| 10.0 kg | 10.0 | 1,050 | capture 1,050 | nothing | 1,050 | 1,050 |
| 10.4 kg | 10.0 | 1,050 | capture 1,050 | nothing | 1,050 | 1,050 (supplier gives 0.4 kg away) |
| 12 kg or 7.9 kg | refused | unchanged | | | | |

After ready, 0.1 kg of the 9.6 kg found missing at the door: Rs 10.50 less (10.00 + 0.50 GST), final 997.50, by each method as in point 5.

### Removed
`WalletService.recordAdjustment` and its debit path, the only code that took wallet money because of a weighing. Over-weight is never billed to anyone.

### Not changed here (open)
- **Order adjustment record and per-method reconciliation.** A card order's capture still reads as a mismatch against settlement where wallet or credit movements are involved, and `SettlementService.approve` does not require a clean reconciliation. Next slice.
- **A doorstep rejection on a pickup order whose capture is still pending** is refused with a retryable conflict (the refund service needs a captured payment); nothing is written.
- **Subscriptions, tax invoices, and the mobile app** are separate follow-ups. Subscription order lines do not yet carry the catch-weight flag at creation (V67 backfills existing ones).
- **Orders weighed under the old code and not yet ready** already carry a wallet adjustment and would also get a reduced capture. Before deploying, check `select supplier_order_id, direction, sum(amount) from wallet_transaction where kind = 'ORDER_ADJUSTMENT' group by 1, 2` against orders not yet COMPLETED or CANCELLED.
- A legacy `ORDER_ADJUSTMENT` debit (the old over-weight surcharge) now raises what the wallet can give back for that order, and a credit lowers it; both are counted by direction.

## D-129 — Post-dispatch reductions are order adjustments, applied through the funding port

After READY money only goes down. Each reduction is one `order_adjustment` row (V68), unique per order and reason (`WEIGHT_SETTLEMENT`, `DOORSTEP_REJECTION`), carrying an idempotency key, the funding method and the funding reference.

1. **Weight shortfall** is settled at READY by `OrderAdjustmentService.settleAtReady` (final payable written and flushed first, then the funding call), recorded as an `APPLIED` row when there is a shortfall.
2. **Doorstep rejection** (`recordDoorstepRejection`) runs under the order lock, is idempotent by existing row, and computes the new final payable as accepted minus all adjustment rows minus the new amount. Receiving takes the order lock first.
3. **Card orders whose capture is still pending** cannot be refunded yet. The row is written `PENDING_CAPTURE`, the order's final payable is already reduced, and `OrderAdjustmentJobs.applyPendingCaptures` (15 s, ShedLock) applies it once the capture lands. A refused capture keeps the row pending and alerts hourly. A pending refund does not block settlement approval.
4. Costonomy never funds a refund: the supplier bears it through the lower final payable. Card refunds go to the wallet's withdrawable balance (D-104); wallet orders are credited back; credit invoices are reduced (`CREDIT_INVOICE_REPAID_BEYOND_CORRECTION` audit when the invoice was already paid past the new figure).
5. V68 backfills final payable for READY+ orders and one row per existing weight shortfall and doorstep refund.

## D-130 — Reconciliation counts collected money per funding method; approval re-checks

`SettlementDirectory.collectedFor` sums card orders from `payment` (captured minus refunded), wallet orders from `wallet_transaction` (debits minus credits) and credit orders from `credit_invoice.amount`, and expects collected to equal gross minus the settlement's own `REFUND` debit lines (`refundsDeductedFrom`): a dispute refund lowers both. Approval reconciles after pending refunds are applied. Counting only `payment` made every wallet or credit order a mismatch.

`SettlementService.approve` re-runs reconciliation inline, in its own transaction, under a settlement row lock (`SettlementRepository.lockById`). If it does not match, approval is refused with 409 `INVALID_STATE_TRANSITION` unless the request carries `acknowledgeMismatchNote`; with it the approval proceeds and `SETTLEMENT_APPROVED_WITH_MISMATCH` is audited. The refusal rolls back, so a refused attempt records nothing; the hourly sweep still records the mismatch. The sweep no longer wraps itself in one transaction.

## D-131 — Unverified carriers fail closed; Pidge keeps working with nothing invented; no committed signing key

1. **Shiprocket, LoadShare, Blowhorn, Delhivery and Xpressbees** behave as Shadowfax and Porter do (D-121): the quote is declined without a carrier call and booking is refused before any HTTP, because none of them has a fare field verified against a live response. A decline is honest; a guessed price is not. Their tracking, location and cancel paths for consignments that already exist are unchanged.
2. **Pidge** stays live. Its quote and booking no longer default the fare (50.00), ETA, distance, weight, vehicle type, contacts or quote id: a missing response field or an incomplete request is a non-retryable error naming the field. A 30 km radius check is added. Its webhook rejects everything when no secret is configured, and the production guard refuses to start with Pidge enabled and no secret.
3. **JWT signing key.** The key that was the default in `application.properties` is in repository history and is treated as leaked: the default is removed, the production guard refuses it (and a missing or short key), and `JwtService` generates a fresh key per start only under the `local` profile. Rotate `JWT_SECRET` wherever the old default was ever used.

## D-132 — Subscriptions generate orders on the normal funding path, one attempt per date, from a scheduler

Verified against the code before changing it: every gap in the review was real. Two differed from the review: `nextDeliveryDate` did not advance on a funding failure (the real bug was the unfunded DRAFT order left behind, which the duplicate check then counted forever), and the supplier endpoint accepted any date, past ones included.

1. **Normal path.** `SubscriptionOrderGenerator` creates the order DRAFT, calls `OrderFunding.arrangeFunding`, then `OrderReleaseService.releaseIfFunded`. Procurement no longer imports wallet or credit services. Releasing calls `onOrderAccepted`, so a credit subscription order now draws its reservation and is invoiced (it never was). `OrderFundingPort.canFund(outlet, store)` lets creation refuse credit without an active agreement.
2. **One transaction per subscription and date.** Anything that stops an order throws `SubscriptionRunException`, rolling back everything including the DRAFT order, so nothing is left to block the date. `SubscriptionRunStore` (own bean, `REQUIRES_NEW`) then records the outcome in `subscription_run` (unique per subscription and date; `GENERATED`, `SKIPPED_NO_OFFER`, `FUNDING_FAILED`, `SKIPPED_PAUSED`, `SKIPPED_INVALID`). A `GENERATED` row is written inside the order's transaction and is never downgraded. The subscription stays ACTIVE.
3. **Trigger.** The supplier endpoint is removed. `SubscriptionJobs` (ShedLock) generates tomorrow's orders hourly from 18:00 to 23:00 India time (`costonomy.mp.subscriptions.generation-cron`), so a wallet topped up the same evening is picked up; each run is idempotent. A date still unfunded after the last run is lost; no back-dated orders.
4. **Notified once per date and outcome.** `SubscriptionFundingFailed` and `SubscriptionOrderSkipped` reach the outlet's users (in-app and push, critical). `SKIPPED_INVALID` covers a slot that stopped being available, a store that stopped delivering or an order below its delivery minimum.
5. **Frequency.** WEEKLY is the same weekday as the start date; ALTERNATE_DAYS is even day offsets from it (`SubscriptionSchedule`). Both used to fire every day.
6. **One live order per subscription per date**, enforced by `supplier_order.subscription_delivery_key` (generated, NULL for cancelled orders) with a unique key (V69, same pattern as V42). The row lock on the subscription serialises two runs; the key is the backstop and stops a duplicate before any money moves.
7. **Pricing.** No available offer means `SKIPPED_NO_OFFER`, never Rs 0. The line unit is the SKU's `pack_unit`; the client's unit is ignored. Lines carry `is_catch_weight` and `requires_cold_chain` as the intent path sets them.
8. **Delivery fee** comes from `DeliveryCharges`, extracted from `IntentOrderCreator` so the two cannot drift. A store that does not deliver is refused, and an order below its delivery minimum is skipped. COSTONOMY delivery is refused for subscriptions in v1; payment is WALLET or CREDIT only.
9. **Authorization.** Pausing, resuming, cancelling and skipping need `PROCUREMENT_CREATE` at OUTLET scope. Someone who can only view the outlet, or a supplier's staff, gets 404 and nothing changes. Reads keep the either-side check; the supplier side is read-only.
10. **Also fixed (intent path).** The free-delivery threshold used to return before the "store delivers" and delivery-minimum checks, so a large order could get supplier delivery from a store that does not offer it. Refusals now come first.
11. **V69 data changes.** Existing unfunded DRAFT subscription orders are cancelled with a reason (no money ever moved for them). Existing ACTIVE subscriptions on a payment method outside WALLET/CREDIT or a delivery mode outside SUPPLIER_DELIVERY/PICKUP are PAUSED, with the reason appended to their notes, so the restaurant chooses again. If a database already holds two non-cancelled orders for one subscription and date (possible after the old race), the unique key fails the migration loudly rather than letting it choose which to cancel: resolve those by hand first.

## D-133 — Tax invoices and credit notes: supplier-issued, behind a flag, nothing invented

**Status: an assumption to be confirmed by a tax adviser, not a compliance claim.** Everything below is gated by `costonomy.mp.billing.tax-invoices.enabled` (default `false`; `true` only in tests). With it off, all five billing routes return 404, nothing listens for the events that would issue a credit note, and no row or number is ever created.

**Who issues.** The supplier is the issuer, with Mandi as technology provider. Generation therefore needs `ORDER_VIEW` on the supplier's own store; a buyer, another supplier, or a call with no actor gets 404 and writes nothing. Both sides can read. (A dedicated invoice permission is a follow-up: today anyone who can view the supplier's orders can generate.)

**Verified against the code before changing it.** All ten gaps in the review were real. Differences: a draft/cancelled gate existed but ran after the existing-invoice early return and returned 400; no code path ever wrote `supplier_order_item.hsn_code`, so every invoice got 9968; any unrecognised state name also returned 36; the delivery fee was added to the total with no line of its own; there are five routes, not six.

1. **No fabricated data.** Supplier legal name, GSTIN, address and state, buyer name, address and a recognisable place of supply, and a product name, HSN code and unit on every supplied line are required. Anything missing is returned together as a 422 `TAX_INVOICE_DATA_MISSING` with `details.missing` (stable field paths such as `supplier.gstin` or `line[41 Fresh Milk].hsnCode`), and nothing is written, no number used. The supplier's state comes from GSTIN digits 1-2 (`GstState`, all current GST state codes); the place of supply is the buyer's GSTIN state, else the outlet's state name matched against the same list. No default, no fallback, no placeholder party. The HSN default (9968, a courier-services code) is gone from code and schema; HSN is copied onto the order line when it is created (SKU first, then the product).
2. **Issuable from READY.** An order may be invoiced once it is ready for collection or dispatched or later (`TAX_INVOICE_NOT_ALLOWED`, 422, before that), after catch-weight has settled (D-128). Automatic issue at READY is a follow-up.
3. **The invoice states what was charged.** Lines use the order's stored taxable value, GST and total at the billable quantity (never raw `dispatched_weight`); the delivery fee is a separate untaxed amount; the sum must equal the order's final payable plus any doorstep refund already taken off, or nothing is written (an `IllegalStateException`, logged). A 9.6 kg reading on 10 kg accepted invoices 1,008.00; a 10.4 kg reading invoices the accepted 10 kg, 1,050.00.
4. **Numbering.** A series per supplier GSTIN, per financial year (April to March, India time), per document type: `INV/2627/000123` and `CN/2627/000123`, at most 16 characters. `document_sequence` is allocated under `SELECT ... FOR UPDATE` inside the transaction that inserts the document (MANDATORY propagation, on purpose: a number taken in its own transaction would stay used when the insert loses a race, leaving a gap). Uniqueness is per supplier (`UNIQUE(supplier_gstin, number)`), not global.
5. **Insert race.** `TaxInvoiceService.generateOrGetInvoice` is deliberately not transactional; `TaxInvoiceStore` inserts in `REQUIRES_NEW` and throws on `uk_tax_invoice_order`; the caller re-reads and returns the winner's invoice (200 for both callers). Deadlocks on the sequence row are retried.
6. **Credit notes** are issued after the check-in commits, by `BillingEventListener` on `ReceivingCompleted` (and by catch-up when the order's invoice is generated), through `CreditNoteStore` in its own transaction. A billing failure can never fail a doorstep check-in. The note reverses what receiving refunded (one shared `Pricing.rejection` helper) and is linked to the invoice; a note issued first is linked when the invoice is generated. One note per order and reason. The invoice records the supply as dispatched; the credit note reverses the rejected part, so invoice minus credit notes equals the final payable.
7. **Module boundary.** Billing reads through a JDBC `BillingDirectory`, not other modules' repositories. `ON DELETE CASCADE` is removed from invoice and credit-note items (nothing is hard-deleted), item rows reference their order lines, and V70 removes the old rows (all of them carried invented values; nothing was in production).
8. **Exports are unverified drafts.** The Tally voucher XML and GSTR-1 CSV stay behind the flag and say so (`X-Export-Status: UNVERIFIED-DRAFT`, a comment in the XML). The CSV is now one row per invoice and rate, a buyer without a GSTIN is B2CS (by place of supply and rate) rather than B2B, and the place of supply reads `36-Telangana`. The Tally ledger names ("Sales Account", "CGST Output", ...) are assumptions and neither format has been imported into Tally or the GST offline tool.

**Not solved. For a tax adviser; none of this is claimed.**
- HSN master data and validation of HSN against the rate charged.
- GSTIN checksum, and registration status and type (composition suppliers cannot charge tax). Only the shape (15 characters, a known state prefix) is checked.
- Per-state registration: a supplier selling from a store in another state than its GSTIN.
- E-invoicing (IRN and QR code) above the turnover threshold.
- TCS under section 52 and TDS under 194-O for marketplace operators.
- Debit notes (for example a weight surcharge; Costonomy never bills more than accepted today).
- GST on the delivery fee: a composite supply taxed at the goods' rate, a separate service, or exempt. It is shown as its own untaxed amount until advised; no SAC code is invented.
- Place of supply where ship-to differs from the buyer's registration; the time of supply (Section 31) and whether to issue automatically at dispatch.
- Invoice cancellation and amendment; the Section 34 time limit for credit notes.
- The GST state code list was transcribed by hand and should be checked.

## D-134 — Cold chain: one place stamps it, declarations are superseded, only a verified carrier carries chilled goods

Also the record V65 (the cold-chain columns, `has_cold_chain_items`, the original vehicle gate) never had. V65 said "restrict carriers to insulated 3-wheelers and 4-wheelers"; this replaces that assumption with recorded capability.

**Verified against the code before changing it.** The review's "cart checkout never sets the flags" does not apply: there is no cart path (D-091), only the intent creator and the subscription generator create orders, and Phase 2 had already made subscriptions set them. The real defects were the logic copied into both creators, `canonical_product.requires_cold_chain` never being read (nothing writes it either), a quote gate that failed open, a checkout fee quote with no gate at all (it fell back to the rate card, so a chilled order could be sold carriage nobody could provide), and the supplier's declaration being edited in place. The Mock, Pidge and Borzo adapters echo back the vehicle we asked for, so "the quote states a vehicle" is not evidence either.

1. **One stamper.** `OrderLineStamper` copies a SKU's cold chain, catch-weight and HSN onto every line and sets the order's cold-chain flag from its lines (recomputed, never accumulated). The intent creator and the subscription generator call it; the three `DeliveryDirectory` SKU-flag methods are deleted so no second path exists. Flags are snapshots: a later declaration never changes an order already placed.
2. **The product flag is a floor.** `supplier_sku.requires_cold_chain` stays the single effective value every reader uses. A SKU of a chilled product is raised to chilled when created, V71 backfills existing ones, and neither creating nor declaring such a SKU as not chilled is possible (422). Nothing writes `canonical_product.requires_cold_chain` yet; an audited admin edit is a later phase, and no categories are seeded.
3. **Declarations supersede.** `SkuHandlingService.declare` (PUT `/supplier-skus/{id}/handling`, reason required) closes the current `supplier_sku_handling_declaration` row, opens the next, and writes a `SKU_HANDLING_DECLARED` audit row with before and after and the reason, under the SKU lock (lock order: SKU, then declaration). The same applies to catch-weight, which moves money (D-128). A save that passes the current values straight back (a rate sheet, a batch variant) does nothing; a different value through the ordinary SKU update is refused with a pointer to the declaration. One current row per SKU is enforced by a generated unique key. Un-declaring with open orders is allowed, with a reason and an audit row, and changes no order already placed; the next subscription order picks up the new value.
4. **Carrier capability is data.** `delivery_provider_cold_chain_capability` (provider, vehicle class, evidence, who verified it, when). No row means not capable. Seeded: only `MOCK_EXPRESS` and `MOCK_SAVER`, for local and test use, and their evidence text says so. No real carrier is seeded: the repo holds no temperature-control evidence for any of them, and Shadowfax, Porter, Blowhorn, Shiprocket, LoadShare, Delhivery and Xpressbees decline every quote under D-121 anyway. Revoking a mock's capability is how the failure paths are tested.
5. **The quote gate** (`ColdChainCarrierGate`). A chilled consignment is asked only of a carrier with a capability row, in the smallest verified vehicle class that takes the weight; carriers without one are recorded as unserviceable and never called. A quote qualifies only if it states a vehicle and that class is verified for that carrier; an unstated vehicle is no longer inherited from the request. `usableQuotes` checks again, so a capability revoked after quoting cannot be booked on a reassignment. Ordinary consignments are unchanged. `VehicleType.canCarryColdChain` is removed; `fromWeight(w, true)` remains only as a size hint.
6. **No carrier qualifies.** At checkout the fee quote throws 422 `DELIVERY_UNAVAILABLE` ("choose pickup, or ask the supplier to deliver"), with no rate-card fallback for chilled goods, no quote saved, no order. Pickup and supplier delivery for chilled goods stay allowed (the supplier carries it and bears the risk). A fee quote records `cold_chain`, and one priced for ordinary goods cannot be spent on an order that has since become chilled (`PRICE_CHANGED`). At dispatch the delivery fails with `NO_COLD_CHAIN_CARRIER`, nothing booked or charged. A delivery already booked keeps its booking; only reassignment re-applies the gate.
7. **Also fixed.** `DeliveryWaterfallService.cascadeUnassigned` and `forceEscalate` were `@Transactional` and caught an exception from `reassign` inside the same transaction, which marks it rollback-only: the commit threw and the audit row was lost. They are no longer transactional, so the audit row commits on its own. (No test plants that failure directly, so this change is reasoned, not mutation-checked.)

**Not done.** Which real carriers, if any, offer temperature control is a fact to be supplied with evidence, one `INSERT` per carrier and vehicle class; nothing else changes. A store-level declaration of its own cold-chain fleet for supplier delivery. An admin edit of the product flag.

## D-135 — A simultaneous duplicate order returns the first order, not a 500

Verified before changing: `IntentOrderCreator.create` checked for an existing link with no lock, so two calls for one intent both passed it and both built an order. The link insert takes a shared lock on the intent row (foreign key) and the later status update needs an exclusive one, so they deadlocked, and the handler had no mapping for a deadlock, so the loser surfaced as a 500. `IntentFlowIT$Concurrency` only asserted that one order existed, "whatever each call reported", so it could not see this.

1. **The intent is locked first** (`IntentRepository.lockById`, `PESSIMISTIC_WRITE`) as the first statement of `create`. The second creation waits for the first to commit, then reads its link and returns its order through the existing "already ordered" path (D-102), which also hands back the checkout the first caller may not have seen. The lock is taken before any consistent read, so what the check reads is the state after the first commit. Lock order is intent, then order, then payment, matching `OrderReleaseService`.
2. **A lock failure that still happens is a 409** (`CONCURRENT_MODIFICATION`, WARN), not a 500: the database rolled the request back and nothing was written, so a retry is safe.
3. **The idempotency fingerprint now includes the delivery mode, quote reference, slot and scheduled date.** The same key with a different delivery used to return the first order silently; it is now `IDEMPOTENCY_KEY_REUSE`.
4. **Tests:** `duplicateOrderCreation` races five times with different keys and asserts both callers succeed with the same order id, and one order, one link, one payment and an ORDERED intent. Mutation-checked: without the lock round 1 fails. `keyReusedWithADifferentDeliveryIsRefused` is mutation-checked against the fingerprint.

## D-136 — The Razorpay checkout is opened after the order commits

Verified before changing: `IntentOrderCreator.create` called the provider while the order transaction held its connection and locks, so a slow gateway stalled the pool, and a provider failure rolled the whole order back.

1. **Order creation records the payment only.** `PaymentService.recordForOrder` writes a CREATED payment inside the order transaction (no provider call). The order and its payment stay atomic.
2. **The checkout opens after commit.** `IntentOrderService.create` (no transaction) calls `OrderFunding.prepareCheckout`, which calls `PaymentService.openCheckout`: the provider call runs with no transaction, then a conditional bulk update (`openCheckoutIfUnopened`, only while CREATED and with no provider order id) stores the provider order id. A concurrent opener cannot overwrite it.
3. **A provider failure leaves an unpaid draft.** The caller gets 422 `PAYMENT_FAILED` "Nothing was charged. Try again."; the order and its CREATED payment remain. Retrying the same order request returns the existing order (D-102) and opens the checkout. No new pay-screen endpoint.
4. **The sweep ends orphans.** `PaymentJobs.reconcileStale` expires a CREATED payment with no provider ids older than 30 minutes and abandons the unfunded order ("Payment was never set up").
5. **Tests** (`PaymentFlowIT$Hardening`): the provider is not called inside the order transaction; a failure leaves a draft and a retry opens it; the sweep ends a never-opened payment. Each mutation-checked (call back inside the transaction; sweep disabled; failure marking the payment FAILED).

## D-137 — One draft per outlet and store, and every edit of a basket takes the draft's lock

Verified before changing: `openDraft` looked for a draft and created one if none was found, with nothing to stop two simultaneous first additions both creating one, so an outlet could hold two drafts for one supplier. Edits, sends and the direct-order preparation read the draft and its lines with plain reads and no lock, so an add racing a send, or a removal of the last line racing an add, could lose a line or delete a draft that had just been refilled.

1. **The database allows one draft.** V72 adds a generated `draft_key` (`outlet:store` while the status is DRAFT, otherwise null) with a unique key, after merging any duplicates already present (nothing is in production): lines move to the oldest draft unless it has that SKU, the rest are dropped.
2. **Creating a draft is its own transaction.** `IntentDraftStore.insertDraft` is `REQUIRES_NEW` (a separate bean, D-021). The loser of a race gets a duplicate-key error that rolls back only that inner transaction, then reads the winner's draft (also in a fresh transaction, which sees the latest commits and takes no range locks), and carries on. The temporary reference is unique per insert; a shared placeholder made concurrent inserts queue on one unique-index entry and deadlock.
3. **Lock order: intent, then its lines.** `loadForWrite` locks the intent (`lockById`); lines are read under lock (`lockByIntentId`, `lockById`, `lockByIntentIdAndSupplierSkuId`). Applies to add, update, remove, send, send-all, clone and `DirectOrderService.prepare`. Send-all locks each draft before reading it and skips one that is no longer a draft. Under REPEATABLE READ a plain read could miss what the lock holder just committed, which is why the reads that matter are locking reads, and why the id of an item or draft is read without loading the entity before the lock.
4. **Emptying a draft is decided under the lock** (a concurrent add finishes first), so a refilled draft is not deleted. The basket read skips a draft with no lines.
5. **Removal is audited.** A removed line leaves no row, so `INTENT_ITEM_REMOVED` (line, SKU, quantity) and `INTENT_DRAFT_DELETED` are written to the audit log.
6. **Tests** (`IntentFlowIT$Concurrency`, five races each): two first additions give one draft with two lines; the same pack added twice is one line; an add against a send loses no line; removing the last line against an add keeps the new line; removal is audited. Mutation-checked: without the intent lock, and without the audit call. The locked emptiness check (item 4) is reasoned, not mutation-checked: swapping it for a plain count did not fail a test, because the interleaving it guards is too narrow for a two-thread race to hit reliably.

**Not done.** The response to an add may omit a line committed by a concurrent add (it is built from this transaction's snapshot); the next read shows it. The mobile cart's own part of this bug (unflushed edits, rapid taps) is fixed in the app.

---

## D-138 — Discovery serviceability: unified policy across storefront, recommendations, and popular suppliers

**2026-10-04 · Settled**

Verified against the code before changing: `StorefrontService.serves` hard-coded 25 km, `RecommendationService.servesOutlet` checked `serviceability.defaultRadiusKm`, `PopularSupplierService.forOutlet` applied no serviceability filtering at all (allowing far stores to consume the limit), and `DiscoveryController` did not scope `outletId` to the caller on discovery endpoints (`/search/suppliers`, `/search/skus`, `/supplier-skus/{skuId}`, `/supplier-stores/{storeId}/catalog`).

1. **Unified `ServiceabilityPolicy`.** One shared component used across `StorefrontService`, `RecommendationService`, and `PopularSupplierService`. Precedence order:
   - If the store has a declared pincode list (`delivery_pincodes`), that list wins exclusively (the outlet pincode must match).
   - Otherwise, if the store defines its own radius (`delivery_radius_km`), that radius applies.
   - Otherwise, fall back to the configured default radius (`costonomy.mp.discovery.serviceability.default-radius-km`, defaulting to 25 km).
   - Missing coordinates on either side (`distanceKm == null`) means serviceable (suppliers are not hidden due to missing coordinate data). No opening-hours filter is applied.
2. **Popular suppliers.** Serviceability filtering is evaluated *before* applying the result limit, so distant suppliers do not consume slots for serviceable candidates. The limit is clamped to at most 100 (`Math.min(Math.max(1, limit), 100)`).
3. **Credit reach parameter.** `GET /api/v1/search/suppliers` supports `reach=all` to bypass serviceability filtering specifically for credit request flows.
4. **Scoped `outletId` authorization.** Discovery endpoints (`/search/suppliers`, `/search/skus`, `/supplier-skus/{skuId}`, `/supplier-stores/{storeId}/catalog`) enforce `accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "Outlet")`. When the caller cannot access the outlet, `NotFoundException` (404) is thrown instead of 403.
5. **Tests.** `ServiceabilityPolicyTest` unit tests (pincode precedence, store radius, default radius fallback, null coordinates). `StorefrontIT` integration tests: unscoped outlet returns 404, `reach=all` bypasses serviceability, popular suppliers exclude distant stores without consuming limits, and limit clamps to at most 100.

---

## D-139 — Supplier directory: nearest first with offset pagination

**2026-10-04 · Settled**

Verified against the code before changing: `StorefrontService.searchSuppliers` executed a query with `order by o.display_name limit 100`, capping the candidate stores alphabetically before computing distances and sorting by proximity. As a consequence, a nearby supplier whose name began late in the alphabet (e.g. "ZZZ") would be completely missing if there were more than 100 active stores.

1. **Remove SQL name cap.** Removed `order by o.display_name limit 100` from the store selection query in `StorefrontService.searchSuppliers`.
2. **Nearest first sorting.** Stores are ordered nearest first (`Double.compare` on distance, with `null` distances sorted last using `Double.MAX_VALUE`), with store ID (`supplierStoreId`) as deterministic tie-breaker.
3. **Offset pagination.** `searchSuppliers` supports `offset` (default 0) and `limit` (default 50, clamped between 1 and 100).
4. **Pagination metadata.** `DiscoveryDtos.SupplierSearchPage` includes `total` (the count of serviceable suppliers matching filters) and `nextOffset` (`Integer`, null when on the last page). `reach=all` parameter continues to bypass serviceability filtering.
5. **Tests.** Integration test `paginationAndNearestSorting` in `StorefrontIT$Suppliers`: verifies that with 100 "AAA" stores at 2–5 km and one "ZZZ" store at 0.5 km, "ZZZ" is returned first on page 1; disjoint pages cover all stores without repeats; limit is clamped to 100; and `nextOffset` is null on the final page. Mutation-checked by re-introducing the `order by o.display_name limit 100` cap, which caused the test to fail.

## D-140 — A buyer can ask for immediate or a day when sending a request

Before this, the delivery slot was chosen only at order review, after the supplier had answered. The buyer could not say, when sending, whether they wanted the goods now or on a particular day, and the supplier had no way to plan for it.

1. **A preference, not a booking.** `intent.preferred_delivery_date` (V73, nullable `DATE`). Null means immediate. The slot is still chosen and booked when the order is created; the preference only tells the supplier what is wanted and starts the buyer's slot picker on that day.
2. **Day, not slot.** One choice applies to a whole basket send, and slots belong to each supplier, so the choice is a day (today, tomorrow, in two days in the app; the API accepts today to 30 days ahead). `SendRequest` and `SendBasketRequest` take `preferredDeliveryDate`; `IntentResponse` returns it, so the supplier sees it.
3. **Validated on send.** A day before today or more than 30 days ahead is refused (422 `VALIDATION_ERROR`), and nothing in the basket is sent. Days are India's calendar days (`Asia/Kolkata`).
4. **Immediate is the default.** Leaving it out is what every client did before, and means "as soon as the supplier can".
5. **Not done.** Direct orders (`/direct-order`) skip the request, so they carry no preference and the slot is chosen at review as before. The existing `requestedDeliveryTime` (an exact moment, shown to the supplier as "Wanted by") is unchanged and still not sent by the app.
6. **Tests** (`IntentFlowIT$Basket`): immediate by default and the supplier sees both; one day applies to every request in a basket send; past and too-far days are refused and the draft stays a draft. Mutation-checked: not storing the day, and not validating it.

## D-141 — The supplier chooses how a request is delivered when they answer it, and free delivery is stated as free

Verified before changing: the buyer chose the delivery mode at order creation and the fee was charged then; the supplier had no say after answering. The buyer's picker showed the supplier's own fee as `acceptance.deliveryFee ?? 0`, and that field was always null, so a supplier who charged ₹30 was shown to the buyer as "Free" and then charged ₹30. The buyer's order screen hid the delivery line when the fee was zero.

1. **The supplier's answer carries a delivery offer** (`intent_acceptance.delivery_offer`, V74): `SELF_FREE` (they deliver, no charge), `SELF` (they deliver at their own fee) or `COSTONOMY` (Costonomy riders). Pickup is always offered. Offering to deliver (`SELF_FREE`, `SELF`) is the supplier's decision for that request and needs no standing store setting (an order whose answer offered it is not refused or blocked at dispatch by the store's own-delivery switch); what they may charge is capped by the store's own fee (nothing if none is set). `COSTONOMY` needs Costonomy delivery enabled (422 otherwise). Omitted, an answer offers whatever the policy enables, as before.
2. **The buyer can choose only what was offered.** `IntentOrderCreator.requireOffered` runs on preview and create: with `SELF*` offered, Costonomy delivery is refused, and the other way round. Pickup is always allowed.
3. **Free is free.** With `SELF_FREE` the supplier-delivery fee is zero (after the same refusals as any supplier delivery: not enabled, below the minimum order value). `acceptance.deliveryFee` is now set when the offer is made (0 for free, the store's fee for `SELF`) and for direct orders (the store's own fee when it delivers), so the buyer is shown the amount they will be charged. The delivery row's fee is the order's fee, no longer the policy's (it ignored a waiver).
4. **Riders are requested after Ready, automatically.** Unchanged: `COSTONOMY_DELIVERY` orders are dispatched when the supplier marks Ready for Pickup (`DeliveryDispatchListener`). The "Request Delivery Partner" button remains the manual fallback.
5. **Who pays for riders.** Free applies only to the supplier's own delivery; Costonomy delivery shows its quoted fee and the buyer pays it. **Not changed, for a decision:** the existing free-delivery threshold (`free_delivery_threshold`) still waives the fee on `COSTONOMY_DELIVERY` as well, so above the threshold the courier's cost is absorbed by someone and no charge to the supplier or buyer exists for it.
6. **The charge can be set per request, up to the store's fee.** With `SELF` the answer may carry `deliveryFee`: absent means the store's own fee; lower (zero is free) is the supplier's call for this order; higher than the store's fee is refused (422). That amount is what the buyer is shown and charged (`acceptance.delivery_fee`), not the store's standing fee. A supplier who finds delivery not viable chooses Costonomy riders instead.
7. **Tests** (`IntentFlowIT$DeliveryOffer`): free delivery is shown as free and charged nothing; a lower charge for one order is shown and charged, and a higher one is refused; own delivery at the store's fee is shown and charged; the buyer can choose only what was offered; an offer the store has turned off is refused. Each mutation-checked.

