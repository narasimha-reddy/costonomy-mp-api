-- V51: Real-world B2B food operations: Catch-weight reconciliation, doorstep verification, store MOV, and free delivery thresholds.
--
-- 1. Catch-weight items (meat, poultry, seafood, produce, paneer) vary by natural weight
--    at packing/dispatch. Dispatched weight differences trigger server-side delta adjustments.
-- 2. Doorstep verification: Chefs inspect crates at delivery, accepting good units and rejecting
--    damaged/spoiled lines before sign-off, issuing instant credit note / wallet refund.
-- 3. Store Minimum Order Value (MOV) & Free Delivery: Wholesalers require MOV to justify dispatch,
--    and incentivize bulk orders with free delivery tiers.

ALTER TABLE supplier_sku
    ADD COLUMN is_catch_weight BOOLEAN NOT NULL DEFAULT FALSE AFTER grade;

ALTER TABLE supplier_order_item
    ADD COLUMN dispatched_weight DECIMAL(19,4) NULL AFTER fulfilled_quantity,
    ADD COLUMN weighed_at TIMESTAMP(6) NULL AFTER dispatched_weight,
    ADD COLUMN weight_delta_amount DECIMAL(19,4) NULL AFTER weighed_at,
    ADD COLUMN doorstep_accepted_qty DECIMAL(19,4) NULL AFTER weight_delta_amount,
    ADD COLUMN doorstep_rejected_qty DECIMAL(19,4) NULL AFTER doorstep_accepted_qty,
    ADD COLUMN doorstep_rejection_reason VARCHAR(64) NULL AFTER doorstep_rejected_qty,
    ADD COLUMN doorstep_refund_amount DECIMAL(19,4) NULL AFTER doorstep_rejection_reason;

ALTER TABLE supplier_order
    ADD COLUMN weight_adjustment_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000 AFTER accepted_amount,
    ADD COLUMN doorstep_refund_amount DECIMAL(19,4) NOT NULL DEFAULT 0.0000 AFTER weight_adjustment_amount,
    ADD COLUMN final_payable_amount DECIMAL(19,4) NULL AFTER doorstep_refund_amount;

ALTER TABLE supplier_delivery_policy
    ADD COLUMN min_order_value DECIMAL(19,4) NOT NULL DEFAULT 0.0000 AFTER own_delivery_fee,
    ADD COLUMN free_delivery_threshold DECIMAL(19,4) NULL AFTER min_order_value;

ALTER TABLE wallet_transaction
    DROP INDEX uk_wallet_txn_order_debit;
