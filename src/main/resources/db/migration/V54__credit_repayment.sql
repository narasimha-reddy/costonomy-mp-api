-- V54 — Credit repayments: the tables and the CREDIT_REPAY permission (D-121).
--
-- A restaurant will be able to repay a supplier-funded credit invoice itself,
-- first from its wallet, later by UPI or card and by confirming a claim. Credit
-- stays the supplier's: Mandi never funds or guarantees it, it only moves the
-- restaurant's own money and records that it did. This migration is groundwork
-- only. No code reads or writes these objects yet.

-- One credit_repayment row per money movement. A single repayment can settle
-- several invoices of the same agreement, so the per-invoice rows stay in
-- credit_payment and point back here.
CREATE TABLE credit_repayment (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id   BIGINT        NOT NULL,
    outlet_id             BIGINT        NOT NULL,
    supplier_store_id     BIGINT        NOT NULL,
    amount                DECIMAL(19,4) NOT NULL,

    -- WALLET now; UPI and CARD are reserved so the row shape does not change.
    source                VARCHAR(16)   NOT NULL,

    -- The wallet debit that funded it. One debit can fund one repayment only.
    wallet_transaction_id BIGINT        NULL,
    -- The payment provider's id for UPI or card. Unique for the same reason.
    provider_payment_id   VARCHAR(80)   NULL,

    status                VARCHAR(16)   NOT NULL DEFAULT 'COMPLETED',
    idempotency_key       VARCHAR(200)  NOT NULL,
    created_by            BIGINT        NULL,

    created_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_repayment_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_repayment_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_repayment_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_repayment_wallet_tx FOREIGN KEY (wallet_transaction_id) REFERENCES wallet_transaction (id),
    CONSTRAINT uk_credit_repayment_wallet_tx UNIQUE (wallet_transaction_id),
    CONSTRAINT uk_credit_repayment_provider_payment UNIQUE (provider_payment_id),
    CONSTRAINT uk_credit_repayment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_repayment_amount CHECK (amount > 0),

    KEY ix_credit_repayment_agreement (credit_agreement_id),
    KEY ix_credit_repayment_outlet (outlet_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Where a credit_payment came from (32 wide: SUPPLIER_RECORDED does not fit 16). Every existing row is a payment the supplier
-- recorded, which is what the default says. claim_id has no foreign key: the
-- claims table arrives in V55. MySQL allows many NULLs in a UNIQUE key, so the
-- supplier's manual payments (no repayment) are not limited by the new key.
ALTER TABLE credit_payment
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'SUPPLIER_RECORDED' AFTER idempotency_key,
    ADD COLUMN credit_repayment_id BIGINT NULL AFTER source,
    ADD COLUMN claim_id BIGINT NULL AFTER credit_repayment_id,
    ADD CONSTRAINT fk_credit_payment_repayment FOREIGN KEY (credit_repayment_id) REFERENCES credit_repayment (id),
    ADD CONSTRAINT uk_credit_payment_repayment_invoice UNIQUE (credit_repayment_id, credit_invoice_id);

-- Repaying is its own permission, granted to the roles that already handle the
-- outlet's money (the same four as QUICKSCAN_PAY in V40). Never to supplier or
-- operations roles: they cannot spend a restaurant's wallet.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('CREDIT_REPAY', 'Repay credit', 'RESTAURANT',
 'Repay a supplier''s credit invoice', NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'CREDIT_REPAY'
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN', 'REST_PURCHASE_MANAGER', 'REST_FINANCE_STAFF');
