-- V11 — Payments and refunds.
--
-- Doc 02 §3, doc 03 §6–7, doc 01 §14–15, doc 09 §5.
--
-- One payment per supplier order (D-010). Capture and refund then map one-to-one
-- onto the thing being accepted: a partial acceptance captures that order's
-- accepted value and releases the rest, and a rejection releases the whole of it,
-- without any cross-order allocation.
--
-- **Financial truth is the provider's, reconciled into here.** Doc 01 §14 and
-- guardrail 3: a client callback saying "payment successful" changes nothing. A
-- payment reaches CAPTURED because the provider said so, through a verified
-- webhook or a direct query — never because a mobile app reported it.

CREATE TABLE payment (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    procurement_id  BIGINT       NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    provider        VARCHAR(64)  NOT NULL,
    -- The provider's own id for this payment. Null until they issue one.
    provider_payment_id VARCHAR(200) NULL,
    -- The order/intent id we asked the provider to create, which the client uses
    -- to open their checkout. Distinct from provider_payment_id: one intent can
    -- produce several attempts.
    provider_order_id VARCHAR(200) NULL,
    -- CREATED → AUTHORIZED → CAPTURE_PENDING → CAPTURED, plus FAILED, RELEASED,
    -- PARTIALLY_REFUNDED, FULLY_REFUNDED. Doc 03 §6.
    status          VARCHAR(32)  NOT NULL DEFAULT 'CREATED',
    payment_method  VARCHAR(32)  NOT NULL DEFAULT 'PREPAID',

    -- What we asked the provider to hold: the full order total, because what the
    -- supplier will accept is not yet known.
    authorized_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- What was actually taken: the accepted commercial value (doc 01 §14). Below
    -- authorized_amount after a partial acceptance, zero after a rejection.
    captured_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    refunded_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- Returned without ever being taken — the unaccepted remainder. Distinct from
    -- a refund, which returns money that was captured. Conflating them would make
    -- a supplier's partial acceptance look like a dispute.
    released_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',

    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    authorized_at   TIMESTAMP(6) NULL,
    captured_at     TIMESTAMP(6) NULL,
    released_at     TIMESTAMP(6) NULL,
    -- When we last agreed with the provider about this payment's state (doc 21).
    reconciled_at   TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_payment_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_payment_procurement FOREIGN KEY (procurement_id) REFERENCES procurement (id),
    CONSTRAINT fk_payment_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    -- One payment per order. The database guard behind idempotent creation: a
    -- duplicate initiation cannot produce two authorisations against one order,
    -- whatever the client sent (doc 10 §2).
    CONSTRAINT uk_payment_order UNIQUE (supplier_order_id),
    -- Doc 10 §3's invariants, enforced here rather than by discipline.
    CONSTRAINT ck_payment_captured CHECK (captured_amount <= authorized_amount),
    CONSTRAINT ck_payment_refunded CHECK (refunded_amount <= captured_amount),
    KEY ix_payment_provider_payment (provider, provider_payment_id),
    KEY ix_payment_status (status, created_at),
    KEY ix_payment_procurement (procurement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every movement of money, append-only. Doc 02 §3, doc 09 §11.
--
-- The payment row holds current balances; this holds how they got there. A
-- settlement or a dispute months later is reconstructed from these rows, so
-- nothing here is ever updated — a correction is another row.
CREATE TABLE payment_transaction (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    payment_id      BIGINT       NOT NULL,
    -- AUTHORIZE, CAPTURE, RELEASE, REFUND.
    transaction_type VARCHAR(32) NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    currency        CHAR(3)      NOT NULL DEFAULT 'INR',
    status          VARCHAR(32)  NOT NULL,
    provider_reference VARCHAR(200) NULL,
    -- The key we sent the provider, so a retry reaches the same operation on
    -- their side too. Ours alone is not enough: a network failure after they
    -- acted would otherwise let a retry charge twice.
    idempotency_key VARCHAR(200) NULL,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_payment_transaction_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    KEY ix_payment_transaction_payment (payment_id, created_at),
    KEY ix_payment_transaction_provider (provider_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Refunds. Doc 01 §15, doc 03 §7, doc 22.
--
-- A refund returns money that was captured. Returning money never taken is a
-- release, recorded on the payment — see released_amount above.
CREATE TABLE refund (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    payment_id      BIGINT       NOT NULL,
    supplier_order_id BIGINT     NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    -- SUPPLIER_REJECTION, PARTIAL_ACCEPTANCE, CANCELLATION, DELIVERY_FAILURE,
    -- DISPUTE_RESOLVED, DUPLICATE_PAYMENT, PROVIDER_REVERSAL. Doc 22.
    reason          VARCHAR(64)  NOT NULL,
    note            VARCHAR(500) NULL,
    -- REQUESTED → PROCESSING → COMPLETED, or FAILED. Doc 03 §7.
    status          VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    provider_refund_id VARCHAR(200) NULL,
    -- Client-supplied, and uniquely indexed: doc 22 requires refunds to be
    -- idempotent, and a duplicate refund request is money leaving twice.
    idempotency_key VARCHAR(200) NOT NULL,
    requested_by    BIGINT       NULL,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    completed_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT fk_refund_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_refund_requested_by FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT uk_refund_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    KEY ix_refund_payment (payment_id, created_at),
    KEY ix_refund_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every provider webhook, stored before it is acted on. Doc 04 §12, doc 09 §5.
--
-- Three requirements meet in this table.
--
-- **Deduplication.** Providers retry, so the same event arrives repeatedly.
-- `provider_event_id` is unique, and an insert that collides is a duplicate —
-- decided by the database rather than by a check-then-act that would race with
-- the provider's own retry.
--
-- **Out-of-order handling.** Doc 46: a capture event can arrive before the
-- authorisation event it follows. The raw payload is kept so a late-arriving
-- event can be reconciled against what we already believe, rather than blindly
-- applied.
--
-- **Auditability.** Doc 09 §5 requires provider event ids to be persisted. When a
-- restaurant and a provider disagree about a charge, this is the record.
CREATE TABLE payment_webhook_event (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider        VARCHAR(64)  NOT NULL,
    provider_event_id VARCHAR(200) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    provider_payment_id VARCHAR(200) NULL,
    -- Resolved where we recognise the payment; null for an event about something
    -- we have no record of, which is itself worth keeping.
    payment_id      BIGINT       NULL,
    -- Verbatim. Never re-serialised: a signature is over the exact bytes, and a
    -- round-trip through a parser would make it unverifiable afterwards.
    payload         JSON         NOT NULL,
    signature_verified TINYINT(1) NOT NULL DEFAULT 0,
    -- RECEIVED → PROCESSED, or IGNORED (duplicate, or not relevant) / FAILED.
    status          VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    processing_error VARCHAR(1000) NULL,
    -- When the provider says it happened, which may be well before we saw it.
    occurred_at     TIMESTAMP(6) NULL,
    processed_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_webhook_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT uk_webhook_provider_event UNIQUE (provider, provider_event_id),
    KEY ix_webhook_payment (payment_id, created_at),
    KEY ix_webhook_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
