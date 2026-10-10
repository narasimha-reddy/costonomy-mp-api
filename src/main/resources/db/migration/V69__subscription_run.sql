-- Subscriptions generate orders through the normal funding path, one attempt per subscription per delivery date
-- (D-132).

-- 1. Orders left behind by the old generator: DRAFT, never funded (the wallet debit that failed returned
--    false and the transaction committed anyway). No money ever moved for them, and they would block their
--    delivery date through the unique key below, so they are cancelled with a reason.
UPDATE supplier_order
   SET status = 'CANCELLED', payment_status = 'FAILED', cancelled_at = NOW(6),
       rejection_reason = 'Subscription order was never funded'
 WHERE is_subscription_order = 1 AND status = 'DRAFT';

-- 2. Subscriptions on a payment method or delivery mode generation no longer accepts are paused, not rewritten:
--    the restaurant has to choose again. Their reason is kept in the notes.
UPDATE subscription
   SET status = 'PAUSED',
       notes = LEFT(CONCAT_WS(' | ', notes,
               'Paused by the system: subscriptions support WALLET or CREDIT payment and SUPPLIER_DELIVERY or PICKUP only'), 500)
 WHERE status = 'ACTIVE'
   AND (payment_method NOT IN ('WALLET', 'CREDIT')
        OR delivery_mode NOT IN ('SUPPLIER_DELIVERY', 'PICKUP'));

-- 3. One live order per subscription per delivery date, enforced by the database. Two generations racing
--    (two instances, a retry overlapping the scheduler) insert the same key and one fails before any money
--    moves. Same pattern as wallet_transaction.order_movement_key (V42): a generated column that is NULL
--    when the order does not count, and NULLs never collide.
ALTER TABLE supplier_order
    ADD COLUMN subscription_delivery_key BIGINT
        GENERATED ALWAYS AS (CASE WHEN subscription_id IS NOT NULL AND status <> 'CANCELLED'
                                  THEN subscription_id END) STORED,
    ADD CONSTRAINT uk_supplier_order_subscription_delivery
        UNIQUE (subscription_delivery_key, scheduled_delivery_date);

-- 4. What happened for each subscription and delivery date. The row for a failed attempt is written after
--    the attempt's transaction rolled back, so a failure is never lost with the order it failed to create.
CREATE TABLE subscription_run (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    subscription_id   BIGINT        NOT NULL,
    delivery_date     DATE          NOT NULL,
    outcome           VARCHAR(32)   NOT NULL,
    reason            VARCHAR(500)  NULL,
    supplier_order_id BIGINT        NULL,
    amount            DECIMAL(19,4) NULL,
    attempts          INT           NOT NULL DEFAULT 1,
    created_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_subscription_run_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id),
    CONSTRAINT fk_subscription_run_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT uk_subscription_run UNIQUE (subscription_id, delivery_date),
    CONSTRAINT ck_subscription_run_outcome CHECK (outcome IN
        ('GENERATED', 'SKIPPED_NO_OFFER', 'FUNDING_FAILED', 'SKIPPED_PAUSED', 'SKIPPED_INVALID')),
    CONSTRAINT ck_subscription_run_order CHECK (outcome <> 'GENERATED' OR supplier_order_id IS NOT NULL),
    KEY ix_subscription_run_date (delivery_date, outcome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
