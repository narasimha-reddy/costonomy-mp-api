-- V7 — Starter canonical catalog.
--
-- Real platform reference data, not test fixtures. Canonical products are
-- platform-owned (doc 01 §7) and a marketplace with an empty catalog cannot be
-- used at all: a supplier uploading their SKUs needs canonical products to map
-- onto, and that mapping is what makes offers comparable.
--
-- This is a starting set of common Indian restaurant inputs, drawn from the
-- categories doc 48 names. Operations curates it from here.
--
-- Test fixtures — restaurants, suppliers, orders — are deliberately NOT here.
-- Doc 10 §12 requires seed data to be deterministic and clearly non-production,
-- which is a different concern from reference data and belongs in a dev seeder.
--
-- Aliases matter as much as the products. Doc 07 §2 forbids inventing semantic
-- mappings without a configured alias, so a term absent from
-- canonical_product_alias is a term search genuinely does not know.

INSERT INTO product_category (name, slug, display_order, status, created_at, updated_at) VALUES
('Dairy',            'dairy',            10, 'ACTIVE', NOW(6), NOW(6)),
('Meat & Poultry',   'meat-poultry',     20, 'ACTIVE', NOW(6), NOW(6)),
('Vegetables',       'vegetables',       30, 'ACTIVE', NOW(6), NOW(6)),
('Grains & Pulses',  'grains-pulses',    40, 'ACTIVE', NOW(6), NOW(6)),
('Oils & Fats',      'oils-fats',        50, 'ACTIVE', NOW(6), NOW(6)),
('Spices & Masala',  'spices-masala',    60, 'ACTIVE', NOW(6), NOW(6)),
('Sugar & Sweeteners','sugar-sweeteners',70, 'ACTIVE', NOW(6), NOW(6)),
('Beverages',        'beverages',        80, 'ACTIVE', NOW(6), NOW(6)),
('Packaging',        'packaging',        90, 'ACTIVE', NOW(6), NOW(6)),
('Cleaning & Hygiene','cleaning-hygiene',100,'ACTIVE', NOW(6), NOW(6));

INSERT INTO canonical_product
    (category_id, name, normalized_name, base_unit, base_pack_size, status, created_at, updated_at)
SELECT c.id, v.name, v.normalized_name, v.base_unit, v.base_pack_size, 'ACTIVE', NOW(6), NOW(6)
FROM (
    SELECT 'dairy' AS slug, 'Paneer'              AS name, 'paneer'              AS normalized_name, 'KG' AS base_unit, 1    AS base_pack_size UNION ALL
    SELECT 'dairy',          'Fresh Cream',            'fresh cream',            'L',  1    UNION ALL
    SELECT 'dairy',          'Processed Cheese',       'processed cheese',       'KG', 1    UNION ALL
    SELECT 'dairy',          'Curd',                   'curd',                   'KG', 1    UNION ALL
    SELECT 'dairy',          'Butter',                 'butter',                 'KG', 1    UNION ALL
    SELECT 'dairy',          'Toned Milk',             'toned milk',             'L',  1    UNION ALL
    SELECT 'meat-poultry',   'Chicken Curry Cut',      'chicken curry cut',      'KG', 1    UNION ALL
    SELECT 'meat-poultry',   'Chicken Boneless',       'chicken boneless',       'KG', 1    UNION ALL
    SELECT 'meat-poultry',   'Mutton Curry Cut',       'mutton curry cut',       'KG', 1    UNION ALL
    SELECT 'vegetables',     'Onion',                  'onion',                  'KG', 1    UNION ALL
    SELECT 'vegetables',     'Tomato',                 'tomato',                 'KG', 1    UNION ALL
    SELECT 'vegetables',     'Potato',                 'potato',                 'KG', 1    UNION ALL
    SELECT 'vegetables',     'Ginger',                 'ginger',                 'KG', 1    UNION ALL
    SELECT 'vegetables',     'Garlic',                 'garlic',                 'KG', 1    UNION ALL
    SELECT 'grains-pulses',  'Basmati Rice',           'basmati rice',           'KG', 25   UNION ALL
    SELECT 'grains-pulses',  'Sona Masoori Rice',      'sona masoori rice',      'KG', 25   UNION ALL
    SELECT 'grains-pulses',  'Refined Wheat Flour',    'refined wheat flour',    'KG', 25   UNION ALL
    SELECT 'grains-pulses',  'Whole Wheat Flour',      'whole wheat flour',      'KG', 25   UNION ALL
    SELECT 'grains-pulses',  'Toor Dal',               'toor dal',               'KG', 25   UNION ALL
    SELECT 'grains-pulses',  'Chana Dal',              'chana dal',              'KG', 25   UNION ALL
    SELECT 'oils-fats',      'Refined Sunflower Oil',  'refined sunflower oil',  'L',  15   UNION ALL
    SELECT 'oils-fats',      'Groundnut Oil',          'groundnut oil',          'L',  15   UNION ALL
    SELECT 'oils-fats',      'Ghee',                   'ghee',                   'KG', 1    UNION ALL
    SELECT 'spices-masala',  'Red Chilli Powder',      'red chilli powder',      'KG', 1    UNION ALL
    SELECT 'spices-masala',  'Turmeric Powder',        'turmeric powder',        'KG', 1    UNION ALL
    SELECT 'spices-masala',  'Coriander Powder',       'coriander powder',       'KG', 1    UNION ALL
    SELECT 'spices-masala',  'Garam Masala',           'garam masala',           'KG', 1    UNION ALL
    SELECT 'sugar-sweeteners','Sugar',                 'sugar',                  'KG', 50   UNION ALL
    SELECT 'sugar-sweeteners','Jaggery',               'jaggery',                'KG', 1    UNION ALL
    SELECT 'packaging',      'Aluminium Foil Container','aluminium foil container','PIECE', 500 UNION ALL
    SELECT 'packaging',      'Paper Carry Bag',        'paper carry bag',        'PIECE', 500 UNION ALL
    SELECT 'cleaning-hygiene','Dishwash Liquid',       'dishwash liquid',        'L',  5    UNION ALL
    SELECT 'cleaning-hygiene','Floor Cleaner',         'floor cleaner',          'L',  5    UNION ALL
    SELECT 'cleaning-hygiene','Hand Wash',             'hand wash',              'L',  5
) v
JOIN product_category c ON c.slug = v.slug;

-- The restaurant's own vocabulary. A kitchen orders "dahi", not "curd", and
-- "maida", not "refined wheat flour".
INSERT INTO canonical_product_alias (canonical_product_id, alias, normalized_alias, created_at)
SELECT p.id, v.alias, v.normalized_alias, NOW(6)
FROM (
    SELECT 'curd'                  AS product, 'Dahi'             AS alias, 'dahi'             AS normalized_alias UNION ALL
    SELECT 'curd',                            'Yoghurt',                    'yoghurt'           UNION ALL
    SELECT 'refined wheat flour',             'Maida',                      'maida'             UNION ALL
    SELECT 'whole wheat flour',               'Atta',                       'atta'              UNION ALL
    SELECT 'toned milk',                      'Milk',                       'milk'              UNION ALL
    SELECT 'ghee',                            'Clarified Butter',           'clarified butter'  UNION ALL
    SELECT 'red chilli powder',               'Lal Mirch Powder',           'lal mirch powder'  UNION ALL
    SELECT 'red chilli powder',               'Mirchi Powder',              'mirchi powder'     UNION ALL
    SELECT 'turmeric powder',                 'Haldi',                      'haldi'             UNION ALL
    SELECT 'coriander powder',                'Dhania Powder',              'dhania powder'     UNION ALL
    SELECT 'onion',                           'Pyaz',                       'pyaz'              UNION ALL
    SELECT 'tomato',                          'Tamatar',                    'tamatar'           UNION ALL
    SELECT 'potato',                          'Aloo',                       'aloo'              UNION ALL
    SELECT 'ginger',                          'Adrak',                      'adrak'             UNION ALL
    SELECT 'garlic',                          'Lehsun',                     'lehsun'            UNION ALL
    SELECT 'toor dal',                        'Arhar Dal',                  'arhar dal'         UNION ALL
    SELECT 'toor dal',                        'Kandi Pappu',                'kandi pappu'       UNION ALL
    SELECT 'chana dal',                       'Bengal Gram',                'bengal gram'       UNION ALL
    SELECT 'sona masoori rice',               'Sona Masuri',                'sona masuri'       UNION ALL
    SELECT 'refined sunflower oil',           'Sunflower Oil',              'sunflower oil'     UNION ALL
    SELECT 'refined sunflower oil',           'Cooking Oil',                'cooking oil'       UNION ALL
    SELECT 'processed cheese',                'Cheese',                     'cheese'            UNION ALL
    SELECT 'fresh cream',                     'Cream',                      'cream'             UNION ALL
    SELECT 'chicken curry cut',               'Chicken',                    'chicken'           UNION ALL
    SELECT 'dishwash liquid',                 'Dishwashing Liquid',         'dishwashing liquid'UNION ALL
    SELECT 'aluminium foil container',        'Foil Container',             'foil container'
) v
JOIN canonical_product p ON p.normalized_name = v.product;

-- Brands common enough that a supplier importing a catalog will reference them.
INSERT INTO brand (name, normalized_name, status, created_at, updated_at) VALUES
('Amul',       'amul',       'ACTIVE', NOW(6), NOW(6)),
('Britannia',  'britannia',  'ACTIVE', NOW(6), NOW(6)),
('Mother Dairy','mother dairy','ACTIVE', NOW(6), NOW(6)),
('Nestle',     'nestle',     'ACTIVE', NOW(6), NOW(6)),
('Fortune',    'fortune',    'ACTIVE', NOW(6), NOW(6)),
('Saffola',    'saffola',    'ACTIVE', NOW(6), NOW(6)),
('India Gate', 'india gate', 'ACTIVE', NOW(6), NOW(6)),
('Daawat',     'daawat',     'ACTIVE', NOW(6), NOW(6)),
('Aashirvaad', 'aashirvaad', 'ACTIVE', NOW(6), NOW(6)),
('MDH',        'mdh',        'ACTIVE', NOW(6), NOW(6)),
('Everest',    'everest',    'ACTIVE', NOW(6), NOW(6)),
('Vim',        'vim',        'ACTIVE', NOW(6), NOW(6)),
('Lizol',      'lizol',      'ACTIVE', NOW(6), NOW(6));
