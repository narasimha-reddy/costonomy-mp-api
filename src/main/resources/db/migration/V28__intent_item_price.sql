-- A request line carries the price it is asked at.
--
-- V23 deliberately kept prices off the intent: the supplier's reply set them, so
-- anything the basket showed was a guess. That made the basket unusable for its
-- actual purpose -- a kitchen with a budget cannot send a request blind -- and
-- the honest label for the figure was "approximately", which is not something to
-- show somebody about money they are about to commit.
--
-- So the price moves onto the line and is **locked when the request is sent**.
-- The restaurant sees a real figure, confirms any change since they added the
-- item, and the supplier's reply then confirms that price or declines the line.
-- One number from basket to order, which is what makes it safe to show without a
-- tilde in front of it.
--
-- Still nothing financial in the sense V23 meant: no payment, no reservation, no
-- commission, no GMV. A price on a line is a quote, and a quote the restaurant
-- never orders against costs nobody anything.
ALTER TABLE intent_item
    ADD COLUMN supplier_offer_id  BIGINT        NULL AFTER supplier_sku_id,
    ADD COLUMN unit_price_snapshot DECIMAL(19,4) NULL AFTER unit,
    ADD COLUMN gst_rate_snapshot   DECIMAL(9,4)  NULL AFTER unit_price_snapshot,
    ADD CONSTRAINT fk_intent_item_offer FOREIGN KEY (supplier_offer_id)
        REFERENCES supplier_offer (id);

-- Lines that predate this column get today's price, which is exactly what the
-- basket was already showing them at. Nothing is invented: a draft's snapshot is
-- a baseline for detecting change, and it is re-confirmed at send anyway.
UPDATE intent_item i
    JOIN supplier_offer o
      ON o.supplier_sku_id = i.supplier_sku_id
     AND o.status = 'ACTIVE'
   SET i.supplier_offer_id   = o.id,
       i.unit_price_snapshot = o.selling_price,
       i.gst_rate_snapshot   = o.gst_rate
 WHERE i.unit_price_snapshot IS NULL;
