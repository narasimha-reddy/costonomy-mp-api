-- V84 — When a credit offer was made, so an offer nobody accepts can lapse after 14 India days (D-166), and the
-- history of due-date extensions (D-167).
--
-- credit_agreement.updated_at is bumped by every exposure movement and cannot say when the supplier made the offer,
-- so the offer carries its own timestamp. Set when the supplier approves on different terms or edits an APPROVED
-- offer; cleared when the line is activated, closed, or asked for again. Existing APPROVED offers start from their
-- last update, which is when they were last touched by anybody.
ALTER TABLE credit_agreement ADD COLUMN offer_made_at TIMESTAMP(6) NULL AFTER suspension_source;

UPDATE credit_agreement SET offer_made_at = updated_at WHERE status = 'APPROVED';

-- Every time a supplier gave an invoice longer to be paid (D-167). Append-only: the history is shown on the invoice,
-- and the first row's old_due_date is the ORIGINAL due date the 60-day cap is measured from.
CREATE TABLE credit_due_extension (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_invoice_id BIGINT       NOT NULL,
    credit_agreement_id BIGINT     NOT NULL,
    old_due_date      DATE         NOT NULL,
    new_due_date      DATE         NOT NULL,
    old_overdue_after DATE         NOT NULL,
    new_overdue_after DATE         NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    extended_by       BIGINT       NULL,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_due_extension_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_due_extension_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT ck_credit_due_extension_later CHECK (new_due_date > old_due_date),
    KEY ix_credit_due_extension_invoice (credit_invoice_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
