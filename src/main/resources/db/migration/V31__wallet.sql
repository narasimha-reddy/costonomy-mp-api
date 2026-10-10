-- A prepaid balance an outlet can order against. D-091's follow-on.
--
-- Prepaid card and supplier credit were the only two ways to fund an order, and
-- both are somebody else's rail: a gateway that can decline, or a supplier who
-- has to extend terms first. A wallet is money the restaurant has already put
-- in, which makes it the one method that can settle an order the moment it is
-- created -- no checkout to complete, nothing to be declined by.
--
-- It is a real balance, not a bypass. An order funded from an empty wallet is an
-- order with nothing behind it, which is the case guardrail 16 exists for.

CREATE TABLE wallet (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id   BIGINT       NOT NULL,

    -- The single source of the balance. Derivable by summing the ledger, and
    -- deliberately not derived: the debit that funds an order has to be one
    -- atomic, conditional statement, and summing a ledger to decide whether it
    -- may proceed is the read-then-write that lets two orders spend the same
    -- rupee. The ledger explains this figure; it does not define it.
    balance     DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency    CHAR(3)      NOT NULL DEFAULT 'INR',
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',

    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version     BIGINT       NOT NULL DEFAULT 0,

    -- One wallet per outlet. Two would mean an outlet with two balances and no
    -- answer to "how much have we got".
    CONSTRAINT uk_wallet_outlet UNIQUE (outlet_id),
    CONSTRAINT fk_wallet_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),

    -- The invariant the conditional debit enforces, stated where it cannot be
    -- argued with. A wallet that can go negative is credit under another name.
    CONSTRAINT ck_wallet_balance CHECK (balance >= 0)
);

-- Why the balance is what it is. Append-only.
CREATE TABLE wallet_transaction (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    wallet_id         BIGINT       NOT NULL,

    -- The order this paid for, or null for a top-up.
    supplier_order_id BIGINT       NULL,

    -- DEBIT takes money out, CREDIT puts it in. Spelled from the wallet's point
    -- of view, which is the opposite of the restaurant's -- worth the comment,
    -- because "credit" in this table is not the credit in credit_agreement.
    direction         VARCHAR(16)  NOT NULL,
    amount            DECIMAL(19,4) NOT NULL,

    -- What the balance became. Stored so a statement can be read back without
    -- replaying every row before it, and so a disagreement between the ledger
    -- and the wallet is visible rather than inferred.
    balance_after     DECIMAL(19,4) NOT NULL,
    reason            VARCHAR(200) NULL,

    created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version           BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_wallet_txn_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
    CONSTRAINT fk_wallet_txn_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT ck_wallet_txn_amount CHECK (amount > 0),

    -- One debit per order. A retry that reached the database twice would
    -- otherwise charge a wallet twice for one order, and an idempotency key
    -- protects the request, not the row.
    CONSTRAINT uk_wallet_txn_order_debit UNIQUE (supplier_order_id, direction)
);

CREATE INDEX ix_wallet_txn_wallet ON wallet_transaction (wallet_id, created_at);
