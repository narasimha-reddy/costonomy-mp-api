-- V81 — A payment the supplier records for a whole credit line is one credit_repayment receipt (D-164).
--
-- The receipt is the same row a wallet repayment writes, with source SUPPLIER_RECORDED (17 characters, so the column
-- widens from 16) and no wallet debit or payout. It carries what the supplier typed: how it was paid, the
-- reference (a UTR or cheque number), the India day the money arrived and a note. The per-invoice credit_payment
-- rows still point back to it.
ALTER TABLE credit_repayment
    MODIFY COLUMN source VARCHAR(32) NOT NULL,
    ADD COLUMN method    VARCHAR(32)  NULL AFTER source,
    ADD COLUMN reference VARCHAR(200) NULL AFTER method,
    ADD COLUMN paid_on   DATE         NULL AFTER reference,
    ADD COLUMN note      VARCHAR(500) NULL AFTER paid_on;

-- The duplicate-reference check ("has this UTR been recorded in this store lately?"). credit_payment has no store
-- column, so the check starts from the reference, newest first, and joins the agreement for the store. The column is
-- utf8mb4_0900_ai_ci, so the equality is case-insensitive by itself. Every receipt, a payment recorded one invoice
-- at a time and a confirmed claim write the reference here, so one index covers all three.
CREATE INDEX ix_credit_payment_reference ON credit_payment (reference, paid_at);
