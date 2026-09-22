-- V24 — Delivery weight, volume and vehicle classification.
--
-- Supports dynamic vehicle selection for delivery dispatch:
--   TWO_WHEELER       (<= 20 kg)
--   THREE_WHEELER     (20 kg - 100 kg)
--   FOUR_WHEELER_TRUCK (> 100 kg / Bulk)
--
-- Stores estimated payload weight and volume alongside the delivery and its
-- quotes for dispatch optimization and auditable pricing.

ALTER TABLE delivery
    ADD COLUMN weight_kg DECIMAL(19,4) NULL AFTER drop_contact_phone,
    ADD COLUMN volume_cbm DECIMAL(19,4) NULL AFTER weight_kg,
    ADD COLUMN vehicle_type VARCHAR(32) NULL AFTER volume_cbm,
    ADD COLUMN assignment_deadline TIMESTAMP(6) NULL AFTER assigned_at,
    ADD KEY ix_delivery_assignment_deadline (status, assignment_deadline);

ALTER TABLE delivery_quote
    ADD COLUMN vehicle_type VARCHAR(32) NULL AFTER distance_km;

-- Seed Pidge Delivery Orchestration & Smart Dispatch Provider (enabled via app config / admin)
INSERT INTO delivery_provider (code, name, enabled, supports_tracking, supports_proof, priority, created_at, updated_at)
VALUES ('PIDGE', 'Pidge Smart Dispatch', 0, 1, 1, 5, NOW(6), NOW(6));

-- ── Delivery Central Ledger ─────────────────────────────────────────────
--
-- Append-only financial ledger tracking quoted vs actual courier charges,
-- platform delivery margin, and wallet/escrow drawdowns for observability.
CREATE TABLE delivery_ledger (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    delivery_id         BIGINT        NOT NULL,
    provider_code       VARCHAR(64)   NOT NULL,
    provider_delivery_id VARCHAR(200) NULL,
    entry_type          VARCHAR(32)   NOT NULL, -- QUOTED, BOOKED, ADJUSTED, REFUNDED
    amount              DECIMAL(19,4) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',
    description         VARCHAR(500)  NULL,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_delivery_ledger_delivery FOREIGN KEY (delivery_id) REFERENCES delivery (id),
    KEY ix_delivery_ledger_delivery (delivery_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
