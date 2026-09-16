-- Record which side of the trade a notification was written for.
--
-- It cannot be derived from the event type, and the attempt to is what this
-- column replaces. `SupplierOrderExpired` has a rule for *both* sides -- the
-- restaurant is told their order expired, the supplier that they missed it --
-- so looking the event up in the rule catalogue returns whichever rule happens to
-- be declared first, and the other side's copy is mislabelled. The relay is the
-- only place that knows which rule produced which row, so it is the only place
-- that can record this.
--
-- The client needs it because it cannot work the answer out either: routing by
-- the *viewer's* role sends a supplier to a restaurant URL, and someone who is
-- both a supplier and a restaurant has no single role to route by.
ALTER TABLE notification
    ADD COLUMN audience VARCHAR(32) NULL AFTER target_id;

-- Backfill what can be known. A notification whose event has exactly one rule is
-- unambiguous, and that is every event but one.
UPDATE notification SET audience = 'SUPPLIER_STORE'
 WHERE event_type IN (
    'SupplierOrderCreated', 'SupplierOrderReleased', 'CreditRequested',
    'CreditRequestModified', 'CreditAgreementAccepted', 'DisputeCreated',
    'DisputeResolved', 'RatingSubmitted', 'SettlementPaid');

UPDATE notification SET audience = 'OUTLET'
 WHERE audience IS NULL
   AND event_type <> 'SupplierOrderExpired';

-- The ambiguous one, decided by what the recipient actually is. Someone holding a
-- supplier grant was told as a supplier; everyone else was told as a buyer.
UPDATE notification n SET n.audience = 'SUPPLIER_STORE'
 WHERE n.event_type = 'SupplierOrderExpired'
   AND EXISTS (
       SELECT 1 FROM user_role ur
        WHERE ur.user_id = n.user_id
          AND ur.status = 'ACTIVE'
          AND ur.scope_type IN ('SUPPLIER', 'SUPPLIER_STORE'));

UPDATE notification SET audience = 'OUTLET' WHERE audience IS NULL;
