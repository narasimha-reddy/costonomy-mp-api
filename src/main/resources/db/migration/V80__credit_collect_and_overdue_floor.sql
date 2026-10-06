-- V80 — Who may collect on a credit line, and the floor a manual reinstate leaves behind.
--
-- CREDIT_COLLECT: record a payment, confirm or reject a restaurant's "I paid" claim. Until now these needed
-- CREDIT_MODIFY, which a store manager does not hold, so the person who actually receives the cash could not
-- record it. CREDIT_MODIFY stays accepted everywhere CREDIT_COLLECT is, and keeps terms and suspension alone.
-- CREDIT_WRITE_OFF: giving up on a debt. Owner and admin only.
--
-- Granted explicitly. V5 gave SUP_OWNER and SUP_ADMIN "every SUPPLIER and SHARED permission" as it was at V5
-- time, so a permission added later reaches nobody unless a migration says so.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('CREDIT_COLLECT', 'Collect on credit', 'SUPPLIER',
 'Record a payment received, and confirm or reject a restaurant''s payment claim', NOW(6), NOW(6)),
('CREDIT_WRITE_OFF', 'Write off credit', 'SUPPLIER',
 'Give up on a debt a restaurant owes', NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'CREDIT_COLLECT'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN', 'SUP_FINANCE_STAFF', 'SUP_STORE_MANAGER');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'CREDIT_WRITE_OFF'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN');

-- What was overdue when a supplier manually lifted an overdue-sweep suspension. The sweep suspends again only
-- when overdue goes above max(max_overdue_amount, overdue_floor); cleared when overdue returns to zero.
ALTER TABLE credit_agreement ADD COLUMN overdue_floor DECIMAL(19,4) NULL AFTER max_overdue_amount;
