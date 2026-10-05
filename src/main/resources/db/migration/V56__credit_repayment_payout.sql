-- V56 — What Mandi owes a supplier for credit a restaurant repaid from its wallet (D-126).
--
-- A wallet repayment takes the restaurant's own money out of its wallet (D-122).
-- That money is not Mandi's: Mandi owes it to the supplier. One row here records
-- the debt, PENDING, in the same transaction as the repayment; the next
-- settlement for the supplier store applies it as settlement adjustments (a CREDIT
-- for the amount, a DEBIT for commission) and marks the row APPLIED.
--
-- The commission rate is a snapshot taken at repayment time: a later change to
-- commission_configuration never changes a row that already exists.
CREATE TABLE credit_repayment_payout (
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_repayment_id         BIGINT        NOT NULL,
    supplier_store_id           BIGINT        NOT NULL,
    amount                      DECIMAL(19,4) NOT NULL,

    -- Null when no rate could be resolved, or when the commission switch is off.
    commission_rate_percent     DECIMAL(9,4)  NULL,
    commission_configuration_id BIGINT        NULL,
    commission_amount           DECIMAL(19,4) NOT NULL DEFAULT 0,

    -- PENDING, then APPLIED once a settlement carries it. Never back.
    status                      VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    settlement_id               BIGINT        NULL,
    credit_adjustment_id        BIGINT        NULL,
    commission_adjustment_id    BIGINT        NULL,
    applied_at                  TIMESTAMP(6)  NULL,

    created_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                     BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_repayment_payout_repayment FOREIGN KEY (credit_repayment_id) REFERENCES credit_repayment (id),
    CONSTRAINT fk_credit_repayment_payout_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT uk_credit_repayment_payout_repayment UNIQUE (credit_repayment_id),
    CONSTRAINT ck_credit_repayment_payout_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_repayment_payout_commission CHECK (commission_amount >= 0 AND commission_amount <= amount),

    KEY ix_credit_repayment_payout_store_status (supplier_store_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
