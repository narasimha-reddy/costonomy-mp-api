# costonomy-mp-api

Spring Boot backend for **Mandi** — Costonomy's restaurant procurement
marketplace. Modular monolith, MySQL `costonomy_mp`.

Mobile client lives in `costonomy-mp-mobile` (sibling repo).

> **Status: specification only.** No application code yet. The build sequence is
> in `docs/specs/00-README.md` §8; the next step is Phase 1, backend foundation.

## Read before writing code

1. `docs/specs/00-README.md` — entry point to the specification
2. `docs/specs/02-domain-model-database.md` — domain model and DDL
3. `docs/specs/03-state-machines-permissions.md` — states, transitions, authorization
4. `docs/specs/04-api-specification.md` — REST contracts
5. `docs/DECISIONS.md` — decisions the specs don't settle, and the **open** ones

Where the specs disagree, `docs/DECISIONS.md` D-001 says which wins:
the numbered `00`–`10` docs and PRD v2.2 are authoritative;
`Mandi_Engineering_PRD_v1.0.md` is background only.

**Two open questions block work that is coming soon** — read them before they get
decided by accident:
- **OPEN-001**: is a payment per procurement or per supplier order? Settle before
  Phase 9, and before a migration implies an answer.
- **OPEN-002**: which API error envelope? Settle before the first controller.

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
