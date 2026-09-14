-- V12 — Supplier credit.
--
-- Doc 01 §18–19, doc 02 §3, doc 03 §8–9, doc 04 §13, doc 10 §3.
--
-- **Credit is supplier-funded and supplier-controlled.** Mandi runs the workflow,
-- the ledger and the reconciliation; it does not fund credit, guarantee
-- receivables, own them, or bear the losses (doc 01 §18). Nothing in this schema
-- should ever imply otherwise — there is no platform balance here, only a record
-- of what one supplier extended to one restaurant outlet.
--
-- The exposure identity, from doc 01 §18 and doc 10 §3:
--
--     available = approved_limit - reserved - utilized
--     approved_limit = reserved + utilized + available
--     available >= 0
--
-- `available` is **derived, never stored**. A stored copy is a second source of
-- truth for the same number, and the two drift the first time a write partially
-- fails. Every read computes it; the CHECK constraint below enforces that it
-- cannot go negative no matter which code path did the arithmetic.

-- One supplier store's credit line to one restaurant outlet. Doc 01 §18, doc 03 §8.
--
-- Store-and-outlet specific, not organisation-wide (v2.2 §14). A supplier trading
-- from two cities carries different risk in each, and a restaurant group's
-- outlets are different businesses to collect from.
--
-- **This row carries the live exposure.** reserved and utilized are mutated only
-- by conditional UPDATEs that re-check the limit in the same statement, which is
-- what makes the reservation race safe — see CreditExposureStore.
CREATE TABLE credit_agreement (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id       BIGINT       NOT NULL,
    restaurant_id   BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    supplier_organization_id BIGINT NOT NULL,

    -- REQUESTED → APPROVED → ACTIVE, plus REJECTED, SUSPENDED, EXPIRED, CLOSED.
    -- Doc 03 §8. APPROVED is not yet usable: it means the supplier agreed to terms
    -- that differ from the ask, and the restaurant has not yet accepted them.
    status          VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',

    approved_limit  DECIMAL(19,4) NOT NULL DEFAULT 0,
    credit_period_days INT       NOT NULL DEFAULT 0,
    grace_period_days INT        NOT NULL DEFAULT 0,
    -- A ceiling on any single order, independent of the limit. A supplier may be
    -- happy to carry ₹2,00,000 across a month of ordinary orders and not ₹2,00,000
    -- in one (doc 01 §18).
    max_single_order_credit DECIMAL(19,4) NULL,
    max_overdue_amount DECIMAL(19,4) NULL,
    auto_suspend_enabled TINYINT(1) NOT NULL DEFAULT 1,

    -- Held against orders placed but not yet accepted.
    reserved_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- Drawn down: accepted and not yet repaid.
    utilized_amount DECIMAL(19,4) NOT NULL DEFAULT 0,

    effective_from  DATE         NULL,
    review_date     DATE         NULL,
    expires_at      TIMESTAMP(6) NULL,
    activated_at    TIMESTAMP(6) NULL,
    suspended_at    TIMESTAMP(6) NULL,
    suspension_reason VARCHAR(500) NULL,
    closed_at       TIMESTAMP(6) NULL,
    -- Bumped on every supplier modification. Doc 04 §13: "supplier modification
    -- must be explicit and versioned" — an agreement's terms must be quotable as
    -- of a version, because an order was placed under one of them.
    terms_version   INT          NOT NULL DEFAULT 1,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_agreement_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_agreement_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurant (id),
    CONSTRAINT fk_credit_agreement_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_agreement_org FOREIGN KEY (supplier_organization_id) REFERENCES supplier_organization (id),
    -- One live credit line per pair. Without this, a second request would create a
    -- second limit and the exposure would be the sum of two numbers nobody agreed to.
    CONSTRAINT uk_credit_agreement_pair UNIQUE (outlet_id, supplier_store_id),
    -- Doc 10 §3. The invariant, in the database: available can never go negative,
    -- whichever code path did the arithmetic.
    CONSTRAINT ck_credit_available CHECK (reserved_amount + utilized_amount <= approved_limit),
    CONSTRAINT ck_credit_reserved CHECK (reserved_amount >= 0),
    CONSTRAINT ck_credit_utilized CHECK (utilized_amount >= 0),
    KEY ix_credit_agreement_store_status (supplier_store_id, status),
    KEY ix_credit_agreement_outlet_status (outlet_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The restaurant's ask, and the negotiation that follows it. Doc 04 §13, doc 05 §20.
--
-- Separate from credit_agreement because a request is a conversation — asked,
-- queried, modified, accepted or refused — while an agreement is the commercial
-- instrument that results. Keeping the trail here means an agreement's terms can
-- be compared against what was originally asked for, months later.
CREATE TABLE credit_request (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT   NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    requested_limit DECIMAL(19,4) NOT NULL,
    requested_period_days INT    NOT NULL,
    purpose         VARCHAR(64)  NULL,
    note            VARCHAR(1000) NULL,
    -- REQUESTED → APPROVED | MODIFIED | REJECTED | INFO_REQUESTED. Doc 05 §20.
    status          VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    -- What the supplier said, in their words. Shown to the restaurant verbatim:
    -- "rejected" with no reason is a dead end for someone who would happily have
    -- supplied whatever was missing.
    response_note   VARCHAR(1000) NULL,
    responded_by    BIGINT       NULL,
    responded_at    TIMESTAMP(6) NULL,
    requested_by    BIGINT       NOT NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_request_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_request_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_request_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_credit_request_by FOREIGN KEY (requested_by) REFERENCES users (id),
    KEY ix_credit_request_store_status (supplier_store_id, status),
    KEY ix_credit_request_agreement (credit_agreement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every change to the limit or terms, append-only. Doc 01 §18: "supplier may
-- manually adjust exposure/limit with audit".
--
-- Append-only on purpose: a limit reduction that a restaurant disputes is
-- answered by the row, not by the current value.
CREATE TABLE credit_limit_history (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT   NOT NULL,
    terms_version   INT          NOT NULL,
    previous_limit  DECIMAL(19,4) NULL,
    new_limit       DECIMAL(19,4) NOT NULL,
    previous_period_days INT     NULL,
    new_period_days INT          NOT NULL,
    -- REQUEST, APPROVAL, MODIFICATION, MANUAL_ADJUSTMENT, AUTO_REDUCTION.
    change_type     VARCHAR(32)  NOT NULL,
    -- Never nullable. An unexplained limit change is the thing doc 01 §18's audit
    -- requirement exists to prevent.
    reason          VARCHAR(500) NOT NULL,
    changed_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_history_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_history_by FOREIGN KEY (changed_by) REFERENCES users (id),
    KEY ix_credit_history_agreement (credit_agreement_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Credit held against one supplier order. Doc 01 §19, doc 03 §9.
--
-- REQUESTED → RESERVED → UTILIZED, or RESERVED → RELEASED | EXPIRED. Tied to a
-- supplier order, because that is the thing whose acceptance decides which way
-- it goes.
CREATE TABLE credit_reservation (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT   NOT NULL,
    supplier_order_id BIGINT     NOT NULL,
    procurement_id  BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'REQUESTED',
    -- What was held when the order was placed: the full order value, because what
    -- the supplier will accept is not yet known.
    reserved_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- What was actually drawn: the accepted value. Below reserved_amount after a
    -- partial acceptance, zero after a rejection (doc 01 §19).
    utilized_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    released_amount DECIMAL(19,4) NOT NULL DEFAULT 0,
    failure_code    VARCHAR(64)  NULL,
    failure_reason  VARCHAR(500) NULL,
    reserved_at     TIMESTAMP(6) NULL,
    utilized_at     TIMESTAMP(6) NULL,
    released_at     TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_reservation_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_reservation_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_credit_reservation_procurement FOREIGN KEY (procurement_id) REFERENCES procurement (id),
    -- One reservation per order — the database guard behind the reservation race
    -- (doc 10 §2). Two concurrent submissions cannot hold credit twice for the
    -- same order, whatever the callers did.
    CONSTRAINT uk_credit_reservation_order UNIQUE (supplier_order_id),
    CONSTRAINT ck_credit_reservation_utilized CHECK (utilized_amount <= reserved_amount),
    KEY ix_credit_reservation_agreement (credit_agreement_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- What the restaurant owes for one drawn-down order. Doc 01 §18 ("invoice/dues
-- view"), doc 10 §1 scenario 7.
--
-- Raised when credit is *utilized*, not when the order is placed: until a
-- supplier accepts, nothing has been supplied and nothing is owed.
CREATE TABLE credit_invoice (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT   NOT NULL,
    supplier_order_id BIGINT     NOT NULL,
    outlet_id       BIGINT       NOT NULL,
    supplier_store_id BIGINT     NOT NULL,
    invoice_number  VARCHAR(40)  NOT NULL,
    -- ISSUED → PARTIALLY_PAID → PAID, or ISSUED/PARTIALLY_PAID → OVERDUE → PAID.
    status          VARCHAR(32)  NOT NULL DEFAULT 'ISSUED',
    amount          DECIMAL(19,4) NOT NULL,
    paid_amount     DECIMAL(19,4) NOT NULL DEFAULT 0,
    issued_at       TIMESTAMP(6) NOT NULL,
    -- issued_at + credit_period_days, snapshotted. Recomputing it later from the
    -- agreement would silently re-date every existing invoice the moment a
    -- supplier changed their terms.
    due_date        DATE         NOT NULL,
    -- due_date + grace_period_days. Overdue begins here, not at due_date.
    overdue_after   DATE         NOT NULL,
    marked_overdue_at TIMESTAMP(6) NULL,
    settled_at      TIMESTAMP(6) NULL,

    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_invoice_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_invoice_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_credit_invoice_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_invoice_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT uk_credit_invoice_number UNIQUE (invoice_number),
    -- One invoice per order. A second would double what is owed.
    CONSTRAINT uk_credit_invoice_order UNIQUE (supplier_order_id),
    CONSTRAINT ck_credit_invoice_paid CHECK (paid_amount <= amount),
    KEY ix_credit_invoice_agreement_status (credit_agreement_id, status),
    KEY ix_credit_invoice_due (status, overdue_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The ledger. Append-only. Doc 02 §3, doc 09 §11.
--
-- The agreement row says where the exposure stands; this says how it got there.
-- Each row snapshots the balances *after* it was applied, so the ledger can be
-- read forwards to reconstruct any point in time without replaying arithmetic —
-- and so a disagreement about today's number can be traced to the movement that
-- caused it.
CREATE TABLE credit_transaction (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_agreement_id BIGINT   NOT NULL,
    credit_reservation_id BIGINT NULL,
    supplier_order_id BIGINT     NULL,
    credit_invoice_id BIGINT     NULL,
    -- RESERVE, UTILIZE, RELEASE, REPAYMENT, LIMIT_CHANGE, ADJUSTMENT.
    transaction_type VARCHAR(32) NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    balance_reserved_after DECIMAL(19,4) NOT NULL,
    balance_utilized_after DECIMAL(19,4) NOT NULL,
    balance_available_after DECIMAL(19,4) NOT NULL,
    description     VARCHAR(500) NULL,
    created_by      BIGINT       NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_txn_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_txn_reservation FOREIGN KEY (credit_reservation_id) REFERENCES credit_reservation (id),
    CONSTRAINT fk_credit_txn_order FOREIGN KEY (supplier_order_id) REFERENCES supplier_order (id),
    CONSTRAINT fk_credit_txn_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    KEY ix_credit_txn_agreement (credit_agreement_id, created_at),
    KEY ix_credit_txn_order (supplier_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A repayment recorded against an invoice. Append-only. Doc 10 §1 scenario 7.
--
-- **Recorded, not collected.** The money moves between the restaurant and the
-- supplier directly; Mandi reconciles it (doc 01 §18: Mandi does not fund credit,
-- own receivables or perform recovery). So this table has no provider reference
-- and no capture — it is the supplier confirming they were paid.
CREATE TABLE credit_payment (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    credit_invoice_id BIGINT     NOT NULL,
    credit_agreement_id BIGINT   NOT NULL,
    amount          DECIMAL(19,4) NOT NULL,
    -- BANK_TRANSFER, UPI, CASH, CHEQUE, ADJUSTMENT.
    method          VARCHAR(32)  NOT NULL,
    reference       VARCHAR(200) NULL,
    note            VARCHAR(500) NULL,
    paid_at         TIMESTAMP(6) NOT NULL,
    recorded_by     BIGINT       NULL,
    -- Doc 04 §21: a repeated recording must not reduce the debt twice.
    idempotency_key VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_credit_payment_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_credit_payment_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_credit_payment_by FOREIGN KEY (recorded_by) REFERENCES users (id),
    CONSTRAINT uk_credit_payment_key UNIQUE (idempotency_key),
    CONSTRAINT ck_credit_payment_amount CHECK (amount > 0),
    KEY ix_credit_payment_invoice (credit_invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Invoice numbering, mirroring order_number_sequence in V10. A sequence table
-- rather than the invoice id: a gap-free, date-prefixed number is what a finance
-- team reconciles against, and the id is an implementation detail.
CREATE TABLE credit_invoice_sequence (
    sequence_date   DATE         NOT NULL,
    next_value      BIGINT       NOT NULL DEFAULT 1,
    updated_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (sequence_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
