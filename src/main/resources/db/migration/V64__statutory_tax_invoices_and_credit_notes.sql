-- V64: Statutory B2B Tax Invoices, Credit Notes (Section 31 & 34 CGST Act), and Subscription Funding.
--
-- Provides:
-- 1. Full GST-compliant Tax Invoices for fulfilled B2B orders with supplier & buyer GSTIN,
--    tax breakdowns (CGST + SGST for intra-state, IGST for inter-state), and line-level HSN.
-- 2. Statutory Credit Notes for doorstep rejections and catch-weight under-delivery adjustments.
-- 3. Payment method configuration on recurring subscriptions to enforce pre-dispatch funding gating.
-- 4. HSN code tracking on catalog products, SKUs, and order lines.

ALTER TABLE subscription
    ADD COLUMN payment_method VARCHAR(32) NOT NULL DEFAULT 'WALLET' AFTER delivery_mode;

ALTER TABLE canonical_product
    ADD COLUMN hsn_code VARCHAR(16) NULL AFTER base_pack_size;

ALTER TABLE supplier_sku
    ADD COLUMN hsn_code VARCHAR(16) NULL AFTER pack_size;

ALTER TABLE supplier_order_item
    ADD COLUMN hsn_code VARCHAR(16) NULL AFTER unit;

CREATE TABLE tax_invoice (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    invoice_number          VARCHAR(64) NOT NULL,
    supplier_order_id       BIGINT NOT NULL,
    supplier_store_id       BIGINT NOT NULL,
    supplier_organization_id BIGINT NOT NULL,
    outlet_id               BIGINT NOT NULL,
    restaurant_id           BIGINT NOT NULL,
    supplier_name           VARCHAR(255) NOT NULL,
    supplier_gstin          VARCHAR(32) NULL,
    supplier_address        TEXT NULL,
    supplier_state_code     VARCHAR(8) NULL,
    buyer_name              VARCHAR(255) NOT NULL,
    buyer_gstin             VARCHAR(32) NULL,
    buyer_address           TEXT NULL,
    buyer_state_code        VARCHAR(8) NULL,
    place_of_supply         VARCHAR(64) NULL,
    is_inter_state          TINYINT(1) NOT NULL DEFAULT 0,
    taxable_amount          DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    cgst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    sgst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    igst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    delivery_fee            DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_amount            DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    status                  VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
    issued_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_tax_invoice_number UNIQUE (invoice_number),
    CONSTRAINT uk_tax_invoice_order UNIQUE (supplier_order_id),
    CONSTRAINT fk_tax_invoice_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_tax_invoice_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_tax_invoice_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    INDEX idx_tax_invoice_store (supplier_store_id, issued_at),
    INDEX idx_tax_invoice_outlet (outlet_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tax_invoice_item (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    tax_invoice_id          BIGINT NOT NULL,
    supplier_order_item_id  BIGINT NOT NULL,
    product_name            VARCHAR(255) NOT NULL,
    hsn_code                VARCHAR(16) NOT NULL DEFAULT '9968',
    quantity                DECIMAL(19,4) NOT NULL,
    unit                    VARCHAR(32) NOT NULL,
    unit_price              DECIMAL(19,4) NOT NULL,
    taxable_value           DECIMAL(19,4) NOT NULL,
    gst_rate                DECIMAL(9,4) NOT NULL,
    cgst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    sgst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    igst_amount             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_amount            DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_tax_invoice_item_invoice FOREIGN KEY (tax_invoice_id) REFERENCES tax_invoice (id) ON DELETE CASCADE,
    INDEX idx_tax_invoice_item_order_item (supplier_order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_note (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_note_number      VARCHAR(64) NOT NULL,
    tax_invoice_id          BIGINT NULL,
    tax_invoice_number      VARCHAR(64) NULL,
    supplier_order_id       BIGINT NOT NULL,
    supplier_store_id       BIGINT NOT NULL,
    supplier_organization_id BIGINT NOT NULL,
    outlet_id               BIGINT NOT NULL,
    restaurant_id           BIGINT NOT NULL,
    supplier_name           VARCHAR(255) NOT NULL,
    supplier_gstin          VARCHAR(32) NULL,
    buyer_name              VARCHAR(255) NOT NULL,
    buyer_gstin             VARCHAR(32) NULL,
    reason_code             VARCHAR(64) NOT NULL DEFAULT 'DOORSTEP_REJECTION',
    is_inter_state          TINYINT(1) NOT NULL DEFAULT 0,
    taxable_refund_amount   DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    cgst_refund_amount      DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    sgst_refund_amount      DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    igst_refund_amount      DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_refund_amount     DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    status                  VARCHAR(32) NOT NULL DEFAULT 'ISSUED',
    issued_at               TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_credit_note_number UNIQUE (credit_note_number),
    CONSTRAINT fk_credit_note_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_credit_note_invoice FOREIGN KEY (tax_invoice_id) REFERENCES tax_invoice (id),
    INDEX idx_credit_note_order (supplier_order_id),
    INDEX idx_credit_note_store (supplier_store_id, issued_at),
    INDEX idx_credit_note_outlet (outlet_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_note_item (
    id                      BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_note_id          BIGINT NOT NULL,
    supplier_order_item_id  BIGINT NOT NULL,
    product_name            VARCHAR(255) NOT NULL,
    hsn_code                VARCHAR(16) NOT NULL DEFAULT '9968',
    rejected_quantity       DECIMAL(19,4) NOT NULL,
    unit                    VARCHAR(32) NOT NULL,
    unit_price              DECIMAL(19,4) NOT NULL,
    taxable_refund          DECIMAL(19,4) NOT NULL,
    gst_rate                DECIMAL(9,4) NOT NULL,
    cgst_refund             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    sgst_refund             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    igst_refund             DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    total_refund            DECIMAL(19,4) NOT NULL DEFAULT 0.0000,
    rejection_reason        VARCHAR(255) NULL,
    created_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_credit_note_item_note FOREIGN KEY (credit_note_id) REFERENCES credit_note (id) ON DELETE CASCADE,
    INDEX idx_credit_note_item_order_item (supplier_order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
