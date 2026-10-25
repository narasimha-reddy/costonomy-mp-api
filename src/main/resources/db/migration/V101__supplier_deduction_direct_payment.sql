-- Direct payment of supplier-funded delivery (D-203):
-- Suppliers can pay Costonomy delivery fees directly via payment gateway (UPI/Razorpay)
-- for credit orders or when settling delivery fees directly.
ALTER TABLE supplier_deduction
    ADD COLUMN payment_id        VARCHAR(200) NULL,
    ADD COLUMN provider_order_id VARCHAR(200) NULL,
    ADD COLUMN paid_at           TIMESTAMP(6) NULL,
    ADD CONSTRAINT uk_supplier_deduction_payment UNIQUE (payment_id),
    ADD KEY ix_supplier_deduction_provider_order (provider_order_id);
