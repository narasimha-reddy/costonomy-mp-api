-- Pack units become a closed vocabulary, and a container states what is in it.
--
-- Two changes, one reason. The unit is what makes a comparison a comparison: two
-- suppliers' paneer map to one canonical product so a restaurant can hold their
-- prices side by side, and that is only true if both quote the same measure. Free
-- text could not carry that -- `KG`, `Kg`, `kg`, `kgs` are four units to this
-- database and one to a person -- and a pack unit alone cannot carry it either,
-- because "1 PKT" says how the goods are bundled and nothing about how much is in
-- the bundle.
--
-- The vocabulary itself lives in `Unit` (Java) rather than in a CHECK constraint,
-- following D-009 and every status column here: adding a unit should be a code
-- change with tests, not a migration, and the column stays readable in a dump.

-- ── What is inside a container ──────────────────────────────────────────
--
-- Required when pack_unit is PKT, CASE, BULK, TIN or BUNDLE, and refused
-- otherwise: a SKU packed in KG already states its amount, and a second
-- statement of the same quantity is a second chance to disagree with itself.
-- Enforced in SupplierCatalogService, where the error can name the field.
ALTER TABLE supplier_sku
    ADD COLUMN measure_value DECIMAL(19,4) NULL AFTER pack_unit,
    ADD COLUMN measure_unit  VARCHAR(16)   NULL AFTER measure_value;

-- ── Normalise the spellings already stored ──────────────────────────────
--
-- `L` and `PIECE` predate the vocabulary and mean LTR and PC. They are updated
-- everywhere they appear, including on order, procurement and receiving lines.
--
-- Those three are transactional snapshots, and the rule against rewriting a
-- snapshot is about *values* -- a price, a quantity, a total -- because those
-- must reconstruct what was agreed. A unit's spelling is not a value: `L` and
-- `LTR` are the same litre. Leaving them would make a past order render `L`
-- while a new one renders `LTR`, which reads as two different units for no
-- reason, and would break any grouping by unit across time.
UPDATE canonical_product   SET base_unit = 'LTR' WHERE base_unit = 'L';
UPDATE canonical_product   SET base_unit = 'PC'  WHERE base_unit = 'PIECE';

UPDATE supplier_sku        SET pack_unit = 'LTR' WHERE pack_unit = 'L';
UPDATE supplier_sku        SET pack_unit = 'PC'  WHERE pack_unit = 'PIECE';

UPDATE requirement_item    SET unit = 'LTR' WHERE unit = 'L';
UPDATE requirement_item    SET unit = 'PC'  WHERE unit = 'PIECE';

UPDATE procurement_item    SET unit = 'LTR' WHERE unit = 'L';
UPDATE procurement_item    SET unit = 'PC'  WHERE unit = 'PIECE';

UPDATE supplier_order_item SET unit = 'LTR' WHERE unit = 'L';
UPDATE supplier_order_item SET unit = 'PC'  WHERE unit = 'PIECE';

UPDATE receiving_item      SET unit = 'LTR' WHERE unit = 'L';
UPDATE receiving_item      SET unit = 'PC'  WHERE unit = 'PIECE';
