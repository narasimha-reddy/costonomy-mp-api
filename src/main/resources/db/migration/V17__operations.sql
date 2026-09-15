-- V17 — Operations permissions.
--
-- Doc 09 §12–13, and the note V5 left for this phase.
--
-- **Operations gets its own permissions instead of borrowing the tenant's.**
-- V5 granted `ORDER_VIEW` and `CREDIT_VIEW` to the OPS roles. Both are SHARED
-- rather than tenant permissions, so `PermissionCatalogIT` was satisfied — but a
-- PLATFORM-scoped `ORDER_VIEW` satisfies a check at *any* outlet or store, which
-- means an operator could read and act through the restaurant's and supplier's own
-- endpoints. Doc 09 §17 is explicit that operations is not a mobile feature; those
-- endpoints enforce tenant rules and return tenant shapes, and an operator arriving
-- through them is invisible to anyone reasoning about who can call what.
--
-- So: the SHARED grants come off the OPS roles, and inspection happens through
-- `/api/v1/admin/**` gated on the INTERNAL permissions below.
--
-- **Read and write are separated**, which doc 09 §13 requires in as many words:
-- "support users may inspect records without receiving unrestricted mutation
-- rights". `DELIVERY_OPERATE` and `PAYMENT_RECONCILE` are mutations; until now
-- there was no way to grant someone the ability to *look* at a delivery or a
-- payment without also granting the ability to change it.

INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('SUPPLIER_INSPECT', 'Inspect suppliers',  'INTERNAL',
 'Read any supplier organisation, store and catalog entry', NOW(6), NOW(6)),
('ORDER_INSPECT',    'Inspect orders',     'INTERNAL',
 'Read any supplier order and its timeline', NOW(6), NOW(6)),
('PAYMENT_INSPECT',  'Inspect payments',   'INTERNAL',
 'Read any payment, refund and reconciliation state', NOW(6), NOW(6)),
('DELIVERY_INSPECT', 'Inspect deliveries', 'INTERNAL',
 'Read any delivery, its attempts and its events', NOW(6), NOW(6)),
('DISPUTE_INSPECT',  'Inspect disputes',   'INTERNAL',
 'Read any dispute and its full thread', NOW(6), NOW(6)),
('CONFIG_VIEW',      'View configuration', 'INTERNAL',
 'Read operational configuration without changing it', NOW(6), NOW(6));

-- Support inspects everything and changes nothing. Doc 09 §13's example case.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p
    ON p.code IN ('SUPPLIER_INSPECT','ORDER_INSPECT','PAYMENT_INSPECT',
                  'DELIVERY_INSPECT','DISPUTE_INSPECT','CONFIG_VIEW')
 WHERE r.code = 'OPS_SUPPORT';

-- Specialists inspect what they operate on, and only that. A delivery operator
-- has no business reading a credit ledger.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code IN ('ORDER_INSPECT','DELIVERY_INSPECT')
 WHERE r.code = 'OPS_DELIVERY';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code IN ('ORDER_INSPECT','PAYMENT_INSPECT','CONFIG_VIEW')
 WHERE r.code = 'OPS_FINANCE';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p
    ON p.code IN ('SUPPLIER_INSPECT','ORDER_INSPECT','DISPUTE_INSPECT')
 WHERE r.code = 'OPS_MODERATION';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p ON p.code = 'SUPPLIER_INSPECT'
 WHERE r.code = 'OPS_SUPPLIER_VERIFY';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p
    ON p.code IN ('SUPPLIER_INSPECT','ORDER_INSPECT','PAYMENT_INSPECT',
                  'DELIVERY_INSPECT','DISPUTE_INSPECT','CONFIG_VIEW')
 WHERE r.code = 'OPS_MARKETPLACE';

-- OPS_ADMIN holds every INTERNAL permission by the rule V5 used, so it needs no
-- new row here — but the rule ran before these existed, so it does.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6)
  FROM role r JOIN permission p
    ON p.code IN ('SUPPLIER_INSPECT','ORDER_INSPECT','PAYMENT_INSPECT',
                  'DELIVERY_INSPECT','DISPUTE_INSPECT','CONFIG_VIEW')
 WHERE r.code = 'OPS_ADMIN';

-- Take the borrowed tenant-facing grants back off the internal roles.
--
-- ORDER_VIEW and CREDIT_VIEW are the restaurant's and the supplier's own
-- permissions. An operator holding either at PLATFORM scope can call the tenant
-- endpoints — and would then be subject to tenant rules, returning tenant shapes,
-- with no audit distinguishing them from the restaurant itself.
DELETE rp FROM role_permission rp
  JOIN role r ON r.id = rp.role_id
  JOIN permission p ON p.id = rp.permission_id
 WHERE r.code LIKE 'OPS_%'
   AND p.code IN ('ORDER_VIEW', 'CREDIT_VIEW');
