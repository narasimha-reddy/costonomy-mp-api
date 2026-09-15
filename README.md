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

- *Payments* — a `PaymentProvider` port with Razorpay and mock adapters, a
  payment per supplier order, authorization before any supplier can see the
  order, capture of only the amount accepted, release of the rest, refunds, and
  webhook handling that survives duplicates, out-of-order delivery and a client
  that dies mid-checkout.

- *Supplier credit* — credit requests and the negotiation around them, agreements
  that are specific to one supplier store and one restaurant outlet, an append-only
  ledger, reservation on order and utilization on acceptance, invoices with a due
  date and a grace period, recorded repayments, and the overdue sweep. Credit is
  supplier-funded throughout: Mandi runs the workflow and the ledger and funds
  none of it.

- *Delivery* — a provider-agnostic `DeliveryProvider` port with two configured
  mocks, quoting across every partner, selection by lowest cost meeting the
  required ETA, booking with failover, reassignment that keeps one delivery
  identity, an honest tracking view with a staleness indicator, and supplier own
  delivery as a first-class mode. Provider bidding never reaches a restaurant.

- *Realtime* — a WebSocket channel authenticated by single-use ticket, fed by a
  projection of the outbox, with a polling fallback that reads the same rows by
  the same cursor. Channels are tenant scopes derived from grants, so an event
  reaches exactly the outlet and store it concerns.

- *Receiving, disputes and ratings* — item-level check-in whose numbers must
  reconcile against what the supplier accepted, disputes that run alongside an
  order without ever changing it, and ratings that publish on write and can be
  moderated away. Receiving and rating complete the picture ranking needs: fill
  rate, on-time rate and average rating are now measured rather than absent.

- *Notifications and analytics* — a catalogue mapping domain events to who needs
  telling and in what words, an inbox with server-backed unread state, push and
  SMS with bounded retry, opt-out preferences that critical notifications
  override, and analytics ingest that strips anything named like a secret before
  storing it.

- *Operations APIs* — supplier and order search, a per-order timeline assembled
  from every module, payment and delivery inspection including the provider
  bidding restaurants never see, credit exposure, dispute moderation, audit
  search, versioned configuration and an operational dashboard. Operations holds
  its own permissions, separated into inspection and mutation.

467 tests pass (199 unit, 268 integration). Next is Phase 16, hardening —
see `docs/specs/00-README.md` §8 for the full sequence.

There are no open decisions.
