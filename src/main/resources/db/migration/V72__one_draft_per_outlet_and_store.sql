-- One basket per outlet and supplier store, enforced by the database (D-137).
--
-- The application looked for an existing draft and created one if it found none, with nothing stopping two simultaneous
-- first additions from both creating one. Nothing is in production, so any duplicates are merged here: lines move to
-- the oldest draft unless it already has that SKU, and the later drafts are removed.
UPDATE intent_item i
  JOIN intent d ON d.id = i.intent_id AND d.status = 'DRAFT'
  JOIN (SELECT outlet_id, supplier_store_id, MIN(id) AS keep_id
          FROM intent WHERE status = 'DRAFT' GROUP BY outlet_id, supplier_store_id HAVING COUNT(*) > 1) k
    ON k.outlet_id = d.outlet_id AND k.supplier_store_id = d.supplier_store_id AND d.id <> k.keep_id
   SET i.intent_id = k.keep_id
 WHERE NOT EXISTS (SELECT 1 FROM (SELECT intent_id, supplier_sku_id FROM intent_item) x
                    WHERE x.intent_id = k.keep_id AND x.supplier_sku_id = i.supplier_sku_id);

DELETE i FROM intent_item i
  JOIN intent d ON d.id = i.intent_id AND d.status = 'DRAFT'
  JOIN (SELECT outlet_id, supplier_store_id, MIN(id) AS keep_id
          FROM intent WHERE status = 'DRAFT' GROUP BY outlet_id, supplier_store_id HAVING COUNT(*) > 1) k
    ON k.outlet_id = d.outlet_id AND k.supplier_store_id = d.supplier_store_id AND d.id <> k.keep_id;

DELETE d FROM intent d
  JOIN (SELECT outlet_id, supplier_store_id, MIN(id) AS keep_id
          FROM intent WHERE status = 'DRAFT' GROUP BY outlet_id, supplier_store_id HAVING COUNT(*) > 1) k
    ON k.outlet_id = d.outlet_id AND k.supplier_store_id = d.supplier_store_id AND d.id <> k.keep_id
 WHERE d.status = 'DRAFT';

ALTER TABLE intent
    ADD COLUMN draft_key VARCHAR(48)
        GENERATED ALWAYS AS (CASE WHEN status = 'DRAFT' THEN CONCAT(outlet_id, ':', supplier_store_id) END) STORED;
ALTER TABLE intent ADD CONSTRAINT uk_intent_one_draft UNIQUE (draft_key);
