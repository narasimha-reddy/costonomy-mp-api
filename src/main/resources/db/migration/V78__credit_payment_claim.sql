-- V78 — "I paid" claims on credit invoices (D-155).
--
-- A restaurant that paid a supplier directly (bank transfer, UPI, cash, cheque, card) can tell Mandi so. That is only
-- a statement: it changes nothing about the invoice or the credit line until the supplier confirms it, and the
-- confirmation is what writes the credit_payment row. Mandi never lets a restaurant clear its own debt on its say-so.
-- No proof photo column yet: uploading proof is a later change.
CREATE TABLE credit_payment_claim (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_invoice_id   BIGINT        NOT NULL,
    credit_agreement_id BIGINT        NOT NULL,
    outlet_id           BIGINT        NOT NULL,
    supplier_store_id   BIGINT        NOT NULL,

    -- What the restaurant says it paid, and how.
    amount              DECIMAL(19,4) NOT NULL,
    method              VARCHAR(32)   NOT NULL,
    reference           VARCHAR(200)  NULL,
    paid_on             DATE          NOT NULL,
    note                VARCHAR(500)  NULL,

    -- SUBMITTED, CONFIRMED, REJECTED, WITHDRAWN. Only SUBMITTED can move.
    status              VARCHAR(16)   NOT NULL,
    decision_note       VARCHAR(500)  NULL,
    -- What the supplier confirmed: at most the amount claimed and at most what was outstanding then.
    confirmed_amount    DECIMAL(19,4) NULL,
    -- The payment a confirmation wrote. One claim can write at most one payment.
    credit_payment_id   BIGINT        NULL,

    claimed_by          BIGINT        NULL,
    decided_by          BIGINT        NULL,
    decided_at          TIMESTAMP(6)  NULL,
    idempotency_key     VARCHAR(200)  NOT NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_claim_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_claim_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_claim_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_claim_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_claim_payment FOREIGN KEY (credit_payment_id) REFERENCES credit_payment (id),
    CONSTRAINT uk_credit_claim_payment UNIQUE (credit_payment_id),
    CONSTRAINT uk_credit_claim_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_claim_amount CHECK (amount > 0),

    KEY ix_credit_claim_store_status (supplier_store_id, status),
    KEY ix_credit_claim_invoice_status (credit_invoice_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- V77 added credit_payment.claim_id without a foreign key because this table did not exist yet.
ALTER TABLE credit_payment
    ADD CONSTRAINT fk_credit_payment_claim FOREIGN KEY (claim_id) REFERENCES credit_payment_claim (id);
