-- When a supplier's own delivery was made free because the goods reached the store's free-delivery threshold (D-203),
-- the threshold is kept on the answer. A restaurant may order less than was offered; delivery is free only while the
-- order still reaches it. Null for every other offer, which keep the amount the supplier named.
ALTER TABLE intent_acceptance
    ADD COLUMN delivery_free_from_goods DECIMAL(19,4) NULL,
    ADD CONSTRAINT ck_intent_acceptance_free_from CHECK (delivery_free_from_goods IS NULL OR delivery_free_from_goods > 0);
