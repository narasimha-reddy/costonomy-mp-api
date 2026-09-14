-- V2 — Platform infrastructure: idempotency, audit, outbox, config, job locks.
--
-- Doc 02 §8 sketches these as part of V12 (notifications_audit_analytics), but
-- idempotency and audit are required by the *first* mutating endpoint, not the
-- last. They are foundation, so they move here and the domain migrations shift
-- one number later. See docs/DECISIONS.md D-008.

-- ── Idempotency ──────────────────────────────────────────────────────────
-- Doc 04 §21 and doc 02 §9. The contract:
--   same actor + operation + key + same payload  → the original response, replayed
--   same actor + operation + key + different payload → IDEMPOTENCY_KEY_REUSE
--
-- `request_hash` is what makes the second case detectable: without it, a client
-- that reuses a key for a genuinely different order would silently receive the
-- first order's response and believe the second was placed.
--
-- `state` distinguishes IN_PROGRESS from COMPLETED. A retry that arrives while
-- the first attempt is still running must not start a second attempt — it gets
-- IDEMPOTENT_REQUEST_IN_PROGRESS and retries. This is the case that actually
-- bites in production: a mobile client timing out at 10s and retrying while the
-- payment authorisation is still in flight.
CREATE TABLE idempotency_record (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id        BIGINT       NOT NULL,
    operation       VARCHAR(150) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    state           VARCHAR(32)  NOT NULL,
    response_status INT          NULL,
    response_body   JSON         NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- The unique key is the mechanism, not a safety net: two concurrent retries
    -- both attempt the insert and exactly one wins. The loser reads the winner's
    -- row. This is why the claim must be its own committed transaction.
    CONSTRAINT uk_idempotency_actor_operation_key UNIQUE (actor_id, operation, idempotency_key),
    KEY ix_idempotency_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Audit ────────────────────────────────────────────────────────────────
-- Doc 03 §17 and doc 09 §7. Every privileged or financially material transition
-- writes one row.
--
-- Append-only by policy: nothing updates or deletes from this table. It is the
-- record that answers "who suspended this supplier, and on what grounds" months
-- later, and a mutable audit log answers nothing.
CREATE TABLE audit_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_id        BIGINT       NULL,
    actor_role      VARCHAR(64)  NULL,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       BIGINT       NULL,
    old_state       VARCHAR(64)  NULL,
    new_state       VARCHAR(64)  NULL,
    before_json     JSON         NULL,
    after_json      JSON         NULL,
    reason          VARCHAR(500) NULL,
    request_id      VARCHAR(64)  NULL,
    idempotency_key VARCHAR(200) NULL,
    source          VARCHAR(64)  NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- "Show me everything that happened to supplier order 1234."
    KEY ix_audit_entity (entity_type, entity_id, created_at),
    -- "Show me everything this operator did." Doc 09 §12 requires audit search
    -- by actor, entity, action and date.
    KEY ix_audit_actor (actor_id, created_at),
    KEY ix_audit_action (action, created_at),
    KEY ix_audit_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Outbox ───────────────────────────────────────────────────────────────
-- Doc 02 §10 and doc 08 §3. A domain event is written in the *same transaction*
-- as the state change that produced it, then published asynchronously.
--
-- The alternative — publish inside the transaction — gives you two failure modes
-- that are both wrong: publish then rollback (an event for something that never
-- happened), or commit then fail to publish (a supplier acceptance that never
-- notifies the restaurant). The outbox makes the state change and the intent to
-- publish atomic, and delivery at-least-once. Consumers must be idempotent,
-- which is why `event_id` is unique.
CREATE TABLE outbox_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id        CHAR(36)     NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    payload_version INT          NOT NULL DEFAULT 1,
    payload         JSON         NOT NULL,
    actor_id        BIGINT       NULL,
    correlation_id  VARCHAR(64)  NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NULL,
    last_error      VARCHAR(1000) NULL,
    occurred_at     TIMESTAMP(6) NOT NULL,
    published_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    -- The publisher's only query: pending events whose backoff has elapsed,
    -- oldest first.
    KEY ix_outbox_dispatch (status, next_attempt_at, id),
    KEY ix_outbox_aggregate (aggregate_type, aggregate_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Configuration ────────────────────────────────────────────────────────
-- Doc 09 §10: acceptance SLA, commission, settlement timing, credit rules,
-- ranking weights, throttling and feature flags are all runtime-configurable,
-- versioned, audited and effective-dated where financially relevant.
--
-- `effective_from` + `version` are the financially relevant part: a settlement
-- run months later must reproduce using the commission rate that applied *then*,
-- not the current one (doc 09 §11). Rows are never updated in place — a change
-- inserts a new version.
CREATE TABLE app_config (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    config_key      VARCHAR(120) NOT NULL,
    config_value    VARCHAR(1000) NOT NULL,
    value_type      VARCHAR(32)  NOT NULL,
    config_version  INT          NOT NULL DEFAULT 1,
    description     VARCHAR(500),
    effective_from  TIMESTAMP(6) NOT NULL,
    effective_to    TIMESTAMP(6) NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    updated_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_app_config_key_version UNIQUE (config_key, config_version),
    KEY ix_app_config_lookup (config_key, status, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Scheduled job locks ──────────────────────────────────────────────────
-- ShedLock. The supplier-timeout job, payment reconciliation and settlement
-- generation must run once per interval across the whole deployment, not once
-- per instance — two instances expiring the same supplier order concurrently is
-- exactly the race doc 10 §2 requires us not to have.
-- Column names and types are fixed by ShedLock's JdbcTemplate provider.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
