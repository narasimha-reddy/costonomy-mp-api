# A/B Testing & Rapid Feature Experimentation Platform

World-class, enterprise-grade experimentation platform built into the Costonomy backend for rapid feature experimentation, canary rollouts, and multi-variant A/B testing.

---

## 1. Core Capabilities & Architecture

| Capability | Implementation | Benefit |
|---|---|---|
| **Deterministic Bucketing** | 32-bit MurmurHash3 salted hashing | Stateless, sub-millisecond assignment; 100% reproducible; zero cross-experiment bias |
| **Two-Stage Bucketing** | Independent traffic hash & variant hash | Ramping traffic (e.g. 10% → 50%) does **not** re-shuffle variants for already exposed users |
| **Layer Mutual Exclusion** | Deterministic layer-level bucketing | Guarantees subjects enter at most one active experiment per layer (e.g., `checkout_flow`) |
| **Bounded Multi-Instance Cache** | Scheduled atomic swap + tombstoning | Sub-millisecond reads (< 10 µs); propagates creates, updates, status changes, and deletes across instances within 30s |
| **Strict Activation Validation** | Pre-activation configuration validator | Enforces valid JSON payloads, valid rules, non-negative weights, exactly one control, and positive weight |
| **Dynamic Config Payloads** | Typed JSON payloads parsed via Jackson | Ship parameter changes (pricing, timeouts, weights) on the fly without server redeploys |
| **Audience Targeting** | JSON rule engine supporting `IN`, `NOT_IN`, `EQUALS`, `GT/GTE`, `LT/LTE` | Target specific user roles, cities/pincodes, versions, or order values |
| **Subject Overrides** | Whitelist overrides | Instant assignment of QA testers, internal staff, and test outlets to specific variants |
| **Async Exposure Logging** | Spring event bus + sliding-window deduplication | Auditable impressions without slowing down API requests or thrashing the database |
| **Unambiguous Conversion Attribution** | CTE window deduplication + explicit linkage | Ties conversions to explicit exposure IDs or latest valid enrolled exposure; prevents repeated exposures from multiplying counts |
| **Rollout Fallback Isolation** | Explicit exclusion of `FALLBACK_OUT_OF_TRAFFIC` | Preserves audit logs for diagnostics while ensuring un-enrolled fallbacks never inflate conversion rate denominators |
| **Platform Authorization** | `AccessControlService` (`CONFIG_VIEW` / `CONFIG_MANAGE`) | Standardized platform security gating on all `/api/v1/admin/experiments/**` endpoints |
| **Client Data Hygiene** | Sanitized `ClientExperimentDto` | Prevents exposing salts, override whitelists, or internal attribute snapshots to client endpoints |

---

## 2. Mathematical Foundation: Salted Consistent Bucketing

To ensure an experiment never suffers from selection bias or correlated interference with other experiments:

### 2.1 Unlayered Independent Experiments

$$\text{trafficBucket} = \text{MurmurHash3}(\text{subjectId} + ":" + \text{salt} + ":\text{traffic}) \pmod{10000}$$

$$\text{variantBucket} = \text{MurmurHash3}(\text{subjectId} + ":" + \text{salt} + ":\text{variant}) \pmod{\sum \text{weights}}$$

1. **Traffic Gating**: If $\text{trafficBucket} \ge \text{trafficAllocationPct} \times 100$, the subject is not enrolled in the experiment and cleanly falls back to the `control` variant with reason `FALLBACK_OUT_OF_TRAFFIC`.
2. **Variant Allocation**: Cumulative weight slicing deterministically maps the variant bucket to the corresponding treatment.
3. **Orthogonality**: Each experiment has its own unique UUID `salt`. A subject in Treatment A for Experiment 1 has an independent, unbiased probability distribution for Experiment 2.

### 2.2 Layer-Level Mutual Exclusion

When experiments share a `layer` (e.g. `checkout_flow` or `pricing_engine`), they compete for non-overlapping slices of the layer's 10,000 buckets:

$$\text{layerBucket} = \text{MurmurHash3}(\text{subjectId} + ":\text{layer}:" + \text{layer}) \pmod{10000}$$

Active experiments in the layer are sorted deterministically by `experimentKey`. Each experiment receives an allocation slice $[start, end)$ corresponding to its `trafficAllocationPct * 100`.
- If $\text{layerBucket}$ falls within an experiment's range, the subject is assigned to that experiment.
- If it falls within another active experiment's range, the subject receives `FALLBACK_LAYER_EXCLUDED` and the control variant.
- A subject can **never** enter multiple active experiments in the same layer.
- Total traffic across active experiments in a layer is strictly validated upon activation and cannot exceed 100%.

---

## 3. Database Schema (Flyway V90 & V92)

The implementation is **purely additive** across V90 and V92 migrations:

```
┌─────────────────────────────────┐          ┌──────────────────────────────────┐
│           experiment            │ 1      * │        experiment_variant        │
│─────────────────────────────────│──────────│──────────────────────────────────│
│ id (PK)                         │          │ id (PK)                          │
│ experiment_key (UK)             │          │ experiment_id (FK)               │
│ name, description               │          │ variant_key, name, description   │
│ status (DRAFT/ACTIVE/PAUSED/..) │          │ weight (traffic split weight)    │
│ subject_type (USER/RESTAURANT)  │          │ is_control (TINYINT)             │
│ traffic_allocation_pct (0-100)  │          │ payload_json (JSON dynamic params)│
│ salt (UUID)                     │          └──────────────────────────────────┘
│ layer (VARCHAR - mutual excl.)  │
│ targeting_rules (JSON)          │          ┌──────────────────────────────────┐
│ overrides (JSON)                │          │       experiment_exposure        │
│ start_time, end_time            │          │──────────────────────────────────│
└─────────────────────────────────┘          │ id (PK), experiment_key          │
                                             │ variant_key, subject_id          │
┌─────────────────────────────────┐          │ evaluation_reason, exposed_at    │
│     experiment_metric_event     │          │ attributes_snapshot (JSON)       │
│─────────────────────────────────│          └──────────────────────────────────┘
│ id (PK), subject_id             │                          ▲
│ metric_name, numeric_value      │                          │ (FK exposure_id)
│ properties (JSON), recorded_at  │                          │
│ experiment_key, variant_key     │──────────────────────────┘
│ exposure_id (FK)                │
└─────────────────────────────────┘
```

---

## 4. Multi-Instance Cache Consistency & Invalidation

The platform provides sub-millisecond evaluation latency (< 10 µs) while guaranteeing cross-instance consistency without requiring external cache infrastructure:
- **Local In-Memory Cache**: Cached in an `AtomicReference<Map<String, Experiment>>` swapped atomically.
- **Bounded Synchronization**: All instances run a background scheduled sync (`@Scheduled(fixedDelayString = "${costonomy.mp.experiment.cache-sync-interval:PT30S}")`) that reloads active experiment definitions from the database every 30 seconds.
- **Immediate Local Invalidation**: Any mutate operation (`create`, `update`, `updateStatus`, `delete`, `addOverride`) immediately updates the local instance's atomic snapshot.
- **Tombstoning of Deleted Entities**: When an experiment is deleted, it is removed from the database and immediately tombstoned in memory. Any attempt to query or evaluate the deleted experiment returns empty/fallback, preventing resurrecting from stale state.

---

## 5. Unambiguous Conversion Attribution Model

Conversion metrics are attributed to variants using an unambiguous single-credit model:

1. **Explicit Attribution**:
   Clients and backend services can pass `exposureId` or (`experimentKey` + `variantKey`) when tracking a metric. The conversion event directly and unambiguously links to that variant.
2. **First/Latest Enrolled Exposure Attribution**:
   When tracking a generic subject metric (e.g. `order_completed`), the conversion is attributed to the variant from the subject's latest valid exposure (`evaluation_reason IN ('BUCKETED', 'OVERRIDE')`) prior to the event (`recorded_at >= exposed_at`).
3. **Deduplication Against Repeated Exposures**:
   A Common Table Expression (CTE) with `ROW_NUMBER() OVER (PARTITION BY m.id ORDER BY exp.exposed_at DESC)` ensures each metric event is credited **at most once**, regardless of how many times the subject was exposed.
4. **Exclusion of Rollout Fallbacks**:
   `FALLBACK_OUT_OF_TRAFFIC`, `FALLBACK_NOT_ACTIVE`, `FALLBACK_NOT_TARGETED`, and `FALLBACK_LAYER_EXCLUDED` are excluded from both exposure totals and conversion counts. Conversion rate denominators strictly reflect eligible enrolled subjects.

---

## 6. Pre-Activation Validation Rules

Before an experiment can transition to `ACTIVE` status (or be saved as `ACTIVE`), the following constraints are strictly enforced:
- **Control Variant**: Exactly one variant must have `isControl = true`.
- **Weights**: All variant weights must be non-negative ($\ge 0$), and total weight must be positive ($> 0$).
- **JSON Payloads**: All variant `payloadJson` values must be valid JSON parseable by Jackson.
- **Targeting Rules & Overrides**: `targetingRules` and `overrides` must be valid, well-formed JSON structures.
- **Traffic Allocation**: `trafficAllocationPct` must be between 0 and 100%.
- **Layer Traffic**: If a `layer` is configured, the sum of `trafficAllocationPct` across all active experiments in that layer must not exceed 100%.

Violations reject the request with `BusinessException(ErrorCode.INVALID_EXPERIMENT_CONFIG)` (HTTP 422).

---

## 7. Security & Authorization

Access to `/api/v1/admin/experiments/**` is gated using the project's standard `AccessControlService` and `ActorContext.requireUserId()` at `PLATFORM` scope:
- **`CONFIG_VIEW`**: Required for all read, search, diagnostic, inspection, and analytics endpoints (`GET /api/v1/admin/experiments/**`, `POST /inspect`, `GET /dashboard`).
- **`CONFIG_MANAGE`**: Required for all mutation endpoints (`POST /api/v1/admin/experiments`, `PUT /**`, `DELETE /**`, `POST /overrides`, `POST /cache/reload`).
- **Unauthenticated callers**: Rejected with HTTP 401 (`UNAUTHENTICATED`).
- **Unauthorized users**: Rejected with HTTP 403 (`FORBIDDEN`).

Client endpoints under `/api/v1/experiments/**` return `ClientExperimentDto` which deliberately omits internal `salt`, `overrides`, and attribute snapshots.

---

## 8. REST API Reference

### Admin Endpoints (`/api/v1/admin/experiments`)

- `GET /api/v1/admin/experiments`: View all experiments (with optional `?status=ACTIVE`, `?subjectType=RESTAURANT`, and `?search=keyword` filtering). Requires `CONFIG_VIEW`.
- `GET /api/v1/admin/experiments/live`: View live in-memory diagnostic snapshot, active running experiment count, cache status, and JVM metrics. Requires `CONFIG_VIEW`.
- `GET /api/v1/admin/experiments/dashboard`: Interactive visual HTML dashboard to view and monitor all experiments in real time in any web browser. Requires `CONFIG_VIEW`.
- `GET /api/v1/admin/experiments/{key}`: View complete experiment configuration, variants, weights, targeting rules, and overrides. Requires `CONFIG_VIEW`.
- `GET /api/v1/admin/experiments/{key}/exposures`: View recent live impression/exposure records for audit and debugging. Requires `CONFIG_VIEW`.
- `POST /api/v1/admin/experiments/{key}/inspect`: Transparent dry-run evaluation preview simulating how any subject is evaluated right now. Requires `CONFIG_VIEW`.
- `POST /api/v1/admin/experiments`: Create an experiment with variants, rollout %, targeting rules. Requires `CONFIG_MANAGE`.
- `PUT /api/v1/admin/experiments/{key}`: Update experiment properties, traffic split, variants. Requires `CONFIG_MANAGE`.
- `PUT /api/v1/admin/experiments/{key}/status`: Transition status (`DRAFT` → `ACTIVE` → `PAUSED` → `ARCHIVED`). Requires `CONFIG_MANAGE`.
- `POST /api/v1/admin/experiments/{key}/overrides`: Whitelist specific subjects into a variant. Requires `CONFIG_MANAGE`.
- `DELETE /api/v1/admin/experiments/{key}`: Delete an experiment and tombstone in cache. Requires `CONFIG_MANAGE`.
- `GET /api/v1/admin/experiments/{key}/analytics?metricName=order_completed`: View impressions, unique subjects, conversion counts, conversion rates (%), and average values by variant. Requires `CONFIG_VIEW`.
- `POST /api/v1/admin/experiments/cache/reload`: Invalidate and refresh in-memory cache across DB. Requires `CONFIG_MANAGE`.

### Client Endpoints (`/api/v1/experiments`)

- `GET /api/v1/experiments/active`: View all active experiments and variants (sanitized, no salts/overrides).
- `POST /api/v1/experiments/evaluate`: Evaluate an experiment for a client app session.
- `POST /api/v1/experiments/evaluate-batch`: Batch evaluate multiple experiment keys at application startup.
- `POST /api/v1/experiments/exposures`: Report an impression when a UI component renders on screen.
- `POST /api/v1/experiments/metrics`: Report conversion or performance metrics with optional explicit attribution.
