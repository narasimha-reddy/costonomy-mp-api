-- Restaurant-side payment gateway for food delivery (D-197):
-- Allows restaurants to pay food delivery fees directly via Payment Gateway (UPI/Razorpay)
-- as an alternative to deducting from their pre-funded wallet.
ALTER TABLE food_delivery
    ADD COLUMN payment_method    VARCHAR(32)  NOT NULL DEFAULT 'WALLET',
    ADD COLUMN payment_id        VARCHAR(200) NULL,
    ADD COLUMN provider_order_id VARCHAR(200) NULL,
    ADD COLUMN paid_at           TIMESTAMP(6) NULL,
    ADD KEY ix_food_delivery_provider_order (provider_order_id);
