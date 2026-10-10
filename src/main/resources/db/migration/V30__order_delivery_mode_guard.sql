-- The constraints V29's backfill has to satisfy. D-091, and D-089's lesson: a
-- migration that fills a column and tightens it in one step passes against an
-- empty database and fails against a real one.

-- Every order now states how its goods travel. V29 gave the existing ones
-- COSTONOMY_DELIVERY and IntentOrderCreator will not create one without a mode.
ALTER TABLE supplier_order
    MODIFY COLUMN delivery_mode VARCHAR(32) NOT NULL;

-- The supplier's single mode is superseded by the set they can offer; the
-- restaurant makes the choice. V29 copied the old value into the new column.
ALTER TABLE intent_acceptance
    DROP COLUMN delivery_mode;

-- No CHECK on delivery_mode, cancelled_by or any other enum column, for the
-- reason D-009 gives: these are state machines that gain members, and a CHECK
-- makes each new one an ALTER TABLE on a large table rather than a code change.
-- Twenty-five status columns are already guarded this way -- by the enum, at the
-- boundary -- and one exception would be the inconsistency, not the fix.

-- Reading an outlet's orders by how they travel: the pickup queue is a different
-- screen from the delivery queue, for both sides.
CREATE INDEX ix_supplier_order_mode ON supplier_order (supplier_store_id, delivery_mode, status);
