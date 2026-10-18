-- V95 — Bounded reconciliation polling and lease claims for food deliveries (D-197).

ALTER TABLE food_delivery
    ADD COLUMN last_polled_at TIMESTAMP(6) NULL,
    ADD COLUMN poll_claimed_at TIMESTAMP(6) NULL,
    ADD COLUMN poll_claim_token VARCHAR(36) NULL,
    ADD INDEX ix_food_delivery_poll_due (status, last_polled_at, poll_claimed_at, id),
    ALGORITHM=INPLACE, LOCK=NONE;
