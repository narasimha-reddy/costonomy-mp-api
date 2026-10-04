-- Tax invoices and credit notes without fabricated data (D-133).
--
-- Everything the old generator wrote carried invented values (HSN 9968, state 36, placeholder parties), and nothing
-- is in production, so those rows are removed rather than migrated.
DELETE FROM credit_note_item;
DELETE FROM credit_note;
DELETE FROM tax_invoice_item;
DELETE FROM tax_invoice;

-- A line's HSN code is a fact about the item, copied onto the order line when it is created (SKU first, then the
-- product). Orders that already exist get the same backfill; a line still without one is refused at invoice time.
UPDATE supplier_order_item i
  JOIN supplier_sku s ON s.id = i.supplier_sku_id
  LEFT JOIN canonical_product p ON p.id = i.canonical_product_id
   SET i.hsn_code = COALESCE(s.hsn_code, p.hsn_code)
 WHERE i.hsn_code IS NULL;

-- No cascade: nothing here is ever hard-deleted. No default HSN: 9968 is a courier-services code, not goods.
ALTER TABLE tax_invoice_item
    DROP FOREIGN KEY fk_tax_invoice_item_invoice,
    MODIFY hsn_code VARCHAR(16) NOT NULL,
    ADD CONSTRAINT fk_tax_invoice_item_invoice FOREIGN KEY (tax_invoice_id) REFERENCES tax_invoice (id),
    ADD CONSTRAINT fk_tax_invoice_item_order_item FOREIGN KEY (supplier_order_item_id) REFERENCES supplier_order_item (id);

ALTER TABLE credit_note_item
    DROP FOREIGN KEY fk_credit_note_item_note,
    MODIFY hsn_code VARCHAR(16) NOT NULL,
    ADD CONSTRAINT fk_credit_note_item_note FOREIGN KEY (credit_note_id) REFERENCES credit_note (id),
    ADD CONSTRAINT fk_credit_note_item_order_item FOREIGN KEY (supplier_order_item_id) REFERENCES supplier_order_item (id);

-- A supplier's series is its own, per financial year (April to March): numbers are unique per supplier GSTIN, not
-- globally, and fit the 16 characters an invoice number may have under Rule 46.
ALTER TABLE tax_invoice
    DROP INDEX uk_tax_invoice_number,
    MODIFY invoice_number VARCHAR(16) NOT NULL,
    MODIFY supplier_gstin VARCHAR(15) NOT NULL,
    MODIFY supplier_address TEXT NOT NULL,
    MODIFY supplier_state_code VARCHAR(2) NOT NULL,
    MODIFY buyer_address TEXT NOT NULL,
    MODIFY buyer_state_code VARCHAR(2) NOT NULL,
    MODIFY place_of_supply VARCHAR(64) NOT NULL,
    ADD COLUMN fiscal_year SMALLINT NOT NULL AFTER invoice_number,
    ADD COLUMN sequence_value BIGINT NOT NULL AFTER fiscal_year,
    ADD CONSTRAINT uk_tax_invoice_supplier_number UNIQUE (supplier_gstin, invoice_number),
    ADD CONSTRAINT ck_tax_invoice_number_length CHECK (CHAR_LENGTH(invoice_number) <= 16);

ALTER TABLE credit_note
    DROP INDEX uk_credit_note_number,
    MODIFY credit_note_number VARCHAR(16) NOT NULL,
    MODIFY supplier_gstin VARCHAR(15) NOT NULL,
    MODIFY reason_code VARCHAR(64) NOT NULL,
    ADD COLUMN fiscal_year SMALLINT NOT NULL AFTER credit_note_number,
    ADD COLUMN sequence_value BIGINT NOT NULL AFTER fiscal_year,
    ADD CONSTRAINT uk_credit_note_supplier_number UNIQUE (supplier_gstin, credit_note_number),
    -- One credit note per order and reason: the same doorstep check-in replayed cannot issue a second.
    ADD CONSTRAINT uk_credit_note_order_reason UNIQUE (supplier_order_id, reason_code),
    ADD CONSTRAINT ck_credit_note_reason CHECK (reason_code IN ('DOORSTEP_REJECTION')),
    ADD CONSTRAINT ck_credit_note_number_length CHECK (CHAR_LENGTH(credit_note_number) <= 16);

-- The next number in a series. Allocated under a row lock inside the transaction that inserts the document, so an
-- insert that loses a race rolls the counter back with it and the series stays gap-free.
CREATE TABLE document_sequence (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    scope_key   VARCHAR(32)  NOT NULL,
    fiscal_year SMALLINT     NOT NULL,
    doc_type    VARCHAR(16)  NOT NULL,
    next_value  BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_document_sequence UNIQUE (scope_key, fiscal_year, doc_type),
    CONSTRAINT ck_document_sequence_type CHECK (doc_type IN ('TAX_INVOICE', 'CREDIT_NOTE')),
    CONSTRAINT ck_document_sequence_next CHECK (next_value >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
