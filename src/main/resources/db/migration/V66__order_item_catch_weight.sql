-- V66: Add is_catch_weight to supplier_order_item so order line items carry catch-weight identity.
ALTER TABLE supplier_order_item
    ADD COLUMN is_catch_weight BOOLEAN NOT NULL DEFAULT FALSE AFTER requires_cold_chain;

-- Backfill from supplier_sku
UPDATE supplier_order_item soi
JOIN supplier_sku sku ON sku.id = soi.supplier_sku_id
SET soi.is_catch_weight = sku.is_catch_weight;
