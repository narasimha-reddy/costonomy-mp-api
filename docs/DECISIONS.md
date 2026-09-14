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

## OPEN-003 — Requirement lifecycle: is there a `SOURCING` state?
**Raised 2026-09-14 · Low stakes, decide when Requirements are built**

`03-state-machines-permissions.md` §3 has `OPEN → SOURCING → PARTIALLY_FULFILLED
→ FULFILLED`. `Mandi_Engineering_PRD_v1.0.md` §6 omits `SOURCING`.

Recommendation: include it — per D-001 the numbered set wins, and the state is
genuinely useful ("we are looking for suppliers" is distinct from "nothing has
happened yet"). Noted so nobody treats its absence in v1.0 as a contradiction.
