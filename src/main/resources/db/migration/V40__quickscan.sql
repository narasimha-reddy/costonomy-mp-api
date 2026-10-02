-- V40 — QuickScan: pay any UPI merchant from the wallet (D-106, part one).
--
-- Everything else in this schema pays a supplier the restaurant already has an
-- order with. QuickScan is different on purpose: scan any shop's UPI QR code —
-- the corner vegetable seller, the ice supplier with no Mandi account — and pay
-- them from the wallet balance, the same way a UPI app would from a bank
-- account. Sandbox only: this is behind a feature flag that refuses to come up
-- in production (ProductionProviderGuard) until legal has signed off on paying
-- third parties out of a wallet balance (RBI's prepaid-instrument rules, the
-- same open question D-104 raised and left unanswered).
--
-- The wallet is debited the moment a payment is created, before any payout has
-- gone anywhere — the same shape payments and refunds already use: the write
-- that must never race a second click happens first, and the payout catches up
-- with what the ledger already recorded.

CREATE TABLE quickscan_payment (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id           BIGINT        NOT NULL,
    created_by          BIGINT        NOT NULL,

    payee_vpa           VARCHAR(100)  NOT NULL,
    payee_name          VARCHAR(100)  NULL,
    note                VARCHAR(200)  NULL,

    amount              DECIMAL(19,4) NOT NULL,
    -- Flat, added to every payment. Zero for now — the fee policy is
    -- undecided (docs/DECISIONS.md D-106) — but the column exists so pricing
    -- it later is a config change, not a migration.
    fee_amount          DECIMAL(19,4) NOT NULL DEFAULT 0,

    -- Only WALLET works today. UPI (paying straight from a bank account,
    -- rather than a prepaid balance) is reserved so the row shape does not
    -- need to change when it arrives.
    method              VARCHAR(16)   NOT NULL,

    -- PAYOUT_PENDING (wallet debited, payout not finished) → PAID | FAILED
    -- (refused or reversed; money returned) | NEEDS_REVIEW (retries exhausted
    -- with the outcome unknown — money is *not* auto-returned, because the
    -- payout may already have reached the shop).
    status              VARCHAR(32)   NOT NULL,

    provider_payout_id  VARCHAR(100)  NULL,
    attempts            INT           NOT NULL DEFAULT 0,
    failure_code        VARCHAR(64)   NULL,
    failure_reason      VARCHAR(500)  NULL,

    -- qs-{outletId}-{client key}, so the same client-supplied key reused by a
    -- different outlet cannot collide with someone else's payment.
    idempotency_key     VARCHAR(200)  NOT NULL,

    paid_at             TIMESTAMP(6)  NULL,

    -- When the provider was last asked about this payout. NULL until the first
    -- check. Without it a plain `save()` on an unchanged row is a no-op — no
    -- dirty field, no UPDATE, updated_at never moves — so every PAYOUT_PENDING
    -- and PAID row would be fetched from the provider on every job run forever
    -- (a rate-limit and cost problem once a real provider is behind this).
    checked_at          TIMESTAMP(6)  NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_quickscan_payment_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_quickscan_payment_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_quickscan_payment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_quickscan_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_quickscan_payment_fee CHECK (fee_amount >= 0),

    KEY ix_quickscan_payment_outlet (outlet_id, created_at),
    KEY ix_quickscan_payment_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Paying a shop moves money out of the wallet balance to somewhere Mandi has
-- no order and no supplier relationship with, so it is its own permission
-- rather than an extension of PAYMENT_CREATE or WALLET_WITHDRAW. Granted by
-- default to the same roles that already handle the outlet's money.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('QUICKSCAN_PAY', 'Pay by QuickScan', 'RESTAURANT',
 'Pay a shop by QR from the wallet', NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'QUICKSCAN_PAY'
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN', 'REST_PURCHASE_MANAGER', 'REST_FINANCE_STAFF');
