-- V18 — Commission and settlement.
--
-- Doc 01 §16–17, doc 02 §3, doc 03 §13, doc 04 §19, doc 05 §33, doc 09 §11.
--
-- **Historical settlement must not depend on current configuration** (doc 05 §33),
-- and **settlement must be reproducible** (doc 09 §11). Everything below follows
-- from taking those two literally: the rate that applied is snapshotted onto each
-- calculation, the calculation is never recomputed, and a correction is an
-- adjustment row rather than an edit.
--
-- It is the same rule as D-012 (a price is never edited, only superseded) and
-- D-047 (a configuration change supersedes), applied to money leaving the
-- platform. A settlement run in March that produces a different number when
-- replayed in June is not a settlement, it is an estimate.

-- What rate applies, to whom, from when. Doc 09 §10–11.
--
-- Scoped so a negotiated rate for one supplier is expressible without a code
-- change: the most specific ACTIVE row wins — store, then organisation, then the
-- platform default.
CREATE TABLE commission_configuration (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- PLATFORM, SUPPLIER or SUPPLIER_STORE.
    scope_type      VARCHAR(32)  NOT NULL,
    -- Null for PLATFORM. The organisation or store this rate is negotiated for.
    scope_id        BIGINT       NULL,
    -- Percent. 1.00 means 1%, which is doc 01 §16's default.
    rate_percent    DECIMAL(9,4) NOT NULL,
    config_version  INT          NOT NULL DEFAULT 1,
    description     VARCHAR(500) NULL,
    -- Effective-dated, because doc 09 §10 requires it "where financially
    -- relevant" and nothing is more financially relevant than this.
    effective_from  TIMESTAMP(6) NOT NULL,
    effective_to    TIMESTAMP(6) NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_commission_config_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_commission_config_version UNIQUE (scope_type, scope_id, config_version),
    CONSTRAINT ck_commission_rate CHECK (rate_percent >= 0 AND rate_percent <= 100),
    KEY ix_commission_config_lookup (scope_type, scope_id, status, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What one supplier order owes. Append-only in spirit: written once, never
-- recomputed. Doc 01 §16.
CREATE TABLE commission_calculation (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id BIGINT     NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    supplier_organization_id BIGINT NOT NULL,

    -- **Item value plus GST, excluding delivery** (doc 01 §16), and only the
    -- accepted portion — a partial acceptance owes commission on what was
    -- actually supplied, not on what was ordered.
    gross_amount    DECIMAL(19,4) NOT NULL,
    -- The rate that applied *at the moment of calculation*, copied here. Doc 05
    -- §33: a settlement read next year must not move because someone renegotiated
    -- a rate this morning. Recomputing from commission_configuration would make
    -- every historical figure a function of today's table.
    rate_percent    DECIMAL(9,4) NOT NULL,
    commission_configuration_id BIGINT NULL,
    commission_amount DECIMAL(19,4) NOT NULL,
    -- gross - commission. Stored rather than derived so the arithmetic that
    -- produced a payout is on the record, not re-performed by whoever reads it.
    net_amount      DECIMAL(19,4) NOT NULL,

    -- Null until this calculation is swept into a settlement. The link is here
    -- rather than in a join table because a calculation belongs to exactly one
    -- settlement, and doc 02 §3's table list has no line table.
    settlement_id   BIGINT       NULL,
    calculated_at   TIMESTAMP(6) NOT NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_commission_calc_order FOREIGN KEY (supplier_order_id)
        REFERENCES supplier_order (id),
    CONSTRAINT fk_commission_calc_store FOREIGN KEY (supplier_store_id)
        REFERENCES supplier_store (id),
    CONSTRAINT fk_commission_calc_config FOREIGN KEY (commission_configuration_id)
        REFERENCES commission_configuration (id),
    -- One calculation per order. Charging a supplier twice for one order is the
    -- failure this constraint exists to make impossible.
    CONSTRAINT uk_commission_calc_order UNIQUE (supplier_order_id),
    CONSTRAINT ck_commission_amount CHECK (commission_amount >= 0
        AND commission_amount <= gross_amount),
    KEY ix_commission_calc_unsettled (supplier_store_id, settlement_id),
    KEY ix_commission_calc_settlement (settlement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What is paid to one supplier store, for one period. Doc 01 §17, doc 03 §13.
--
-- PENDING → CALCULATED → APPROVED → PROCESSING → PAID, with PROCESSING → FAILED.
CREATE TABLE settlement (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    settlement_number VARCHAR(40) NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    supplier_organization_id BIGINT NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',

    -- The window whose completed orders this covers.
    period_start    TIMESTAMP(6) NOT NULL,
    period_end      TIMESTAMP(6) NOT NULL,
    -- Completion plus the configured offset — T+2 by default (doc 01 §17).
    settlement_date DATE         NOT NULL,

    -- Gross - Commission ± Adjustments = Net (doc 01 §17). All four are stored:
    -- the supplier's statement (§23A.33, doc 05 §33) shows every line of that
    -- equation, and deriving three of them from one would let a rounding
    -- difference appear between what we paid and what we said we paid.
    gross_amount    DECIMAL(19,4) NOT NULL DEFAULT 0,
    commission_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    adjustment_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    net_amount      DECIMAL(19,4) NOT NULL DEFAULT 0,
    order_count     INT          NOT NULL DEFAULT 0,

    -- Reconciliation against captured payments. Doc 09 §11 and doc 03 §13's
    -- "reconciliation must be idempotent" — this records the last answer rather
    -- than blocking on it, because a mismatch needs a human, not a retry.
    reconciled_at   TIMESTAMP(6) NULL,
    reconciled_gross DECIMAL(19,4) NULL,
    reconciliation_note VARCHAR(500) NULL,

    approved_by     BIGINT       NULL,
    approved_at     TIMESTAMP(6) NULL,
    paid_at         TIMESTAMP(6) NULL,
    payment_reference VARCHAR(200) NULL,
    failure_reason  VARCHAR(500) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_settlement_store FOREIGN KEY (supplier_store_id)
        REFERENCES supplier_store (id),
    CONSTRAINT fk_settlement_org FOREIGN KEY (supplier_organization_id)
        REFERENCES supplier_organization (id),
    CONSTRAINT fk_settlement_approved_by FOREIGN KEY (approved_by) REFERENCES users (id),
    CONSTRAINT uk_settlement_number UNIQUE (settlement_number),
    -- One settlement per store per period. A second would pay the same orders
    -- twice, and the unique constraint is what makes a re-run idempotent rather
    -- than merely careful.
    CONSTRAINT uk_settlement_period UNIQUE (supplier_store_id, period_start, period_end),
    KEY ix_settlement_status (status, settlement_date),
    KEY ix_settlement_store (supplier_store_id, settlement_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A correction. Append-only. Doc 01 §17: "all adjustments must be auditable".
--
-- **Corrections are adjustments, never edits.** Doc 09 §11 says it of the credit
-- ledger and the same reasoning applies here: editing a settled figure destroys
-- the evidence of what was paid, and the supplier's copy of the statement stops
-- matching ours.
CREATE TABLE settlement_adjustment (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    settlement_id   BIGINT       NOT NULL,
    -- CREDIT adds to the payout, DEBIT subtracts. Signed rather than implied by
    -- the amount, so a reader never has to infer direction from a minus sign
    -- that may or may not be there.
    direction       VARCHAR(16)  NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    -- REFUND, DISPUTE_RESOLUTION, MANUAL_CORRECTION, PENALTY, INCENTIVE.
    reason_code     VARCHAR(64)  NOT NULL,
    -- Never nullable. An unexplained deduction from a supplier's payout is the
    -- thing doc 01 §17's audit requirement exists to prevent.
    reason          VARCHAR(500) NOT NULL,
    supplier_order_id BIGINT     NULL,
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_settlement_adjustment_settlement FOREIGN KEY (settlement_id)
        REFERENCES settlement (id),
    CONSTRAINT fk_settlement_adjustment_order FOREIGN KEY (supplier_order_id)
        REFERENCES supplier_order (id),
    CONSTRAINT fk_settlement_adjustment_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_settlement_adjustment_amount CHECK (amount > 0),
    KEY ix_settlement_adjustment_settlement (settlement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Settlement numbering, mirroring orders, invoices and disputes.
CREATE TABLE settlement_number_sequence (
    sequence_date   DATE         NOT NULL,
    next_value      BIGINT       NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The platform default. Doc 01 §16: 1%.
--
-- A row rather than only a property, because doc 09 §10 requires the rate to be
-- versioned and effective-dated — and because a settlement replayed next year has
-- to find the rate that applied, which a property file cannot tell it.
INSERT INTO commission_configuration
    (scope_type, scope_id, rate_percent, config_version, description,
     effective_from, status, created_at, updated_at)
VALUES
('PLATFORM', NULL, 1.0000, 1,
 'Default marketplace commission. Doc 01 §16: item value plus GST, excluding delivery.',
 NOW(6), 'ACTIVE', NOW(6), NOW(6));
