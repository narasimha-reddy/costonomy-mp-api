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
