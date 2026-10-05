-- What the supplier offers for delivery on one answer (D-141): SELF_FREE (they deliver, no charge), SELF (they deliver at
-- their own fee) or COSTONOMY (Costonomy riders, requested once the order is Ready for Pickup). Null is an older answer,
-- which offers whatever the store's delivery policy enables.
ALTER TABLE intent_acceptance ADD COLUMN delivery_offer VARCHAR(16) NULL;
