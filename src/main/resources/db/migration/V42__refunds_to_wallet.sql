-- V42 — Refunds go to the wallet; a withdrawal goes back to the card (D-104).
--
-- Until now a refund was only ever a provider refund: money back to the card or
-- bank it came from, days later, with a gateway in between. The product owner's
-- ruling is that a refund is credited to the restaurant's wallet at once, and
-- that money leaves the wallet only back to the method it came from.
--
-- Nothing here moves money. It records where a refund went, and why each wallet
-- movement happened, so that "where did this rupee go" has an answer in rows.

-- Where a refund's money went. ORIGINAL is a provider refund to the card or bank,
-- which is every refund before this migration; WALLET is a credit to the outlet's
-- balance, completed in the same transaction as the credit itself.
ALTER TABLE refund
    ADD COLUMN destination VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL' AFTER reason;

-- What a wallet movement was. Direction says which way the balance moved; kind
-- says why, which is what a statement has to show and what a withdrawal needs
-- to find the money a card can take back.
ALTER TABLE wallet_transaction
    ADD COLUMN kind VARCHAR(32) NULL AFTER direction,
    -- The one operation this movement belongs to, unique so that the same
    -- operation cannot move the balance twice: "refund-41" is the credit for
    -- refund 41, "withdrawal-42" the debit that sent refund 42 back to a card.
    ADD COLUMN reference VARCHAR(80) NULL AFTER kind,
    ADD COLUMN refund_id BIGINT NULL AFTER reference;

UPDATE wallet_transaction
   SET kind = CASE
         WHEN supplier_order_id IS NULL THEN 'TOP_UP'
         WHEN direction = 'DEBIT' THEN 'ORDER_PAYMENT'
         ELSE 'ORDER_REFUND'
       END;

ALTER TABLE wallet_transaction
    MODIFY COLUMN kind VARCHAR(32) NOT NULL,
    ADD CONSTRAINT uk_wallet_txn_reference UNIQUE (reference),
    ADD CONSTRAINT fk_wallet_txn_refund FOREIGN KEY (refund_id) REFERENCES refund (id);

-- The old key was one row per (order, direction). It still has to hold for an
-- order paid from the wallet — one payment, one return — but a card-paid order
-- can now have several wallet credits against it (two disputes, say), which the
-- old key would refuse. So the key moves to a column that is the order only for
-- those two kinds, and null otherwise; MySQL lets any number of nulls through.
--
-- The foreign key on supplier_order_id relied on the old key for its index, so
-- that index is added first or MySQL refuses the drop.
CREATE INDEX ix_wallet_txn_order ON wallet_transaction (supplier_order_id);

ALTER TABLE wallet_transaction
    DROP INDEX uk_wallet_txn_order_debit,
    ADD COLUMN order_movement_key BIGINT
        GENERATED ALWAYS AS (CASE WHEN kind IN ('ORDER_PAYMENT', 'ORDER_REFUND')
                                  THEN supplier_order_id END) STORED,
    ADD CONSTRAINT uk_wallet_txn_order_movement UNIQUE (order_movement_key, direction);

-- Sending wallet money back to a card is money leaving the platform, so it has
-- its own permission rather than borrowing "make payment": a Purchase Manager can
-- pay for an order, and whether they can also empty the wallet is the owner's
-- call. Granted by default to the roles that already handle the outlet's money.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('WALLET_WITHDRAW', 'Withdraw from wallet', 'RESTAURANT',
 'Send wallet money back to the card or bank it came from', NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'WALLET_WITHDRAW'
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN', 'REST_PURCHASE_MANAGER', 'REST_FINANCE_STAFF');
