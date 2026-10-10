-- V43 — A refund is asked for inside a dispute, and the supplier pays for it (D-104).
--
-- Part two of D-104. A restaurant asks for money back on a dispute; the supplier
-- approves or declines; if they decline, or say nothing for 48 hours, operations
-- decides. An approved refund is credited to the restaurant's wallet (V42) and
-- taken from the supplier's payout for that order — Costonomy never funds one.

-- The request. One per dispute: a second problem is a second dispute.
CREATE TABLE dispute_refund (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    dispute_id          BIGINT        NOT NULL,
    -- Copied from the dispute so the settlement check can find requests by order
    -- without a join through trust's tables, and so a request reads on its own.
    supplier_order_id   BIGINT        NOT NULL,
    outlet_id           BIGINT        NOT NULL,
    supplier_store_id   BIGINT        NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    reason              VARCHAR(500)  NULL,

    -- REQUESTED → APPROVED | DECLINED (the supplier);
    -- DECLINED, or REQUESTED past the supplier's 48 hours → OPS_APPROVED | OPS_DECLINED.
    status              VARCHAR(32)   NOT NULL,
    requested_by        BIGINT        NOT NULL,

    supplier_decided_by BIGINT        NULL,
    supplier_decided_at TIMESTAMP(6)  NULL,
    supplier_note       VARCHAR(500)  NULL,
    ops_decided_by      BIGINT        NULL,
    ops_decided_at      TIMESTAMP(6)  NULL,
    ops_note            VARCHAR(500)  NULL,

    -- The wallet refund an approval made, for a card-paid order. A wallet-paid
    -- order's credit has no refund row; its ledger reference is dispute-refund-{id}.
    refund_id           BIGINT        NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uk_dispute_refund_dispute UNIQUE (dispute_id),
    CONSTRAINT fk_dispute_refund_dispute FOREIGN KEY (dispute_id) REFERENCES dispute (id),
    CONSTRAINT fk_dispute_refund_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_dispute_refund_refund FOREIGN KEY (refund_id) REFERENCES refund (id),
    CONSTRAINT ck_dispute_refund_amount CHECK (amount > 0),
    KEY ix_dispute_refund_order (supplier_order_id, status),
    KEY ix_dispute_refund_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What an approved refund takes from the supplier.
--
-- **Pending until there is a settlement to take it from.** An order is settled
-- the day after it completes; a refund approved before that waits here, and the
-- settlement that picks up the order picks this up with it, as a DEBIT
-- adjustment. One approved while the order's settlement is still open is applied
-- to it at once. It can never meet a settlement already approved: a refund is
-- refused once the supplier's payout for the order is approved, and a payout
-- cannot be approved while a refund on one of its orders is undecided.
CREATE TABLE supplier_deduction (
    id                       BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_store_id        BIGINT        NOT NULL,
    supplier_order_id        BIGINT        NOT NULL,
    dispute_refund_id        BIGINT        NOT NULL,
    amount                   DECIMAL(19,4) NOT NULL,
    reason                   VARCHAR(500)  NOT NULL,
    -- PENDING, or APPLIED to settlement_id as settlement_adjustment_id.
    status                   VARCHAR(16)   NOT NULL,
    settlement_id            BIGINT        NULL,
    settlement_adjustment_id BIGINT        NULL,
    applied_at               TIMESTAMP(6)  NULL,

    created_at               TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                  BIGINT        NOT NULL DEFAULT 0,

    -- One deduction per approved refund, so an approval that ran twice cannot
    -- charge the supplier twice.
    CONSTRAINT uk_supplier_deduction_refund UNIQUE (dispute_refund_id),
    CONSTRAINT fk_supplier_deduction_request FOREIGN KEY (dispute_refund_id) REFERENCES dispute_refund (id),
    CONSTRAINT fk_supplier_deduction_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_supplier_deduction_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id),
    CONSTRAINT fk_supplier_deduction_adjustment FOREIGN KEY (settlement_adjustment_id)
        REFERENCES settlement_adjustment (id),
    CONSTRAINT ck_supplier_deduction_amount CHECK (amount > 0),
    KEY ix_supplier_deduction_order (supplier_order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Deciding a refund moves the supplier's money, so it is its own permission rather
-- than DISPUTE_RESPOND: a salesperson can answer a dispute, and whether they can
-- also give the restaurant money back out of the payout is the owner's call.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('DISPUTE_REFUND_DECIDE', 'Decide dispute refunds', 'SUPPLIER',
 'Approve or decline a refund asked for on a dispute; an approval comes out of the payout',
 NOW(6), NOW(6)),
-- The operations side. Moves money, so it is separate from DISPUTE_MODERATE, which
-- records outcomes and moves nothing (D-048). Its read counterpart is
-- DISPUTE_INSPECT (D-046).
('REFUND_DECIDE', 'Decide escalated refunds', 'INTERNAL',
 'Approve or decline a dispute refund the supplier declined or did not answer',
 NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'DISPUTE_REFUND_DECIDE'
 WHERE r.code IN ('SUP_OWNER', 'SUP_ADMIN', 'SUP_STORE_MANAGER', 'SUP_FINANCE_STAFF');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'REFUND_DECIDE'
 WHERE r.code IN ('OPS_FINANCE', 'OPS_ADMIN');

-- Whoever decides must be able to see the queue.
INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'DISPUTE_INSPECT'
 WHERE r.code IN ('OPS_FINANCE', 'OPS_ADMIN')
   AND NOT EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
