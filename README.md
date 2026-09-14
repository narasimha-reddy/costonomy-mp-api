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

**Phases 1, 3, 4 and 5 complete.**

- *Foundation* — API envelope, error catalogue, request correlation, security
  wiring, idempotency, audit, outbox, config, job locks. Migrations `V1` and `V2`.
- *Authentication* — OTP login over MSG91 or a mock, JWT access tokens, rotating
  refresh tokens with replay detection, device registration.

- *Organisations and authorization* — restaurants, outlets, supplier
  organisations, stores, GST verification, and the `Role → Permission → Scope`
  model that every endpoint is checked against. Migrations `V3`–`V5`.

- *Catalog* — platform-owned canonical products with aliases, supplier SKUs,
  effective-dated offers, product search, supplier comparison, and CSV/XLSX bulk
  import. Migrations `V6`–`V7`.

Working endpoints: `/auth/*`, `/devices`, `/restaurants`, `/outlets`,
`/suppliers`, `/supplier-stores`, `/categories`, `/brands`, `/products`,
`/search/products`, `/supplier-skus`, `/catalog/imports`, and the operations
verification endpoints under `/admin/suppliers`.

150 tests pass (78 unit, 72 integration). Next is Phase 6, search and
recommendations — see `docs/specs/00-README.md` §8 for the full sequence.
