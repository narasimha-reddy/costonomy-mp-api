# costonomy-mp-api

Spring Boot backend for **Mandi**, Costonomy's restaurant procurement
marketplace. Modular monolith on MySQL `costonomy_mp`.

## Where to look

| | |
|---|---|
| `CLAUDE.md` | how to work in this repo, and the rules that are not negotiable |
| `docs/specs/` | the full specification set |
| `docs/DECISIONS.md` | decisions the specs don't settle, plus **open questions** |

Mobile client: `costonomy-mp-mobile`.

## Getting started

```bash
mvn spring-boot:run       # local profile, all providers mocked
mvn test                  # unit tests — no Docker needed
mvn verify                # + integration and migration tests (needs Docker)
```

Local development needs no external provider and no MySQL install: OTP, payment
and delivery all run on mocks, and integration tests bring their own MySQL 8 via
Testcontainers. You do need Docker running for `mvn verify`.

Once running: `http://localhost:8080/costonomy-mp-api/swagger-ui.html`

## Status

**Phase 1 — backend foundation.** Project skeleton, API envelope and error
catalogue, request correlation, security wiring, and the platform infrastructure
every later phase depends on: idempotency, audit, outbox, config and job locks.
Schema migrations `V1` (identity/access) and `V2` (platform).

No domain endpoints yet. Next is Phase 3, authentication — see
`docs/specs/00-README.md` §8 for the full sequence.
