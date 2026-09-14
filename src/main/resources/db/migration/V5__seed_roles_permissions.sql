-- V5 — Reference data: roles and permissions.
--
-- Doc 02 §8 sketches seed data as V14, but authorization cannot work without it
-- and every endpoint from here on is authorized. So it lands with the feature
-- that needs it. See docs/DECISIONS.md D-008.
--
-- Two conventions worth knowing before adding to this file:
--
-- 1. `permission.code` is globally unique, and some permissions are genuinely
--    shared between the restaurant and supplier worlds — ORDER_VIEW is the same
--    capability, held by a restaurant user at OUTLET scope and by a supplier user
--    at SUPPLIER_STORE scope. Those are seeded once with scope 'SHARED'. The
--    scope of a *grant* is what disambiguates them, not the permission itself.
--
-- 2. Role codes are prefixed by world (REST_/SUP_/OPS_) because both worlds have
--    a "Store Manager" and `role.code` is globally unique.

-- ── Permissions ──────────────────────────────────────────────────────────
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
-- Restaurant
('RESTAURANT_VIEW',     'View restaurant',          'RESTAURANT', 'See restaurant details',                      NOW(6), NOW(6)),
('RESTAURANT_EDIT',     'Edit restaurant',          'RESTAURANT', 'Change restaurant details',                   NOW(6), NOW(6)),
('OUTLET_VIEW',         'View outlet',              'RESTAURANT', 'See outlet details',                          NOW(6), NOW(6)),
('OUTLET_EDIT',         'Edit outlet',              'RESTAURANT', 'Change outlet details and address',           NOW(6), NOW(6)),
('OUTLET_USER_MANAGE',  'Manage outlet users',      'RESTAURANT', 'Invite, assign and remove outlet members',    NOW(6), NOW(6)),
('REQUIREMENT_CREATE',  'Create requirement',       'RESTAURANT', 'Raise a procurement requirement',             NOW(6), NOW(6)),
('REQUIREMENT_EDIT',    'Edit requirement',         'RESTAURANT', 'Change or cancel a requirement',              NOW(6), NOW(6)),
('PROCUREMENT_CREATE',  'Create procurement',       'RESTAURANT', 'Build a cart and procurement request',        NOW(6), NOW(6)),
('PROCUREMENT_SUBMIT',  'Submit procurement',       'RESTAURANT', 'Place the order with suppliers',              NOW(6), NOW(6)),
('PROCUREMENT_APPROVE', 'Approve procurement',      'RESTAURANT', 'Approve an order that policy has held',       NOW(6), NOW(6)),
('ORDER_CANCEL',        'Cancel order',             'RESTAURANT', 'Cancel where policy permits',                 NOW(6), NOW(6)),
('ORDER_RECEIVE',       'Receive order',            'RESTAURANT', 'Record received quantities',                  NOW(6), NOW(6)),
('DISPUTE_CREATE',      'Raise dispute',            'RESTAURANT', 'Raise a dispute against a delivered order',   NOW(6), NOW(6)),
('RATING_CREATE',       'Rate supplier',            'RESTAURANT', 'Rate a completed order',                      NOW(6), NOW(6)),
('CREDIT_REQUEST',      'Request credit',           'RESTAURANT', 'Request credit terms from a supplier',        NOW(6), NOW(6)),
('PAYMENT_CREATE',      'Make payment',             'RESTAURANT', 'Authorise payment for an order',              NOW(6), NOW(6)),
-- Supplier
('SUPPLIER_VIEW',       'View supplier',            'SUPPLIER',   'See supplier organisation details',           NOW(6), NOW(6)),
('SUPPLIER_EDIT',       'Edit supplier',            'SUPPLIER',   'Change supplier organisation details',        NOW(6), NOW(6)),
('SUPPLIER_USER_MANAGE','Manage supplier users',    'SUPPLIER',   'Invite, assign and remove store members',     NOW(6), NOW(6)),
('STORE_VIEW',          'View store',               'SUPPLIER',   'See store details',                           NOW(6), NOW(6)),
('STORE_EDIT',          'Edit store',               'SUPPLIER',   'Change store details, hours and SLA',         NOW(6), NOW(6)),
('CATALOG_VIEW',        'View catalog',             'SUPPLIER',   'See the store catalog',                       NOW(6), NOW(6)),
('CATALOG_EDIT',        'Edit catalog',             'SUPPLIER',   'Add and change SKUs, prices, availability',   NOW(6), NOW(6)),
('CATALOG_IMPORT',      'Import catalog',           'SUPPLIER',   'Bulk import SKUs',                            NOW(6), NOW(6)),
('ORDER_ACCEPT',        'Accept order',             'SUPPLIER',   'Accept an incoming order',                    NOW(6), NOW(6)),
('ORDER_PARTIAL_ACCEPT','Partially accept order',   'SUPPLIER',   'Accept reduced quantities',                   NOW(6), NOW(6)),
('ORDER_REJECT',        'Reject order',             'SUPPLIER',   'Reject an incoming order',                    NOW(6), NOW(6)),
('ORDER_PREPARE',       'Mark preparing',           'SUPPLIER',   'Move an order to preparing',                  NOW(6), NOW(6)),
('ORDER_READY',         'Mark ready',               'SUPPLIER',   'Mark an order ready for pickup',              NOW(6), NOW(6)),
('CREDIT_REQUEST_VIEW', 'View credit requests',     'SUPPLIER',   'See incoming credit requests',                NOW(6), NOW(6)),
('CREDIT_APPROVE',      'Approve credit',           'SUPPLIER',   'Approve a credit agreement',                  NOW(6), NOW(6)),
('CREDIT_REJECT',       'Reject credit',            'SUPPLIER',   'Reject a credit request',                     NOW(6), NOW(6)),
('CREDIT_MODIFY',       'Modify credit',            'SUPPLIER',   'Change limit, period or terms',               NOW(6), NOW(6)),
('SETTLEMENT_VIEW',     'View settlements',         'SUPPLIER',   'See settlement statements',                   NOW(6), NOW(6)),
('PERFORMANCE_VIEW',    'View performance',         'SUPPLIER',   'See acceptance, fill and on-time rates',      NOW(6), NOW(6)),
-- Shared: same capability, disambiguated by the scope of the grant
('ORDER_VIEW',          'View order',               'SHARED',     'See an order and its timeline',               NOW(6), NOW(6)),
('CREDIT_VIEW',         'View credit',              'SHARED',     'See credit balances and ledger',              NOW(6), NOW(6)),
('NOTIFICATION_VIEW',   'View notifications',       'SHARED',     'See notifications',                           NOW(6), NOW(6)),
-- Internal
('SUPPLIER_VERIFY',     'Verify supplier',          'INTERNAL',   'Approve or reject supplier verification',     NOW(6), NOW(6)),
('SUPPLIER_SUSPEND',    'Suspend supplier',         'INTERNAL',   'Suspend or reactivate a supplier',            NOW(6), NOW(6)),
('CATALOG_MODERATE',    'Moderate catalog',         'INTERNAL',   'Edit canonical products, disable SKUs',       NOW(6), NOW(6)),
('ORDER_SUPPORT',       'Order support',            'INTERNAL',   'Inspect any order for support',               NOW(6), NOW(6)),
('DISPUTE_MODERATE',    'Moderate disputes',        'INTERNAL',   'Investigate and resolve disputes',            NOW(6), NOW(6)),
('DELIVERY_OPERATE',    'Operate delivery',         'INTERNAL',   'Inspect and reassign deliveries',             NOW(6), NOW(6)),
('PAYMENT_RECONCILE',   'Reconcile payments',       'INTERNAL',   'Inspect and reconcile payments and refunds',  NOW(6), NOW(6)),
('CREDIT_AUDIT',        'Audit credit',             'INTERNAL',   'Inspect credit exposure across the network',  NOW(6), NOW(6)),
('SETTLEMENT_OPERATE',  'Operate settlements',      'INTERNAL',   'Generate and approve settlements',            NOW(6), NOW(6)),
('AUDIT_VIEW',          'View audit log',           'INTERNAL',   'Search the audit trail',                      NOW(6), NOW(6)),
('CONFIG_MANAGE',       'Manage configuration',     'INTERNAL',   'Change operational configuration',            NOW(6), NOW(6));

-- ── Roles ────────────────────────────────────────────────────────────────
INSERT INTO role (code, name, scope, description, status, created_at, updated_at) VALUES
('REST_OWNER',            'Owner',                     'RESTAURANT', 'Full control of the restaurant',             'ACTIVE', NOW(6), NOW(6)),
('REST_ADMIN',            'Admin',                     'RESTAURANT', 'Manages outlets, users and policies',        'ACTIVE', NOW(6), NOW(6)),
('REST_PURCHASE_MANAGER', 'Purchase Manager',          'RESTAURANT', 'Owns procurement for an outlet',             'ACTIVE', NOW(6), NOW(6)),
('REST_STORE_MANAGER',    'Store Manager',             'RESTAURANT', 'Runs an outlet day to day',                  'ACTIVE', NOW(6), NOW(6)),
('REST_PROCUREMENT_STAFF','Procurement Staff',         'RESTAURANT', 'Raises requirements and builds carts',       'ACTIVE', NOW(6), NOW(6)),
('REST_RECEIVING_STAFF',  'Receiving Staff',           'RESTAURANT', 'Receives deliveries and raises disputes',    'ACTIVE', NOW(6), NOW(6)),
('REST_FINANCE_STAFF',    'Finance Staff',             'RESTAURANT', 'Handles payments and credit',                'ACTIVE', NOW(6), NOW(6)),
('SUP_OWNER',             'Supplier Owner',            'SUPPLIER',   'Full control of the supplier organisation',  'ACTIVE', NOW(6), NOW(6)),
('SUP_ADMIN',             'Supplier Admin',            'SUPPLIER',   'Manages stores, users and catalog',          'ACTIVE', NOW(6), NOW(6)),
('SUP_STORE_MANAGER',     'Store Manager',             'SUPPLIER',   'Runs a supplier store',                      'ACTIVE', NOW(6), NOW(6)),
('SUP_SALESPERSON',       'Salesperson',               'SUPPLIER',   'Responds to orders and manages catalog',     'ACTIVE', NOW(6), NOW(6)),
('SUP_OPERATIONS_STAFF',  'Operations Staff',          'SUPPLIER',   'Prepares and dispatches orders',             'ACTIVE', NOW(6), NOW(6)),
('SUP_FINANCE_STAFF',     'Finance Staff',             'SUPPLIER',   'Handles credit and settlements',             'ACTIVE', NOW(6), NOW(6)),
('OPS_MARKETPLACE',       'Marketplace Operations',    'INTERNAL',   'Day-to-day marketplace operations',          'ACTIVE', NOW(6), NOW(6)),
('OPS_SUPPLIER_VERIFY',   'Supplier Verification',     'INTERNAL',   'Reviews supplier verification',              'ACTIVE', NOW(6), NOW(6)),
('OPS_SUPPORT',           'Customer Support',          'INTERNAL',   'Read-only support access',                   'ACTIVE', NOW(6), NOW(6)),
('OPS_FINANCE',           'Finance',                   'INTERNAL',   'Payments, refunds and settlements',          'ACTIVE', NOW(6), NOW(6)),
('OPS_MODERATION',        'Moderation',                'INTERNAL',   'Catalog, rating and dispute moderation',     'ACTIVE', NOW(6), NOW(6)),
('OPS_ADMIN',             'Marketplace Administration','INTERNAL',   'Configuration and elevated administration',  'ACTIVE', NOW(6), NOW(6)),
('OPS_DELIVERY',          'Delivery Operations',       'INTERNAL',   'Delivery inspection and reassignment',       'ACTIVE', NOW(6), NOW(6));

-- ── Role → permission ────────────────────────────────────────────────────
-- Written as code-joined inserts so a reviewer can read the intent rather than
-- decode surrogate ids.

-- Restaurant Owner and Admin: everything in the restaurant world.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.scope IN ('RESTAURANT', 'SHARED')
 WHERE r.code IN ('REST_OWNER', 'REST_ADMIN');

-- Purchase Manager: the full procurement cycle, including approval, but not
-- restaurant-level administration.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('RESTAURANT_VIEW','OUTLET_VIEW','REQUIREMENT_CREATE','REQUIREMENT_EDIT',
                'PROCUREMENT_CREATE','PROCUREMENT_SUBMIT','PROCUREMENT_APPROVE',
                'ORDER_VIEW','ORDER_CANCEL','ORDER_RECEIVE','DISPUTE_CREATE','RATING_CREATE',
                'CREDIT_VIEW','CREDIT_REQUEST','PAYMENT_CREATE','NOTIFICATION_VIEW')
 WHERE r.code = 'REST_PURCHASE_MANAGER';

-- Store Manager: runs the outlet, can order, cannot approve their own orders.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('OUTLET_VIEW','OUTLET_EDIT','REQUIREMENT_CREATE','REQUIREMENT_EDIT',
                'PROCUREMENT_CREATE','PROCUREMENT_SUBMIT','ORDER_VIEW','ORDER_RECEIVE',
                'DISPUTE_CREATE','RATING_CREATE','NOTIFICATION_VIEW')
 WHERE r.code = 'REST_STORE_MANAGER';

-- Procurement Staff: prepares orders, cannot submit them. The separation is the
-- point — it is what makes an approval policy meaningful.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('OUTLET_VIEW','REQUIREMENT_CREATE','REQUIREMENT_EDIT','PROCUREMENT_CREATE',
                'ORDER_VIEW','NOTIFICATION_VIEW')
 WHERE r.code = 'REST_PROCUREMENT_STAFF';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('OUTLET_VIEW','ORDER_VIEW','ORDER_RECEIVE','DISPUTE_CREATE','RATING_CREATE',
                'NOTIFICATION_VIEW')
 WHERE r.code = 'REST_RECEIVING_STAFF';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('OUTLET_VIEW','ORDER_VIEW','PAYMENT_CREATE','CREDIT_VIEW','CREDIT_REQUEST',
                'NOTIFICATION_VIEW')
 WHERE r.code = 'REST_FINANCE_STAFF';

-- Supplier Owner and Admin: everything in the supplier world.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.scope IN ('SUPPLIER', 'SHARED')
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN');

-- Store Manager: runs one store end to end, including credit decisions, which
-- are the supplier's own commercial risk (doc 01 §18).
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('STORE_VIEW','STORE_EDIT','CATALOG_VIEW','CATALOG_EDIT','CATALOG_IMPORT',
                'ORDER_VIEW','ORDER_ACCEPT','ORDER_PARTIAL_ACCEPT','ORDER_REJECT',
                'ORDER_PREPARE','ORDER_READY','CREDIT_REQUEST_VIEW','CREDIT_VIEW',
                'PERFORMANCE_VIEW','NOTIFICATION_VIEW')
 WHERE r.code = 'SUP_STORE_MANAGER';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('STORE_VIEW','CATALOG_VIEW','CATALOG_EDIT','ORDER_VIEW','ORDER_ACCEPT',
                'ORDER_PARTIAL_ACCEPT','ORDER_REJECT','NOTIFICATION_VIEW')
 WHERE r.code = 'SUP_SALESPERSON';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('STORE_VIEW','CATALOG_VIEW','ORDER_VIEW','ORDER_PREPARE','ORDER_READY',
                'NOTIFICATION_VIEW')
 WHERE r.code = 'SUP_OPERATIONS_STAFF';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('STORE_VIEW','ORDER_VIEW','CREDIT_REQUEST_VIEW','CREDIT_VIEW','CREDIT_APPROVE',
                'CREDIT_REJECT','CREDIT_MODIFY','SETTLEMENT_VIEW','NOTIFICATION_VIEW')
 WHERE r.code = 'SUP_FINANCE_STAFF';

-- Internal. Doc 09 §13: support reads, it does not mutate. Financial mutations
-- need finance-specific permissions, so those roles stay narrow.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('ORDER_SUPPORT','ORDER_VIEW','CREDIT_VIEW','AUDIT_VIEW')
 WHERE r.code = 'OPS_SUPPORT';

-- Note SUPPLIER_VIEW is deliberately NOT granted here, even though a reviewer
-- plainly needs to see the supplier they are reviewing. SUPPLIER_VIEW is a
-- *tenant* permission, and no internal role holds one — that is the property
-- that makes a PLATFORM-scoped grant safe to accept at any scope (see
-- ScopeType.PLATFORM), and PermissionCatalogIT enforces it.
--
-- The review endpoint does not need it: it is authorized by SUPPLIER_VERIFY and
-- reads the organisation directly. Operations *read* endpoints arrive in Phase 14
-- and should introduce explicit INTERNAL inspection permissions
-- (SUPPLIER_INSPECT, alongside the existing ORDER_SUPPORT) rather than borrowing
-- the tenant's own.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('SUPPLIER_VERIFY','AUDIT_VIEW')
 WHERE r.code = 'OPS_SUPPLIER_VERIFY';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('PAYMENT_RECONCILE','SETTLEMENT_OPERATE','CREDIT_AUDIT','ORDER_SUPPORT',
                'ORDER_VIEW','CREDIT_VIEW','AUDIT_VIEW')
 WHERE r.code = 'OPS_FINANCE';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('CATALOG_MODERATE','DISPUTE_MODERATE','AUDIT_VIEW')
 WHERE r.code = 'OPS_MODERATION';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('DELIVERY_OPERATE','ORDER_SUPPORT','ORDER_VIEW','AUDIT_VIEW')
 WHERE r.code = 'OPS_DELIVERY';

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.code IN ('SUPPLIER_VERIFY','SUPPLIER_SUSPEND','CATALOG_MODERATE','ORDER_SUPPORT',
                'ORDER_VIEW','DISPUTE_MODERATE','DELIVERY_OPERATE','CREDIT_AUDIT',
                'CREDIT_VIEW','AUDIT_VIEW')
 WHERE r.code = 'OPS_MARKETPLACE';

-- OPS_ADMIN below receives every INTERNAL and SHARED permission, and therefore
-- also holds no tenant permission. An internal operator can read across the
-- marketplace; none can act as a restaurant or a supplier.

-- Every internal permission. The only role that can change configuration.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p
  ON p.scope IN ('INTERNAL', 'SHARED')
 WHERE r.code = 'OPS_ADMIN';
