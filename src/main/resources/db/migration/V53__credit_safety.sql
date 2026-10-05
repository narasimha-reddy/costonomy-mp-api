-- Who suspended a credit line: the overdue sweep (SYSTEM) or the supplier (SUPPLIER).
-- Only a SYSTEM suspension lifts itself when the overdue balance is paid back (D-119).
ALTER TABLE credit_agreement ADD COLUMN suspension_source VARCHAR(16) NULL AFTER suspension_reason;

UPDATE credit_agreement
   SET suspension_source = CASE WHEN suspension_reason LIKE 'Overdue balance%' THEN 'SYSTEM' ELSE 'SUPPLIER' END
 WHERE status = 'SUSPENDED';

-- Serves the "needs attention" lookup of a restaurant's overdue invoices per outlet.
CREATE INDEX ix_credit_invoice_outlet_status ON credit_invoice (outlet_id, status);
