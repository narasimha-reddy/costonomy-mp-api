-- Bound delivery polling/recovery work and make the event timelines use their chronological order.

ALTER TABLE delivery
    ADD COLUMN last_polled_at TIMESTAMP(6) NULL,
    ADD COLUMN poll_claimed_at TIMESTAMP(6) NULL,
    ADD COLUMN poll_claim_token VARCHAR(36) NULL,
    ADD INDEX ix_delivery_poll_due (mode, status, last_polled_at, id, poll_claimed_at),
    ADD INDEX ix_delivery_status_updated (status, updated_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery
    ADD INDEX ix_food_delivery_retry (status, updated_at, id),
    ADD INDEX ix_food_delivery_assignment_deadline (status, assignment_deadline, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE delivery_provider_attempt
    ADD INDEX ix_delivery_attempt_outcome_started (outcome, started_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery_attempt
    ADD INDEX ix_food_attempt_outcome_started (outcome, started_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE delivery_event
    DROP INDEX ix_delivery_event_delivery,
    ADD INDEX ix_delivery_event_timeline (delivery_id, occurred_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery_event
    DROP INDEX ix_food_event_delivery,
    ADD INDEX ix_food_event_timeline (food_delivery_id, occurred_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE delivery_recovery_task
    MODIFY COLUMN idempotency_ref VARCHAR(255) NULL,
    ADD COLUMN claimed_at TIMESTAMP(6) NULL,
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD UNIQUE INDEX uk_delivery_recovery_idempotency (idempotency_ref),
    ADD INDEX ix_delivery_recovery_due (status, next_retry_at, id),
    ADD INDEX ix_delivery_recovery_stale_claim (status, claimed_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- Ensure the denormalized tenant scopes on each delivery match the authoritative parent row.
ALTER TABLE supplier_order
    ADD UNIQUE INDEX uk_supplier_order_delivery_scope (id, outlet_id, supplier_store_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE outlet
    ADD UNIQUE INDEX uk_outlet_restaurant_scope (id, restaurant_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE delivery
    ADD CONSTRAINT fk_delivery_order_scope
        FOREIGN KEY (supplier_order_id, outlet_id, supplier_store_id)
        REFERENCES supplier_order (id, outlet_id, supplier_store_id);

ALTER TABLE food_delivery
    ADD CONSTRAINT fk_food_delivery_outlet_restaurant_scope
        FOREIGN KEY (outlet_id, restaurant_id)
        REFERENCES outlet (id, restaurant_id);

ALTER TABLE delivery_location
    ADD INDEX ix_delivery_location_recorded (recorded_at),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE food_delivery_location
    ADD INDEX ix_food_location_recorded (recorded_at),
    ALGORITHM=INPLACE, LOCK=NONE;

-- Experiments are mutable definitions; evaluations and variants are append-only facts/configuration rows.
ALTER TABLE experiment
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ALGORITHM=INPLACE, LOCK=NONE;