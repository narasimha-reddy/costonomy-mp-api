-- V68 (D-129): every reduction of what an order comes to, after it settles at ready, is a row.
--
-- final_payable_amount used to be a bare number written in two places with no history. After ready it is now
-- accepted_amount less the sum of an order's adjustments, written in the same transaction as the funding call
-- that moved the money. A row is never deleted; only PENDING_CAPTURE -> APPLIED can change.
CREATE TABLE order_adjustment (
    id                     BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_order_id      BIGINT        NOT NULL,
    reason                 VARCHAR(32)   NOT NULL,   -- WEIGHT_SETTLEMENT | DOORSTEP_REJECTION
    amount                 DECIMAL(19,4) NOT NULL,
    -- The part of amount a credit invoice could not come down by because it was already repaid: settled
    -- directly between the restaurant and the supplier (D-129). Always 0 for card and wallet.
    settled_outside_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    idempotency_key        VARCHAR(80)   NOT NULL,
    payment_method         VARCHAR(32)   NOT NULL,
    funding_reference      VARCHAR(120)  NULL,       -- refund:{id} | wallet:{reference} | credit_invoice:{id} | payment:{id}
    status                 VARCHAR(32)   NOT NULL,   -- APPLIED | PENDING_CAPTURE
    note                   VARCHAR(500)  NULL,
    created_by             BIGINT        NULL,
    applied_at             TIMESTAMP(6)  NULL,
    created_at             TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_adjustment_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_order_adjustment_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_order_adjustment_key UNIQUE (idempotency_key),
    CONSTRAINT uk_order_adjustment_order_reason UNIQUE (supplier_order_id, reason),
    CONSTRAINT ck_order_adjustment_amount CHECK (amount > 0),
    CONSTRAINT ck_order_adjustment_outside CHECK (settled_outside_amount >= 0 AND settled_outside_amount <= amount),
    CONSTRAINT ck_order_adjustment_reason CHECK (reason IN ('WEIGHT_SETTLEMENT', 'DOORSTEP_REJECTION')),
    CONSTRAINT ck_order_adjustment_status CHECK (status IN ('APPLIED', 'PENDING_CAPTURE')),
    CONSTRAINT ck_order_adjustment_applied CHECK ((status = 'APPLIED') = (applied_at IS NOT NULL)),
    KEY ix_order_adjustment_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Orders past ready always carry a final payable from now on. updated_at = updated_at keeps the settlement
-- window, which reads updated_at as the completion time, where it was.
UPDATE supplier_order
   SET final_payable_amount = accepted_amount - coalesce(weight_adjustment_amount, 0) - coalesce(doorstep_refund_amount, 0),
       updated_at = updated_at
 WHERE final_payable_amount IS NULL
   AND status IN ('READY_FOR_PICKUP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'COMPLETED');

-- Rows for what has already happened. A legacy negative weight_adjustment_amount (the old over-weight
-- surcharge) is deliberately not backfilled: list them with
--   select id from supplier_order where weight_adjustment_amount < 0
-- and decide each with a person.
INSERT INTO order_adjustment (supplier_order_id, reason, amount, idempotency_key, payment_method,
                              funding_reference, status, note, applied_at)
SELECT so.id, 'WEIGHT_SETTLEMENT', so.weight_adjustment_amount, concat('weight-settle-', so.id),
       so.payment_method, NULL, 'APPLIED', 'Backfilled by V68', so.updated_at
  FROM supplier_order so
 WHERE so.weight_adjustment_amount > 0
   AND so.status IN ('READY_FOR_PICKUP', 'OUT_FOR_DELIVERY', 'DELIVERED', 'COMPLETED');

INSERT INTO order_adjustment (supplier_order_id, reason, amount, idempotency_key, payment_method,
                              funding_reference, status, note, applied_at)
SELECT so.id, 'DOORSTEP_REJECTION', so.doorstep_refund_amount, concat('doorstep-', so.id), so.payment_method,
       (SELECT concat('refund:', r.id) FROM refund r WHERE r.idempotency_key = concat('doorstep-', so.id)),
       'APPLIED', 'Backfilled by V68', so.updated_at
  FROM supplier_order so
 WHERE so.doorstep_refund_amount > 0;
