-- V50 — The user's review of a bill (D-114).
--
-- After a bill is read, the restaurant reviews and edits it: supplier, invoice number, dates, payment status,
-- the lines matched to SKUs, delivery. That version is kept here, next to the reading, which is never
-- overwritten. Nothing is written to the cost app: choosing "Create supplier" or "Create SKU" only records the
-- choice on this row.
--
-- Additive only: three nullable columns, no existing row changes. V45 to V48 stay reserved for the renumbering
-- of the payments migrations.

ALTER TABLE wallet_entry_invoice
    -- The reviewed bill as the server computed it (money summed with BigDecimal on our side). Null until saved.
    ADD COLUMN review_json  JSON         NULL,
    ADD COLUMN reviewed_at  TIMESTAMP(6) NULL,
    ADD COLUMN reviewed_by  BIGINT       NULL;
