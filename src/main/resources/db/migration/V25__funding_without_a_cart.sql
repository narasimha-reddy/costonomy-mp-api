-- Funding an order that never had a cart.
--
-- V23 and V24 made the order and its lines independent of `procurement`, but the
-- two funding paths still demanded one. Both carry a denormalised
-- `procurement_id` copied off the order at funding time, NOT NULL — so creating
-- an order from an accepted intent got as far as arranging payment and then
-- failed on a column that describes a cart the order never came from.
--
-- Payment is per supplier order (D-010) and credit is reserved per supplier
-- order, so neither actually needs the cart to do its job. The column is a
-- convenience for grouping a multi-supplier checkout — `payment` is still
-- indexed by it, and `findByProcurementId` still answers "what did that one
-- checkout charge". For an intent-built order there is nothing to group: one
-- request is one supplier is one order, and `intent_order_link` records where it
-- came from.
--
-- The foreign keys stay. A null is exempt, so cart-built orders keep exactly the
-- referential guarantee they had.
ALTER TABLE payment MODIFY COLUMN procurement_id BIGINT NULL;
ALTER TABLE credit_reservation MODIFY COLUMN procurement_id BIGINT NULL;
