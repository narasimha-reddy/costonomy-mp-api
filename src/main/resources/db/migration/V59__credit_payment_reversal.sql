-- V59 — The supplier undoes a payment it recorded (B6, D-140): a reversal, never a delete.
--
-- One row per credit_payment taken back, in a table of its own so credit_payment keeps its amount > 0 check and
-- stays append-only. A receipt over several invoices is reversed as one: one row per payment, all with the receipt's
-- id and a key suffixed with the payment id (credit_payment's own rule, V54). The unique key on the payment is what
-- makes a second reversal of it impossible even if two arrive at once.
--
-- credit_repayment.status is VARCHAR(16): REVERSED fits, no change there. The ledger type PAYMENT_REVERSED fits
-- credit_transaction.transaction_type VARCHAR(32).
CREATE TABLE credit_payment_reversal (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_payment_id   BIGINT        NOT NULL,
    -- The receipt it was reversed with; null for a payment recorded one invoice at a time or a confirmed claim.
    receipt_id          BIGINT        NULL,
    credit_invoice_id   BIGINT        NOT NULL,
    credit_agreement_id BIGINT        NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    reason              VARCHAR(500)  NOT NULL,
    reversed_by         BIGINT        NULL,
    reversed_at         TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    idempotency_key     VARCHAR(200)  NOT NULL,

    CONSTRAINT fk_credit_reversal_payment FOREIGN KEY (credit_payment_id) REFERENCES credit_payment (id),
    CONSTRAINT fk_credit_reversal_receipt FOREIGN KEY (receipt_id) REFERENCES credit_repayment (id),
    CONSTRAINT fk_credit_reversal_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_reversal_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_reversal_by FOREIGN KEY (reversed_by) REFERENCES users (id),
    CONSTRAINT uk_credit_reversal_payment UNIQUE (credit_payment_id),
    CONSTRAINT uk_credit_reversal_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_reversal_amount CHECK (amount > 0),

    KEY ix_credit_reversal_receipt (receipt_id),
    KEY ix_credit_reversal_invoice (credit_invoice_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
