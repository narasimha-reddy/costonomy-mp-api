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
