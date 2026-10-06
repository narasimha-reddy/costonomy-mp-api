-- V62 — Reminders to a restaurant about what it owes (D-142), and the daily credit digest to a supplier (D-148).
--
-- credit_reminder: one row per reminder sent or waiting to be sent, manual or automatic. A reminder is about a line and
-- names its invoices in credit_reminder_invoice. requested_at is the credit clock's instant (India time, set by the
-- server) so the 24 hour, 7 day and India-day limits are measured on one clock. A reminder asked for outside 09:00-20:00
-- IST is QUEUED and a job sends it when the window opens.
CREATE TABLE credit_reminder (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT       NOT NULL,
    outlet_id           BIGINT       NOT NULL,
    supplier_store_id   BIGINT       NOT NULL,
    kind                VARCHAR(16)  NOT NULL,
    channel             VARCHAR(64)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    note                VARCHAR(300) NULL,
    message             VARCHAR(1000) NOT NULL,
    created_by          BIGINT       NULL,
    requested_at        TIMESTAMP(6) NOT NULL,
    sent_at             TIMESTAMP(6) NULL,
    idempotency_key     VARCHAR(160) NULL,
    created_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_reminder_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT ck_credit_reminder_kind CHECK (kind IN ('MANUAL', 'AUTO_T3', 'AUTO_DUE', 'AUTO_WEEKLY')),
    CONSTRAINT ck_credit_reminder_status CHECK (status IN ('QUEUED', 'SENT', 'CANCELLED')),
    CONSTRAINT uk_credit_reminder_idempotency UNIQUE (idempotency_key),
    KEY ix_credit_reminder_agreement (credit_agreement_id, kind, requested_at),
    KEY ix_credit_reminder_store (supplier_store_id, kind, requested_at),
    KEY ix_credit_reminder_queue (status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The invoices a reminder is about. auto_key is 'KIND:yyyy-MM-dd' (the India day) for an automatic reminder and NULL for a
-- manual one, so the unique key stops the same automatic reminder reaching one invoice twice on one day, even from two
-- nodes, and puts no limit on manual ones (those are limited by the rules in the service). Append-only.
CREATE TABLE credit_reminder_invoice (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_reminder_id BIGINT      NOT NULL,
    credit_invoice_id BIGINT       NOT NULL,
    kind              VARCHAR(16)  NOT NULL,
    remind_date       DATE         NOT NULL,
    auto_key          VARCHAR(40)  NULL,
    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_reminder_invoice_reminder FOREIGN KEY (credit_reminder_id) REFERENCES credit_reminder (id),
    CONSTRAINT fk_credit_reminder_invoice_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT uk_credit_reminder_invoice_auto UNIQUE (credit_invoice_id, auto_key),
    KEY ix_credit_reminder_invoice_reminder (credit_reminder_id),
    KEY ix_credit_reminder_invoice_kind (credit_invoice_id, kind, remind_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Automatic reminders are on unless the supplier turns them off (plan decision 14).
ALTER TABLE supplier_credit_policy
    ADD COLUMN auto_reminders_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER auto_suspend_enabled;

