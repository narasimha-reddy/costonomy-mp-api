# Onboarding — costonomy-mp-api

For a developer joining Mandi and working through Claude Code. Read this once,
then let `CLAUDE.md` do the rest.

Last updated **27 September 2026**, from the Razorpay branches
(`feat/razorpay-1-adapter` → `-2-hardening` → `-3-small-fixes`), which build on
`feat/edit-open-request-quantities`.

---

## 0. How context works here

Claude Code does not inherit anyone's previous session. On startup it reads
`CLAUDE.md` and whatever you point it at — nothing else. Everything this project
knows is therefore **committed to this repo**, and the corollary is the one habit
that matters:

> When you settle something the next person would otherwise have to rediscover,
> write it into a file. A decision explained in a chat window is a decision lost.

`docs/DECISIONS.md` is where non-obvious choices go, numbered, with the reasoning
and what was rejected. There are 97 of them. They are not documentation of the
code — they are the arguments you do not have to have again.

**Read in this order.** Claude picks up `CLAUDE.md` on its own; the rest you ask
for by name when the task touches them.

| File | What it gives you |
|---|---|
| `CLAUDE.md` | how to work here, and the rules that are not negotiable |
| `docs/specs/00-README.md` | the map of the specification set |
| `docs/specs/02-domain-model-database.md` | domain model and DDL |
| `docs/specs/03-state-machines-permissions.md` | states, transitions, authorization |
| `docs/specs/04-api-specification.md` | REST contracts and the response envelope |
| `docs/DECISIONS.md` | D-001…D-097 — decisions the specs don't settle |
| `docs/specs/IMPLEMENTATION_TRACEABILITY.md` | requirement → evidence. Keep it current |

The sibling repo `costonomy-mp-mobile` carries a copy of `docs/specs/`. They are
meant to be identical; if they have drifted, say so rather than picking one.

---

## 1. Get it running

**JDK 17 is mandatory, not a preference.** On a newer JDK Lombok stops processing
*silently* and the build fails with several hundred "cannot find symbol" errors
on generated getters, none of which point at the real cause. If you see that,
check `mvn -v` before you change a line of code.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

Put that in your shell profile. You will forget otherwise.

### Prerequisites

- JDK 17 (Temurin), Maven 3.9+
- Docker — needed for the local database *and* for Testcontainers in the test suite
- Python 3 for the seed script

### Database

MySQL 8 in Docker on port **13306** (not 3306 — the local profile expects 13306
so it can coexist with anything already on the default port):

```bash
docker run -d --name jobs-mysql -p 13306:3306 \
  -e MYSQL_ROOT_PASSWORD=root mysql:8.0

docker exec jobs-mysql mysql -uroot -proot \
  -e "CREATE DATABASE IF NOT EXISTS costonomy_mp \
      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

If you also work on `costonomy-jobs`, you already have this container — Mandi
shares it as a separate schema rather than running a second server.

### Run it

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway applies **V1–V36** on startup and the data survives restarts.

- API: <http://localhost:7070/costonomy-mp-api>
- Swagger: `/swagger-ui.html` · OpenAPI: `/api-docs`
- Every OTP is `123456` locally — **but only if your gitignored
  `application-local.properties` says so**: `costonomy.mp.otp.mock-code=123456`.
  The committed config sets it for tests alone, so a fresh local file sends real
  random codes and every sign-in fails with `OTP_INVALID`.
- Every provider is a mock, unless you point payments at Razorpay's test mode —
  `docs/RAZORPAY.md`

### Seed it

A freshly migrated database has the canonical catalog but nobody selling
anything, so every screen in the app looks broken. Fix that:

```bash
python3 tools/seed-local.py
```

It walks the real API for everything a supplier can legitimately do — the one
exception, supplier activation, is a direct `UPDATE` and is marked in the script.

### Accounts

| Phone | Who |
|---|---|
| `+919876500004` | Spice Garden, Indiranagar — restaurant |
| `+919876500007` | Tandoor House, Koramangala — restaurant |
| `+919876511001` | Sri Balaji — supplier |
| `+919876511002` | Metro Fresh Supplies — supplier |
| `+919876511003` | Deccan Wholesale — supplier |
| `+919876599001` | platform operator |

OTP `123456` for all of them. The **resend cooldown and attempt limits are real**
on the local profile, deliberately — those paths are part of the product. Expect
a genuine 429 if you re-request a code for the same number inside a minute.

---

## 2. The development loop

`spring-boot-devtools` is a `provided` dependency, so it is on the classpath when
you run from source and in no artifact. A recompile restarts the app in about
five seconds instead of ten, and the process keeps its port — so a browser
session survives the restart, which is most of the saving.

It is **off by default**. Turn it on in your own gitignored
`application-local.properties`:

```properties
spring.devtools.restart.enabled=true
spring.devtools.livereload.enabled=false
```

Then pick changes up from a second terminal — devtools watches `target/classes`,
not the source tree:

```bash
mvn -o -q compile
```

> **`mvn compile` can lie.** It sometimes skips a change, and an IDE build can
> clobber `target/classes` underneath you. If the running app's behaviour
> disagrees with the source you are reading, `mvn -o clean compile` and restart
> before you debug anything else. This has cost real hours.

### Tests

Run the integration test you are changing, not all of them. A full `mvn verify`
is about five minutes; one class is about fifty seconds, of which forty is the
MySQL container and the Spring context:

```bash
mvn -o verify -Dit.test=StorefrontIT
```

Run the whole suite before you open a PR, not between edits.

**No Docker Desktop? Colima works** (open source, no admin rights), but
Testcontainers has to be told where it is:

```bash
colima start
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Its VM clock runs a fraction of a second ahead of the Mac's, so a test that stamps
a row with MySQL's `now()` and then compares it with `Instant.now()` is a coin
toss. `SettlementFlowIT` had one; write new ones against a single clock. Testcontainers
reuse is deliberately not enabled — `CLAUDE.md` explains why, and the short
version is that it would make `MigrationIT` pass while checking nothing.

---

## 3. How we work

### Branches

One branch per developer per piece of work, cut from `main`:

```bash
git checkout main && git pull
git checkout -b feat/<short-name>
```

`main` is the integration point. Do not cut a branch from someone else's branch —
if you need their work, wait for it to land or say so out loud.

### Pull requests

**Open a PR even for small things, because the PR is what runs CI.**
`.github/workflows/ci.yml` runs on pull requests and on pushes to `main`, and on
nothing else. A long-lived feature branch with no PR gets no CI at all — which is
exactly how 209 integration tests were once broken for a day without anyone
knowing.

There is no CI in `costonomy-mp-mobile` yet. Until there is, run
`npm run typecheck && npm run lint && npm test` yourself before pushing.

### Definition of done

`docs/specs/00-README.md` §9 is the bar, and it is not the happy path: migration,
domain, service, API, authorization, mock provider, idempotency, concurrency,
unit + integration tests, edge cases, traceability entry. A requirement without
evidence in `IMPLEMENTATION_TRACEABILITY.md` is not complete.

Two rules worth repeating because they are the ones people skip:

- **Assert the side effect, not the rejection.** Every one of the seven
  transaction bugs listed in `CLAUDE.md` passed a test that checked the error
  response and failed a test that checked whether the thing the error described
  had actually happened.
- **A migration is never edited once applied.** Add the next one. `V34` exists
  solely because `V33` shipped without a column.

---

## 4. Working with Claude Code in this repo

**Give it a scope, not a subject.** "Work on credit" produces sprawl. What works
is: the module, the spec sections that bind it, the D-numbers it must not
re-litigate, and what done means. One paragraph.

**Point it at the decision, not just the code.** If a change looks wrong to you,
there is a decent chance `docs/DECISIONS.md` already explains why it is that way.
Ask for the D-number before asking for the rewrite.

**Do not let it re-litigate these.** They are settled and the reasoning is
written down: D-002 (Flyway owns the schema), D-009 (enum columns are VARCHAR),
D-010 (a payment is per supplier order), D-011 (the `{data, error, meta}`
envelope), D-020 (a supplier never sees an unfunded order), D-021 (a duplicate
insert goes in its own bean and throws), D-044 (an event name is owned by the
enum that raises it), D-046 (read and write separately permissioned).

**Verify, do not assert.** The habit that has caught the most here is checking
the claim against the running system — `curl` the endpoint, query the database,
open the screen — rather than reasoning about what the code should do. Ask for
evidence in the answer.

---

## 5. State of play — 27 September 2026

Phases 1 and 3–17 of `docs/specs/00-README.md` §8 are complete, and the mobile
app is built across both roles.

### Latest: Razorpay payments (D-098, D-099)

Five stacked PRs. **One migration**, `V37` (`refund.attempts`); otherwise — the payment tables from `V11` are used as
they were.

| PR | What it changed |
|---|---|
| `feat/razorpay-1-adapter` | The Razorpay adapter now matches Razorpay's documented API: manual capture, the webhook event-id header, the refund idempotency header, lookup by order. **Confirm checks the payment belongs to this order** — before, any payment funded any order. `docs/RAZORPAY.md` covers running against test mode (D-098) |
| `feat/razorpay-2-hardening` | No provider call holds a database connection; every payment state change locks the payment row first, and order release locks the order; 8 scheduler threads instead of 1; the sweep backs off; a failed capture stays queued instead of being stranded (D-099) |
| `feat/razorpay-3-small-fixes` | The seed script runs on a fresh database again; the wallet top-up is refused unless payments run on the mock; a spent delivery quote says "already used" |
| `feat/razorpay-7-payment-tracing` | A payment's story reads from the logs by its id: every job run has a correlation id (in logs and audit), payment and Razorpay ids ride on every line in scope, each state change and each Razorpay call is one INFO line (D-100) |
| `feat/razorpay-8-review-fixes` | Fixes from three independent reviews: a declined attempt can no longer fail a paid order; the sweep cannot be starved; pending refunds wait for the provider; refunds cannot be over-promised and go to `NEEDS_REVIEW` instead of retrying for ever; a production profile refuses mock providers (D-101) |

What you will notice:

- `POST /outlets/{id}/wallet/top-up` returns **403** unless payments run on the
  mock (`costonomy.mp.providers.payment`, set by `PAYMENT_PROVIDER`). It credited
  money that didn't exist.
- New setting `SCHEDULER_THREADS` (default 8).
- Refund keys sent to the provider are `mandi-refund-{id}` (Razorpay needs ten
  characters).
- **Searching the logs:** `payment=160` finds one payment's whole sequence; a job
  run's lines share `job-<method>-<id>`, and so do the audit rows it wrote. The
  log pattern appends the ids, so custom log configs need `%X{trace}` too.
- `costonomy-mp-mobile/tools/razorpay-e2e` pays real test-mode orders end to end,
  28 cases, mostly failures. It needs this API on Razorpay test keys.

Tests after these PRs: 329 unit, 335 integration.

### Earlier, on `feat/edit-open-request-quantities`

| Migration | What it added |
|---|---|
| `V31__wallet.sql` | a prepaid balance an outlet orders against, funding through `OrderFundingPort` like card and credit |
| `V32__store_direct_orders.sql` | a store flag letting a restaurant order without a request first (D-094) |
| `V33`, `V34` — chat | one thread per outlet/store pair, either side may open it, four permissions (D-095) |
| `V35__sku_detail.sql` | description, dimensions, images, video and reviews on a SKU (D-096) |
| `V36__store_contact.sql` | a contact name and number on every store (D-097) |

### Open, and genuinely undecided

These are live questions, not omissions. Do not close one silently.

1. **Chat has no realtime channel.** Events are on the outbox ready for one, but
   both chat screens poll — 8s in a thread, 20s in the inbox. Wiring it to the
   existing realtime projection (D-031) is the obvious next step.
2. **Supplier and popular lists do not filter by serviceability.** Product
   comparison does. So a restaurant can be shown a supplier who cannot deliver to
   them. Whether that is a bug or deliberate reach is undecided.
3. **`V36` deliberately has no `NOT NULL`.** Only one of seven existing stores had
   anything to backfill from. D-089 keeps a backfill separate from the constraint
   that depends on it; the constraint still needs to be added once the data is
   clean.
4. **Two orphaned credit-funded orders** survived a clear of seeded credit data:
   `MP-260919-000009` (CONFIRMED) and `MP-260920-000002` (PREPARING). They
   reference agreements that no longer exist. Decide whether to delete them.
5. **OPEN-005 in `DECISIONS.md`** — the delivery fee is never charged to the
   restaurant, because it is only known after the payment is authorised. Read it
   before touching the payment flow.
6. **Order creation still calls Razorpay inside its transaction.**
   `IntentOrderCreator.create` keeps the order and its payment atomic, so the
   provider call holds a connection there — the one place D-099 left alone.
   Changing it changes the order flow.
7. **A truly simultaneous duplicate order gets a 500.** Exactly one order is
   created, as `IntentFlowIT$Concurrency` requires, but the losing call surfaces
   the lock error rather than a clean conflict. The app no longer sends one
   (mobile, D-099).
8. **Not yet tested:** UPI (not offered on the test account's checkout), native
   checkout on a phone (needs a dev build), and a webhook actually delivered by
   Razorpay — the suite sends correctly signed ones instead.
9. **Paying suppliers is still manual.** An operator marks a settlement paid with a
   reference typed in; nothing moves money to a supplier. A design with three
   options (Route, Payouts, a wallet with virtual accounts) is with the team; it
   has a legal question to answer first.
10. **The supplier directory lists the first 100 suppliers by name, then sorts by
    distance** — so the nearest can be missing where there are more than 100
    (D-101). A discovery fix, not a payments one.

### Traps that have already bitten

`CreateSupplierRequest` and `CreateStoreRequest` now require a contact name and
number (D-097). Anything that creates a supplier through the API must send them —
that includes `tools/seed-local.py` and every integration-test fixture. When you
add a required field, grep the test sources and the seed script in the same
change, or you will break 209 tests and a new developer's first afternoon.

**Never call a provider inside a transaction, and never change a payment's state
except through `PaymentService.applyProviderState`.** It takes the row lock
before deciding anything. Writing the status anywhere else brings back the
deadlock D-099 fixed: a confirm and a webhook for the same payment, a 500 for a
customer who had paid.
