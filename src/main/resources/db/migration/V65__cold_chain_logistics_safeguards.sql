-- V65: Cold-chain logistics safeguards and perishable transit spoilage protection.
--
-- 1. Cold-chain items (fresh dairy, raw poultry/meat, seafood, frozen items) spoil rapidly
--    if transported via open 2-wheeler couriers without insulated cold boxes.
-- 2. Mark products and SKUs with requires_cold_chain to enforce vehicle gating.
-- 3. Restrict delivery carrier options to insulated 3W cargo, 4W refrigerated/insulated vans,
--    or Supplier's own cold-chain fleet, disallowing open 2-wheeler bikes.

ALTER TABLE canonical_product
    ADD COLUMN requires_cold_chain BOOLEAN NOT NULL DEFAULT FALSE AFTER hsn_code;

ALTER TABLE supplier_sku
    ADD COLUMN requires_cold_chain BOOLEAN NOT NULL DEFAULT FALSE AFTER is_catch_weight;

ALTER TABLE supplier_order_item
    ADD COLUMN requires_cold_chain BOOLEAN NOT NULL DEFAULT FALSE AFTER hsn_code;

ALTER TABLE supplier_order
    ADD COLUMN has_cold_chain_items BOOLEAN NOT NULL DEFAULT FALSE AFTER final_payable_amount;

ALTER TABLE delivery
    ADD COLUMN requires_cold_chain BOOLEAN NOT NULL DEFAULT FALSE AFTER vehicle_type;
