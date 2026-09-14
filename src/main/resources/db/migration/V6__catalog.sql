-- V6 — Catalog.
--
-- Doc 01 §7, doc 02 §3–4. The split that defines this domain:
--
--   * Mandi owns **canonical product identity** — "Paneer, 1 kg" — and its image.
--     This is what makes two suppliers' products comparable at all.
--   * The supplier owns their **commercial SKU** — their code, their brand, their
--     pack — mapped to a canonical product.
--   * An **offer** is the price and availability of that SKU right now.
--
-- SKU and offer are separate tables. Doc 02 §9's representative DDL puts price on
-- supplier_sku, but §3 and §4 both list supplier_offer as its own table and §4
-- requires that "historical commercial values must not be overwritten when they
-- are referenced by a transaction". Effective-dated offers give that for free, and
-- give the price history doc 01 §26 wants for pricing intelligence. See
-- docs/DECISIONS.md D-012.

CREATE TABLE product_category (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id       BIGINT       NULL,
    name            VARCHAR(150) NOT NULL,
    slug            VARCHAR(150) NOT NULL,
    description     VARCHAR(500),
    image_url       VARCHAR(1000),
    display_order   INT          NOT NULL DEFAULT 100,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES product_category (id),
    CONSTRAINT uk_category_slug UNIQUE (slug),
    KEY ix_category_parent_status (parent_id, status, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE brand (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(200) NOT NULL,
    normalized_name VARCHAR(200) NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- Normalised, so "Amul", "AMUL" and "amul " are one brand rather than three
    -- competing for the same shelf.
    CONSTRAINT uk_brand_normalized UNIQUE (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Platform-owned comparable product identity. Doc 02 §4.
--
-- The unit of comparison. Two suppliers selling Amul and Britannia paneer in 1 kg
-- packs both map here, which is what makes "compare offers" mean anything.
--
-- `base_unit` and `base_pack_size` are the canonical shape; a supplier's actual
-- pack may differ and lives on their SKU.
CREATE TABLE canonical_product (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    category_id     BIGINT       NULL,
    name            VARCHAR(250) NOT NULL,
    -- Lowercased, punctuation-stripped, whitespace-collapsed. Doc 07 §2 requires
    -- search to tolerate case, spacing and punctuation; normalising once at write
    -- time is cheaper and more predictable than at every query.
    normalized_name VARCHAR(250) NOT NULL,
    description     TEXT,
    base_unit       VARCHAR(32),
    base_pack_size  DECIMAL(19,4),
    image_url       VARCHAR(1000),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_canonical_product_category FOREIGN KEY (category_id) REFERENCES product_category (id),
    KEY ix_canonical_normalized (normalized_name),
    KEY ix_canonical_category_status (category_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Alternative names for a canonical product. Doc 07 §2.
--
-- Restaurants search in their own vocabulary — "dahi" for curd, "maida" for
-- refined flour, "kaju" for cashew. Doc 07 §2 also forbids inventing semantic
-- mappings without a configured alias, so this table *is* the vocabulary: if a
-- term is not here, search does not know it.
CREATE TABLE canonical_product_alias (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    canonical_product_id BIGINT  NOT NULL,
    alias           VARCHAR(250) NOT NULL,
    normalized_alias VARCHAR(250) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_alias_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    CONSTRAINT uk_alias_product UNIQUE (canonical_product_id, normalized_alias),
    KEY ix_alias_normalized (normalized_alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Supplier-owned commercial SKU. Doc 01 §7.
--
-- Identity only: what the supplier calls it, which brand, what pack. Price and
-- availability are on the offer.
CREATE TABLE supplier_sku (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id BIGINT     NOT NULL,
    canonical_product_id BIGINT  NOT NULL,
    -- The supplier's own code. Optional — many small suppliers have none.
    sku_code        VARCHAR(120) NULL,
    name            VARCHAR(250) NOT NULL,
    brand_id        BIGINT       NULL,
    pack_size       DECIMAL(19,4) NOT NULL,
    pack_unit       VARCHAR(32)  NOT NULL,
    image_url       VARCHAR(1000),
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_sku_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_sku_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    CONSTRAINT fk_sku_brand FOREIGN KEY (brand_id) REFERENCES brand (id),
    -- One SKU code per store. MySQL treats NULLs as distinct, so suppliers
    -- without codes are unaffected; doc 40 requires duplicate SKU detection on
    -- import, and this constraint is the backstop behind that check.
    CONSTRAINT uk_sku_store_code UNIQUE (supplier_store_id, sku_code),
    KEY ix_sku_store_status (supplier_store_id, status),
    KEY ix_sku_product_status (canonical_product_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The commercial terms of a SKU, effective-dated. Doc 02 §4.
--
-- A price change closes the current offer (`effective_to` = now, status
-- SUPERSEDED) and inserts a new one. Nothing is ever updated in place.
--
-- Two reasons that matters. Doc 02 §4: historical commercial values must not be
-- overwritten when a transaction references them — an order placed last Tuesday
-- must still be explicable. And doc 01 §26 lists pricing intelligence as a
-- retention mechanism, which needs the history to exist in the first place.
--
-- Orders still snapshot their own price (doc 02 §5). This table is the catalog's
-- record, not the order's.
CREATE TABLE supplier_offer (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_sku_id BIGINT       NOT NULL,
    -- Denormalised from the SKU so ranking and search can read offers without a
    -- join back through supplier_sku. Doc 07 §3 filters on store before price.
    supplier_store_id BIGINT     NOT NULL,
    canonical_product_id BIGINT  NOT NULL,
    selling_price   DECIMAL(19,4) NOT NULL,
    gst_rate        DECIMAL(9,4) NOT NULL,
    -- AVAILABLE or OUT_OF_STOCK. Doc 01 §7: nothing else, initially.
    availability    VARCHAR(32)  NOT NULL DEFAULT 'AVAILABLE',
    -- Optional. Null means "available, quantity not tracked", which is the common
    -- case; doc 01 §7 rules out MOQ and order increments, not stock counts.
    available_quantity DECIMAL(19,4) NULL,
    effective_from  TIMESTAMP(6) NOT NULL,
    -- Null on the current offer. Set when superseded.
    effective_to    TIMESTAMP(6) NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_offer_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    CONSTRAINT fk_offer_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_offer_product FOREIGN KEY (canonical_product_id) REFERENCES canonical_product (id),
    -- The ranking query: "current, available offers for this product, cheapest
    -- first" (doc 07 §3).
    KEY ix_offer_product_current (canonical_product_id, status, availability, selling_price),
    KEY ix_offer_store_current (supplier_store_id, status, availability),
    KEY ix_offer_sku_history (supplier_sku_id, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ── Bulk import ──────────────────────────────────────────────────────────
-- Doc 25, doc 40, §23A.41:
--   Upload → Parse → Map → Validate → Preview → Confirm → Import → Summary

CREATE TABLE catalog_import (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id BIGINT     NOT NULL,
    file_name       VARCHAR(500) NOT NULL,
    file_type       VARCHAR(16)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PARSING',
    total_rows      INT          NOT NULL DEFAULT 0,
    valid_rows      INT          NOT NULL DEFAULT 0,
    invalid_rows    INT          NOT NULL DEFAULT 0,
    imported_rows   INT          NOT NULL DEFAULT 0,
    created_skus    INT          NOT NULL DEFAULT 0,
    updated_skus    INT          NOT NULL DEFAULT 0,
    -- Which file column was read as which field. Persisted so a summary read
    -- weeks later still explains how the file was interpreted.
    column_mapping_json JSON     NULL,
    error_message   VARCHAR(1000) NULL,
    uploaded_by     BIGINT       NULL,
    confirmed_by    BIGINT       NULL,
    confirmed_at    TIMESTAMP(6) NULL,
    completed_at    TIMESTAMP(6) NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_import_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_import_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id),
    KEY ix_import_store_created (supplier_store_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One row of the uploaded file, with what we made of it.
--
-- Doc 40: "Validation must identify missing required fields, invalid product
-- mapping, invalid price, invalid GST, invalid availability, duplicate SKU,
-- malformed row." Each of those is a row-level error here, carrying the original
-- line number so the supplier can find it in their spreadsheet.
CREATE TABLE catalog_import_row (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    catalog_import_id BIGINT     NOT NULL,
    -- 1-based line in the source file, excluding the header, so the supplier can
    -- find the row in their own spreadsheet.
    --
    -- Named line_number, not row_number: ROW_NUMBER is a reserved word in MySQL 8
    -- (the window function) and would need backquoting everywhere it appears.
    line_number     INT          NOT NULL,
    raw_json        JSON         NOT NULL,
    status          VARCHAR(32)  NOT NULL,
    -- Array of {field, code, message}. Row-level, never a single flat string:
    -- §23A.41 renders errors per row per field.
    errors_json     JSON         NULL,
    resolved_canonical_product_id BIGINT NULL,
    resolved_sku_id BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_import_row_import FOREIGN KEY (catalog_import_id) REFERENCES catalog_import (id),
    CONSTRAINT uk_import_row UNIQUE (catalog_import_id, line_number),
    KEY ix_import_row_status (catalog_import_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
