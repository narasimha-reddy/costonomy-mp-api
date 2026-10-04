-- Cold-chain handling is declared, superseded and gated on verified carrier capability (D-134).

-- 1. A product's own cold-chain flag is a floor under its SKUs: a SKU of a chilled product is chilled.
UPDATE supplier_sku s
  JOIN canonical_product p ON p.id = s.canonical_product_id
   SET s.requires_cold_chain = 1
 WHERE p.requires_cold_chain = 1 AND s.requires_cold_chain = 0;

-- 2. A supplier's handling declaration (cold chain, catch-weight) is superseded, never edited in place: each
--    change closes the current row and opens the next, with who and why. The SKU's own flags stay as the single
--    effective value every reader uses, written only by the declaration service. Orders already placed carry
--    snapshots and are untouched.
CREATE TABLE supplier_sku_handling_declaration (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_sku_id     BIGINT        NOT NULL,
    requires_cold_chain TINYINT(1)    NOT NULL,
    is_catch_weight     TINYINT(1)    NOT NULL,
    effective_from      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    effective_to        TIMESTAMP(6)  NULL,
    declared_by         BIGINT        NULL,
    reason              VARCHAR(500)  NOT NULL,
    superseded_by_id    BIGINT        NULL,
    -- One current declaration per SKU, enforced by the database (NULL once superseded, and NULLs never collide).
    current_sku_key     BIGINT GENERATED ALWAYS AS (CASE WHEN effective_to IS NULL THEN supplier_sku_id END) STORED,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_sku_handling_sku FOREIGN KEY (supplier_sku_id) REFERENCES supplier_sku (id),
    CONSTRAINT fk_sku_handling_superseded_by FOREIGN KEY (superseded_by_id)
        REFERENCES supplier_sku_handling_declaration (id),
    CONSTRAINT uk_sku_handling_current UNIQUE (current_sku_key),
    KEY ix_sku_handling_sku (supplier_sku_id, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO supplier_sku_handling_declaration
       (supplier_sku_id, requires_cold_chain, is_catch_weight, effective_from, declared_by, reason)
SELECT id, requires_cold_chain, is_catch_weight, created_at, NULL, 'Declared when the SKU was created'
  FROM supplier_sku;

-- 3. Which vehicle classes a carrier is known to offer with temperature control, and the evidence. A carrier with no
--    row is not capable: "three-wheeler means insulated" was an assumption, not data (D-121). Only the two mock
--    providers are seeded, for local and test use, and say so.
CREATE TABLE delivery_provider_cold_chain_capability (
    id                   BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_provider_id BIGINT       NOT NULL,
    vehicle_type         VARCHAR(32)  NOT NULL,
    evidence             VARCHAR(500) NOT NULL,
    verified_at          TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    verified_by          VARCHAR(120) NULL,
    created_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_cold_chain_capability_provider FOREIGN KEY (delivery_provider_id) REFERENCES delivery_provider (id),
    CONSTRAINT uk_cold_chain_capability UNIQUE (delivery_provider_id, vehicle_type),
    CONSTRAINT ck_cold_chain_capability_vehicle CHECK (vehicle_type IN ('TWO_WHEELER', 'THREE_WHEELER', 'FOUR_WHEELER_TRUCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO delivery_provider_cold_chain_capability (delivery_provider_id, vehicle_type, evidence, verified_by)
SELECT p.id, v.vehicle_type,
       'LOCAL AND TEST USE ONLY: the mock provider simulates a temperature-controlled vehicle; it is not a real carrier',
       'seed'
  FROM delivery_provider p
  JOIN (SELECT 'THREE_WHEELER' AS vehicle_type UNION ALL SELECT 'FOUR_WHEELER_TRUCK') v
 WHERE p.code IN ('MOCK_EXPRESS', 'MOCK_SAVER');

-- 4. A delivery fee quote remembers whether it priced a chilled consignment, so one priced for ordinary goods cannot
--    be spent on an order that has since become chilled.
ALTER TABLE delivery_fee_quote
    ADD COLUMN cold_chain TINYINT(1) NOT NULL DEFAULT 0;
