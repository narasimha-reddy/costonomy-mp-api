-- A SKU that a kitchen can decide about. D-096.
--
-- Until now a listing was a price, a pack and a thumbnail. That is enough to
-- compare on and not enough to choose on: nobody buys a 25 kg sack of anything
-- without knowing what it is, what it looks like, or whether it fits on the
-- shelf. Everything here is optional, and a SKU without any of it behaves
-- exactly as it does today.

ALTER TABLE supplier_sku
    -- What it is, in the supplier's own words. Long enough for a paragraph and
    -- no longer: this is a product description, not a landing page.
    ADD COLUMN description VARCHAR(2000) NULL,
    -- The pack, measured. Structured rather than a line of text because a
    -- kitchen comparing two sacks needs to compare them, and because delivery
    -- already reasons about weight — which `weight_grams` (V29) carries.
    ADD COLUMN length_cm DECIMAL(9,2) NULL,
    ADD COLUMN width_cm  DECIMAL(9,2) NULL,
    ADD COLUMN height_cm DECIMAL(9,2) NULL,
    -- Pasted, not uploaded. Hosting video is a different problem and this is
    -- the 90% of it: suppliers already have their product videos on YouTube.
    ADD COLUMN youtube_url VARCHAR(500) NULL;

-- The gallery. `supplier_sku.image_url` stays exactly what it was — the
-- thumbnail every list and cart row already shows — and these are the rest.
-- Separate rather than a JSON column so an image can be reordered or removed
-- without rewriting the row, and so position is a number the database sorts.
CREATE TABLE supplier_sku_image (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_sku_id BIGINT       NOT NULL,
    url             VARCHAR(500) NOT NULL,
    position        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                    ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,
    KEY ix_sku_image_sku (supplier_sku_id, position),
    CONSTRAINT fk_sku_image_sku FOREIGN KEY (supplier_sku_id)
        REFERENCES supplier_sku (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- What kitchens thought of the pack itself.
--
-- **One per order line, and the line has to exist.** A review is a report of
-- something that arrived, which is what makes it worth reading and what stops
-- it being a competitor or the supplier themselves. `uk_sku_review_item`
-- enforces the "one" — the same restaurant reviews again by buying again.
--
-- Separate from `rating`, which is about the order and the store: "the delivery
-- was late" and "the paneer was wet" are different complaints and a kitchen
-- comparing packs needs the second one.
CREATE TABLE sku_review (
    id                     BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_sku_id        BIGINT       NOT NULL,
    supplier_order_id      BIGINT       NOT NULL,
    supplier_order_item_id BIGINT       NOT NULL,
    outlet_id              BIGINT       NOT NULL,
    rating                 INT          NOT NULL,
    comment                VARCHAR(2000) NULL,
    moderation_status      VARCHAR(32)  NOT NULL DEFAULT 'PUBLISHED',
    moderation_reason      VARCHAR(500) NULL,
    moderated_by           BIGINT       NULL,
    moderated_at           TIMESTAMP(6) NULL,
    reviewed_by            BIGINT       NOT NULL,
    created_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                           ON UPDATE CURRENT_TIMESTAMP(6),
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_sku_review_item UNIQUE (supplier_order_item_id),
    KEY ix_sku_review_sku (supplier_sku_id, moderation_status),
    CONSTRAINT fk_sku_review_sku FOREIGN KEY (supplier_sku_id)
        REFERENCES supplier_sku (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
