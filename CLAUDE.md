# costonomy-mp-api

Spring Boot backend for **Mandi** — Costonomy's restaurant procurement
marketplace. Modular monolith, MySQL `costonomy_mp`.

Mobile client lives in `costonomy-mp-mobile` (sibling repo).

> **Status: Phases 1 and 3 complete** — foundation plus authentication. Next is
> Phase 4, organisations and authorization. Build sequence:
> `docs/specs/00-README.md` §8.

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

**A side effect that must survive a thrown exception needs its own transaction.**
This is the same proxy rule as above, but it bites in a way that looks correct
from the outside, and it has already produced two real security bugs here:

- OTP attempt counting incremented inside the transaction that the
  `OTP_INVALID` throw then rolled back. The counter stayed at zero, so the
  attempt limit was decorative and a six-digit code was open to exhaustive
  guessing. Now in `OtpAttemptStore` (`REQUIRES_NEW`, atomic SQL increment).
- Refresh-token replay detection revoked every session and *then* threw. The
  throw rolled the revocation back, so the replay was rejected while the
  compromised session stayed live for another thirty days. Now in
  `RefreshTokenStore`.

Both were caught by integration tests that assert the *side effect*, not just the
rejection — `attemptsAreLimited` checks the correct code stops working, and
`replayRevokesAllSessions` checks the **other** token dies. Write that kind of
assertion when you add a security control here.

## Rules that are not negotiable

**The backend is the authority.** Never trust a client-supplied price, total, or
state transition. The server calculates price, GST, delivery, total, commission
and credit — every time.

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
