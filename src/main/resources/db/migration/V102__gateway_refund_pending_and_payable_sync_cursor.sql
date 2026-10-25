-- D-206: a gateway refund the provider has accepted but not completed is PENDING, not done; and the courier payable
-- sync walks the deliveries by a durable cursor instead of rescanning the newest few.
--
-- Backward compatible: one widened CHECK, one new column with a default, one new table, two indexes. Nothing is
-- rewritten and no existing row changes.

-- 1. PENDING: accepted by the gateway (its refund id is stored), not yet COMPLETED or FAILED. Reconciled until it is.
--    send_round numbers the attempts the gateway has *recorded as failed*: a new round is a new provider idempotency
--    key and receipt, because the provider would otherwise answer a retry with the failed refund it already holds.
ALTER TABLE gateway_refund DROP CHECK ck_gateway_refund_status;
ALTER TABLE gateway_refund
    ADD COLUMN send_round INT NOT NULL DEFAULT 0 AFTER lookup_count,
    ADD CONSTRAINT ck_gateway_refund_status CHECK (status IN
        ('REQUESTED', 'SENDING', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXHAUSTED', 'AMBIGUOUS'));

-- 2. Where the payable sync has got to, per source, in (updated_at, id) order of the source's deliveries. A delivery
--    that changes moves to the end of that order, so every change after the cursor is visited, oldest first, and
--    nothing already visited is looked at again.
CREATE TABLE courier_payable_sync_cursor (
    source_type     VARCHAR(32)  PRIMARY KEY,                -- SUPPLIER_DELIVERY | FOOD_DELIVERY
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT '1970-01-02 00:00:00',
    last_id         BIGINT       NOT NULL DEFAULT 0,
    last_run_at     TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO courier_payable_sync_cursor (source_type) VALUES ('SUPPLIER_DELIVERY'), ('FOOD_DELIVERY');

-- 3. The cursor reads deliveries in (updated_at, id) order.
ALTER TABLE delivery      ADD KEY ix_delivery_updated (updated_at, id);
ALTER TABLE food_delivery ADD KEY ix_food_delivery_updated (updated_at, id);
