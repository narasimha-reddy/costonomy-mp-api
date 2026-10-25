-- A gateway payment that arrives when it can no longer be used is refunded to its source, and found again by a sweep
-- if the refund could not be made at once (D-203, D-197).

-- Food delivery: set once the captured payment of an unusable checkout (cancelled, expired, or paid twice) was refunded.
ALTER TABLE food_delivery
    ADD COLUMN gateway_refund_id    VARCHAR(200) NULL,
    ADD COLUMN gateway_refunded_at  TIMESTAMP(6) NULL;

-- Supplier funding: when the checkout was opened (the deduction can be created days earlier, so created_at says
-- nothing about it), and the refund of a payment that arrived after the settlement had already taken the charge.
ALTER TABLE supplier_deduction
    ADD COLUMN checkout_opened_at   TIMESTAMP(6) NULL,
    ADD COLUMN gateway_refund_id    VARCHAR(200) NULL,
    ADD COLUMN gateway_refunded_at  TIMESTAMP(6) NULL,
    ADD KEY ix_supplier_deduction_checkout (kind, status, checkout_opened_at);

UPDATE supplier_deduction SET checkout_opened_at = created_at WHERE provider_order_id IS NOT NULL;
