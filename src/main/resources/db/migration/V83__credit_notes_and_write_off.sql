-- V83 — Credit notes, write-offs and refunds due (B7, B8, D-175..D-180).
--
-- A credit note reduces what a restaurant owes on one invoice without being a payment: the goods were not supplied
-- (a cancelled order), were short or damaged, or the price was agreed down. A write-off is the supplier giving up
-- on what is still owed. Both are rows of credit_invoice_note (kind MANUAL, SYSTEM_CANCEL, WRITE_OFF), both add to
-- credit_invoice.credited_amount, and from here on outstanding = amount - paid_amount - credited_amount everywhere.
-- Neither touches a payout or commission: no money moves through Mandi.
--
-- credit_invoice_note is append-only (no updated_at): a note is never edited or deleted, a mistake is answered by the
-- supplier, not by rewriting history.

-- Per-day counter for CLN-yymmdd-NNNNNN, like credit_invoice_sequence (V12): a counter and not a count of rows.
CREATE TABLE credit_invoice_note_sequence (
    sequence_date DATE         PRIMARY KEY,
    next_value    BIGINT       NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_invoice_note (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_note_number  VARCHAR(40)   NOT NULL,
    credit_invoice_id   BIGINT        NOT NULL,
    credit_agreement_id BIGINT        NOT NULL,
    outlet_id           BIGINT        NOT NULL,
    supplier_store_id   BIGINT        NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    -- SHORT_SUPPLY, QUALITY, PRICE, CANCELLED, GOODWILL, OTHER.
    reason_code         VARCHAR(32)   NOT NULL,
    -- MANUAL: the supplier issued it. SYSTEM_CANCEL: an order was cancelled after the draw. WRITE_OFF: given up.
    kind                VARCHAR(16)   NOT NULL,
    note                VARCHAR(500)  NULL,
    dispute_id          BIGINT        NULL,
    idempotency_key     VARCHAR(200)  NOT NULL,
    -- Null for the system.
    created_by          BIGINT        NULL,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_invoice_note_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_invoice_note_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_invoice_note_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_invoice_note_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_invoice_note_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_credit_invoice_note_dispute FOREIGN KEY (dispute_id) REFERENCES dispute (id),
    CONSTRAINT uk_credit_invoice_note_number UNIQUE (credit_note_number),
    CONSTRAINT uk_credit_invoice_note_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_invoice_note_amount CHECK (amount > 0),
    KEY ix_credit_invoice_note_invoice (credit_invoice_id, id),
    KEY ix_credit_invoice_note_agreement (credit_agreement_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What was taken off the invoice by notes and write-offs. paid + credited can never pass the amount.
ALTER TABLE credit_invoice
    ADD COLUMN credited_amount DECIMAL(19,4) NOT NULL DEFAULT 0 AFTER paid_amount,
    ADD CONSTRAINT ck_credit_invoice_credited CHECK (credited_amount >= 0 AND paid_amount + credited_amount <= amount);

-- The ledger row of a note or write-off names it, so the statement shows its number without matching by position.
ALTER TABLE credit_transaction
    ADD COLUMN credit_note_id BIGINT NULL AFTER credit_invoice_id,
    ADD CONSTRAINT fk_credit_txn_note FOREIGN KEY (credit_note_id) REFERENCES credit_invoice_note (id);

-- Money the restaurant paid on an invoice whose order was then cancelled: it cannot be cancelled by a note, it is
-- owed back to the restaurant. OFF_PLATFORM: the supplier received it directly and refunds it directly, then marks
-- it REFUNDED here. WALLET: the restaurant paid it from its Mandi wallet and Mandi paid it on to the supplier, so
-- only ops can put it right; this PR flags it and moves nothing (decision 5, D-177).
CREATE TABLE credit_refund_due (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    -- Null when the invoice was already fully paid: there was nothing left for a note to cancel.
    credit_note_id      BIGINT        NULL,
    credit_invoice_id   BIGINT        NOT NULL,
    credit_agreement_id BIGINT        NOT NULL,
    outlet_id           BIGINT        NOT NULL,
    supplier_store_id   BIGINT        NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    channel             VARCHAR(16)   NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    note                VARCHAR(500)  NULL,
    refunded_at         TIMESTAMP(6)  NULL,
    refunded_by         BIGINT        NULL,
    idempotency_key     VARCHAR(200)  NOT NULL,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_refund_note FOREIGN KEY (credit_note_id) REFERENCES credit_invoice_note (id),
    CONSTRAINT fk_credit_refund_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_refund_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_refund_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_refund_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_refund_by FOREIGN KEY (refunded_by) REFERENCES users (id),
    CONSTRAINT uk_credit_refund_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_refund_amount CHECK (amount > 0),
    KEY ix_credit_refund_store (supplier_store_id, status, id),
    KEY ix_credit_refund_invoice (credit_invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
