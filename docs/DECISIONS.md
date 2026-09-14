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

## OPEN-001 — Is a payment per procurement, or per supplier order?
**Raised 2026-09-14 · Needs a decision before Phase 9 (Payments)**

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

Not blocking the phases before Payments. **Do not let this get settled
implicitly** by whichever migration is written first — `02`'s DDL currently
implies per-supplier-order, and that should be a decision, not an accident.

---

## OPEN-002 — API response envelope
**Raised 2026-09-14 · Needs a decision before the first controller**

`04-api-specification.md` §2 specifies `{ data, error, meta }` with
`error.code` / `error.message` / `error.details`. The existing `costonomy-api`
does something different: an `ERR_NNNN` code catalogue
(`com.costonomy.exception.ErrorCode`) returned in `X-Error-Code` /
`X-Error-Message` headers, with ad-hoc body shapes per handler.

`00-README.md` §4 says to preserve existing Costonomy conventions; `04` §2 gives
an explicit and different contract.

Recommendation: follow `04`'s envelope. It is the newer, explicit contract, the
mobile client is new so nothing depends on the old shape, and the error codes
`04` §22 lists are domain codes (`SUPPLIER_ORDER_EXPIRED`, `PRICE_CHANGED`,
`CREDIT_LIMIT_EXCEEDED`) that a numeric `ERR_NNNN` catalogue cannot express as
readably. Needs confirming before controllers get written, because retrofitting
an envelope across every endpoint is expensive.

---

## OPEN-003 — Requirement lifecycle: is there a `SOURCING` state?
**Raised 2026-09-14 · Low stakes, decide when Requirements are built**

`03-state-machines-permissions.md` §3 has `OPEN → SOURCING → PARTIALLY_FULFILLED
→ FULFILLED`. `Mandi_Engineering_PRD_v1.0.md` §6 omits `SOURCING`.

Recommendation: include it — per D-001 the numbered set wins, and the state is
genuinely useful ("we are looking for suppliers" is distinct from "nothing has
happened yet"). Noted so nobody treats its absence in v1.0 as a contradiction.
