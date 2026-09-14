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

**Phases 1 and 3–8 complete.**

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

- *Search and recommendations* — type-ahead suggestions, supplier search,
  geographic serviceability, and Best Value ranking with explanations. Migration `V8`.

Working endpoints: `/auth/*`, `/devices`, `/restaurants`, `/outlets`,
`/suppliers`, `/supplier-stores`, `/categories`, `/brands`, `/products`,
`/products/{id}/recommendations`, `/search/*`, `/supplier-skus`,
`/catalog/imports`, and the operations verification endpoints under
`/admin/suppliers`.

- *Requirements and procurement* — requirements that survive supplier failure,
  cart, checkout revalidation with explicit price-change confirmation, policy-driven
  approval, and idempotent submission into supplier orders. Migrations `V9`–`V10`.

- *Supplier acceptance* — accept, partial accept with the shortfall returning to
  the requirement, reject with a reason, the timeout sweep, and alternative
  sourcing. Acceptance and timeout resolve to exactly one outcome under real
  concurrency tests.

258 tests pass (127 unit, 131 integration). Next is Phase 9, payments —
see `docs/specs/00-README.md` §8 for the full sequence.

**Known gap:** payment is not yet enforced before an order reaches a supplier.
See `docs/DECISIONS.md` OPEN-004; it closes in Phase 9.
