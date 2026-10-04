-- V63 (D-124): catch-weight settles at dispatch.
--
-- A weighed line now carries the quantity the buyer is billed for, separately from the raw
-- scale reading. billable = min(reading, accepted): overweight within the tolerance band is the
-- supplier's giveaway, so the platform and the buyer never pay for weight nobody agreed to.
ALTER TABLE supplier_order_item
    ADD COLUMN billable_quantity DECIMAL(19,4) NULL AFTER dispatched_weight;

-- Lines weighed before this column existed: the billed quantity is whatever the stored line
-- value says it was, which is exact whatever clamp the old code applied.
UPDATE supplier_order_item
   SET billable_quantity = ROUND(line_item_value / unit_price_snapshot, 4)
 WHERE dispatched_weight IS NOT NULL
   AND unit_price_snapshot > 0;

-- V62 backfilled the flag from the SKU, but order lines created since by a path that did not set
-- it (subscriptions) are still false.
UPDATE supplier_order_item soi
  JOIN supplier_sku sku ON sku.id = soi.supplier_sku_id
   SET soi.is_catch_weight = sku.is_catch_weight
 WHERE soi.is_catch_weight = FALSE
   AND sku.is_catch_weight = TRUE;

-- What the buyer finally owes is never negative. The application refuses to write a negative
-- figure; this is the backstop. It fails the migration, loudly, if an existing row already is
-- negative, which is a record that needs a person's look and not a silent clamp.
ALTER TABLE supplier_order
    ADD CONSTRAINT ck_order_final_payable CHECK (final_payable_amount IS NULL OR final_payable_amount >= 0);
