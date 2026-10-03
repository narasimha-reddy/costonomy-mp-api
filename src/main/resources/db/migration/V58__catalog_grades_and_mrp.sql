-- D-109: Support item multi-brand catalog options with grades, MRP benchmarks and discount displays.
--
-- A product (e.g. Paneer, Elaichi, Floor Cleaner, Basmati Biryani Rice) supports
-- multiple brands as well as different grades (e.g., "Grade A", "Grade B", "Grade C",
-- "Premium", "Standard", or loose/unbranded grades like "Elaichi Grade A").
--
-- Each brand/grade variant carries an MRP (maximum retail price / benchmark price)
-- against the supplier's selling price, enabling calculated server-side discounts (e.g. "Save ₹80", "16% OFF").
-- For loose or unbranded commodities without printed packaging, MRP is optional.

ALTER TABLE supplier_sku
    ADD COLUMN grade VARCHAR(100) NULL AFTER brand_id,
    ADD COLUMN mrp DECIMAL(19,4) NULL AFTER pack_unit;

ALTER TABLE supplier_offer
    ADD COLUMN mrp DECIMAL(19,4) NULL AFTER selling_price;

CREATE INDEX ix_sku_grade ON supplier_sku (supplier_store_id, canonical_product_id, grade);
