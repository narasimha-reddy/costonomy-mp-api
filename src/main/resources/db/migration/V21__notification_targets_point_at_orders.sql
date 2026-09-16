-- Point existing delivery and dispute notifications at the order they concern.
--
-- `Notification.targetId` was the event's aggregate id, so a delivery event wrote
-- the *delivery* id. Neither side has a screen keyed by one: the restaurant tracks
-- `/tracking/{orderId}` and the supplier opens `/orders/{orderId}`. So a
-- notification about delivery 2 opened order 2 -- a different restaurant's order,
-- reached by a link that looked like it worked.
--
-- The rules now name `supplierOrderId` as the target field, which fixes every
-- notification written from here on. These two statements fix the ones already
-- sent, because an inbox row that opens the wrong order is worse than one that
-- opens nothing: the first is a link someone follows and believes.
--
-- Joined rather than sub-selected so a row whose delivery or dispute has since
-- been removed is left alone rather than set to NULL.
UPDATE notification n
    JOIN delivery d ON d.id = n.target_id
   SET n.target_id = d.supplier_order_id
 WHERE n.target_type = 'DELIVERY'
   AND d.supplier_order_id IS NOT NULL;

UPDATE notification n
    JOIN dispute x ON x.id = n.target_id
   SET n.target_id = x.supplier_order_id
 WHERE n.target_type = 'DISPUTE'
   AND x.supplier_order_id IS NOT NULL;
