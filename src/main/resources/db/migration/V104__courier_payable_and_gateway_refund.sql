-- Courier payable and durable gateway refunds (D-204).
--
-- A supplier deduction or a direct supplier payment is a COLLECTION: money coming in from a supplier or a restaurant.
-- Neither proves the courier was paid. courier_payable is what Costonomy owes the delivery provider for one booked
-- courier, its payment history, and what it was funded from.

-- How a provider is paid. MANUAL means no API: a person pays by bank transfer or against an invoice and records it.
ALTER TABLE delivery_provider
    ADD COLUMN payment_rail VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

-- One row per booked courier (a delivery_provider_attempt or food_delivery_attempt that reached the provider). A
-- reassignment books a new courier and so makes a new row; the old one is voided, or kept for a cancellation fee.
CREATE TABLE courier_payable (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type           VARCHAR(24)   NOT NULL,               -- SUPPLIER_DELIVERY | FOOD_DELIVERY
    delivery_id           BIGINT        NOT NULL,               -- delivery.id or food_delivery.id, by source_type
    attempt_id            BIGINT        NOT NULL,               -- the booking attempt, same table choice
    supplier_order_id     BIGINT        NULL,
    outlet_id             BIGINT        NULL,
    supplier_store_id     BIGINT        NULL,
    provider_code         VARCHAR(64)   NOT NULL,
    provider_delivery_id  VARCHAR(200)  NULL,
    currency              CHAR(3)       NOT NULL DEFAULT 'INR',

    -- What the courier costs. quoted_cost is what we agreed at booking; final_cost is the provider's charge once known
    -- (null until then). amount_due is what we owe now: nothing while the courier is only booked, the final cost once the
    -- goods are with the courier, or the cancellation fee if it was cancelled.
    quoted_cost           DECIMAL(19,4) NOT NULL,
    final_cost            DECIMAL(19,4) NULL,
    -- Set when a person (or the provider's statement) fixed final_cost: the automatic sync then leaves it alone.
    final_cost_manual     TINYINT(1)    NOT NULL DEFAULT 0,
    cancellation_fee      DECIMAL(19,4) NOT NULL DEFAULT 0,
    provider_credit       DECIMAL(19,4) NOT NULL DEFAULT 0,
    amount_due            DECIMAL(19,4) NOT NULL DEFAULT 0,
    amount_paid           DECIMAL(19,4) NOT NULL DEFAULT 0,

    -- Who funds it. buyer + supplier + platform = the cost incurred. The buyer's and supplier's parts are fixed by what
    -- they agreed to at checkout and are never raised afterwards; the supplier's is also never more than the cost. The
    -- platform bears the rest, and the difference between quote and final cost, so it can be negative (a margin).
    buyer_contribution    DECIMAL(19,4) NOT NULL DEFAULT 0,
    supplier_contribution DECIMAL(19,4) NOT NULL DEFAULT 0,
    platform_funded       DECIMAL(19,4) NOT NULL DEFAULT 0,

    -- ACCRUED (booked, nothing owed yet), DUE, PROCESSING (a payment is in flight under a lease), FAILED (retry later),
    -- EXHAUSTED (retries used up, a person must act), AMBIGUOUS (a payment may or may not have gone), PAID, VOID.
    state                 VARCHAR(16)   NOT NULL,
    due_reason            VARCHAR(32)   NULL,                   -- PICKED_UP, DELIVERED, CANCELLATION_FEE, POST_PICKUP_FAILURE, MANUAL
    due_at                TIMESTAMP(6)  NULL,
    payment_rail          VARCHAR(32)   NULL,
    payment_reference     VARCHAR(200)  NULL,
    paid_at               TIMESTAMP(6)  NULL,

    attempt_count         INT           NOT NULL DEFAULT 0,
    last_error            VARCHAR(500)  NULL,
    next_retry_at         TIMESTAMP(6)  NULL,
    claim_token           VARCHAR(64)   NULL,
    claim_expires_at      TIMESTAMP(6)  NULL,

    -- NONE, REQUIRED (the amounts no longer agree with what was paid, or the provider's statement disagrees), RESOLVED.
    reconciliation_state  VARCHAR(16)   NOT NULL DEFAULT 'NONE',
    reconciliation_note   VARCHAR(500)  NULL,

    created_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uk_courier_payable_attempt UNIQUE (source_type, delivery_id, attempt_id),
    CONSTRAINT ck_courier_payable_source CHECK (source_type IN ('SUPPLIER_DELIVERY', 'FOOD_DELIVERY')),
    CONSTRAINT ck_courier_payable_state CHECK (state IN
        ('ACCRUED', 'DUE', 'PROCESSING', 'FAILED', 'EXHAUSTED', 'AMBIGUOUS', 'PAID', 'VOID')),
    CONSTRAINT ck_courier_payable_reconciliation CHECK (reconciliation_state IN ('NONE', 'REQUIRED', 'RESOLVED')),
    CONSTRAINT ck_courier_payable_amounts CHECK (
        quoted_cost >= 0 AND cancellation_fee >= 0 AND provider_credit >= 0 AND amount_due >= 0 AND amount_paid >= 0
        AND buyer_contribution >= 0 AND supplier_contribution >= 0 AND (final_cost IS NULL OR final_cost >= 0)),
    KEY ix_courier_payable_work (state, next_retry_at),
    KEY ix_courier_payable_delivery (source_type, delivery_id),
    KEY ix_courier_payable_order (supplier_order_id),
    KEY ix_courier_payable_provider (provider_code, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Every payment try, with the key the rail was given, so an unknown outcome can be asked about and never repeated blindly.
CREATE TABLE courier_payment_attempt (
    id                BIGINT PRIMARY KEY AUTO_INCREMENT,
    payable_id        BIGINT        NOT NULL,
    attempt_number    INT           NOT NULL,
    rail              VARCHAR(32)   NOT NULL,
    idempotency_key   VARCHAR(100)  NOT NULL,
    amount            DECIMAL(19,4) NOT NULL,
    status            VARCHAR(16)   NOT NULL,                   -- SENDING, PAID, FAILED, AMBIGUOUS
    reference         VARCHAR(200)  NULL,
    error             VARCHAR(500)  NULL,
    started_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at          TIMESTAMP(6)  NULL,
    created_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT fk_courier_payment_attempt_payable FOREIGN KEY (payable_id) REFERENCES courier_payable (id),
    CONSTRAINT uk_courier_payment_attempt_key UNIQUE (idempotency_key),
    CONSTRAINT uk_courier_payment_attempt_number UNIQUE (payable_id, attempt_number),
    CONSTRAINT ck_courier_payment_attempt_status CHECK (status IN ('SENDING', 'PAID', 'FAILED', 'AMBIGUOUS')),
    CONSTRAINT ck_courier_payment_attempt_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Append-only history: what changed, from what, to what, by whom.
CREATE TABLE courier_payable_event (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    payable_id    BIGINT        NOT NULL,
    event_type    VARCHAR(40)   NOT NULL,
    from_state    VARCHAR(16)   NULL,
    to_state      VARCHAR(16)   NULL,
    amount        DECIMAL(19,4) NULL,
    actor         VARCHAR(64)   NOT NULL,                       -- SYSTEM, JOB, or user:<id>
    reference     VARCHAR(200)  NULL,
    detail        VARCHAR(1000) NULL,
    created_at    TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_courier_payable_event_payable FOREIGN KEY (payable_id) REFERENCES courier_payable (id),
    KEY ix_courier_payable_event_payable (payable_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A refund of a gateway payment, recorded BEFORE the gateway is asked and reconciled after (D-204). Replaces refunding
-- by calling the gateway and writing a flag on the row afterwards, which left an unknown outcome unrecorded.
CREATE TABLE gateway_refund (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type         VARCHAR(32)   NOT NULL,                 -- FOOD_DELIVERY | SUPPLIER_DELIVERY_FUNDING
    source_id           BIGINT        NOT NULL,
    purpose             VARCHAR(32)   NOT NULL,                 -- CANCELLED, NO_COURIER, FEE_ADJUSTMENT, UNUSABLE_PAYMENT, DUPLICATE_PAYMENT
    provider_payment_id VARCHAR(200)  NOT NULL,
    captured_amount     DECIMAL(19,4) NOT NULL,                 -- what the gateway captured, to bound the total refunded
    amount              DECIMAL(19,4) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',
    reason              VARCHAR(300)  NULL,
    idempotency_key     VARCHAR(100)  NOT NULL,
    -- REQUESTED (recorded, not yet sent), SENDING (a lease holds it), SUCCEEDED, FAILED (retry later),
    -- EXHAUSTED (retries used up, a person must act), AMBIGUOUS (the gateway may or may not have refunded).
    status              VARCHAR(16)   NOT NULL,
    provider_refund_id  VARCHAR(200)  NULL,
    attempt_count       INT           NOT NULL DEFAULT 0,
    lookup_count        INT           NOT NULL DEFAULT 0,
    last_error          VARCHAR(500)  NULL,
    next_retry_at       TIMESTAMP(6)  NULL,
    claim_token         VARCHAR(64)   NULL,
    claim_expires_at    TIMESTAMP(6)  NULL,
    requested_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at        TIMESTAMP(6)  NULL,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uk_gateway_refund_key UNIQUE (idempotency_key),
    CONSTRAINT ck_gateway_refund_source CHECK (source_type IN ('FOOD_DELIVERY', 'SUPPLIER_DELIVERY_FUNDING')),
    CONSTRAINT ck_gateway_refund_status CHECK (status IN
        ('REQUESTED', 'SENDING', 'SUCCEEDED', 'FAILED', 'EXHAUSTED', 'AMBIGUOUS')),
    CONSTRAINT ck_gateway_refund_amount CHECK (amount > 0 AND captured_amount >= amount),
    KEY ix_gateway_refund_work (status, next_retry_at),
    KEY ix_gateway_refund_source (source_type, source_id),
    KEY ix_gateway_refund_payment (provider_payment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- How much of each gateway payment is already refunded or on its way, kept in one row per payment and raised only by a
-- single conditional UPDATE, so two requests cannot both fit under what was captured (D-204).
CREATE TABLE gateway_refund_reservation (
    provider_payment_id VARCHAR(200)  PRIMARY KEY,
    captured_amount     DECIMAL(19,4) NOT NULL,
    reserved_amount     DECIMAL(19,4) NOT NULL DEFAULT 0,
    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_gateway_refund_reservation CHECK (reserved_amount >= 0 AND reserved_amount <= captured_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A gateway-paid food delivery is refunded to the gateway, and the wallet is not touched. Which of the two paid is a
-- fact of the delivery, not something the refund path guesses (D-204). The fee actually held or captured:
ALTER TABLE food_delivery
    ADD COLUMN gateway_refunded_amount DECIMAL(19,4) NOT NULL DEFAULT 0;

-- A supplier charged for a courier share that the courier's final cost turns out not to need is given the excess back
-- (D-204): a PENDING charge is reduced, a charge already taken from a payout is corrected by a credit that the next
-- settlement of that store applies, and a direct payment is partly refunded at the gateway. The credit is its own row,
-- linked to the charge it corrects.
ALTER TABLE supplier_deduction
    ADD COLUMN corrects_deduction_id BIGINT NULL,
    ADD CONSTRAINT fk_supplier_deduction_corrects FOREIGN KEY (corrects_deduction_id) REFERENCES supplier_deduction (id),
    ADD KEY ix_supplier_deduction_credit (kind, status, supplier_store_id);

ALTER TABLE supplier_deduction DROP CHECK ck_supplier_deduction_source;
ALTER TABLE supplier_deduction ADD CONSTRAINT ck_supplier_deduction_source CHECK (
    (kind = 'DISPUTE_REFUND' AND dispute_refund_id IS NOT NULL AND delivery_id IS NULL AND corrects_deduction_id IS NULL)
    OR (kind = 'DELIVERY_FUNDING' AND delivery_id IS NOT NULL AND dispute_refund_id IS NULL AND corrects_deduction_id IS NULL)
    OR (kind = 'DELIVERY_FUNDING_CREDIT' AND corrects_deduction_id IS NOT NULL AND delivery_id IS NULL
        AND dispute_refund_id IS NULL));

-- Finance on deliveries: the courier payable, the courier payments, and the gateway refunds. Reading is separate from
-- acting (D-046): support may see the queues and move nothing.
INSERT INTO permission (code, name, scope, description, created_at, updated_at) VALUES
('DELIVERY_FINANCE_INSPECT', 'Inspect delivery finance', 'INTERNAL',
 'See courier payables, courier payments, gateway refunds and their queues', NOW(6), NOW(6)),
('DELIVERY_FINANCE_OPERATE', 'Operate delivery finance', 'INTERNAL',
 'Record a courier payment, retry, void or adjust a payable, and retry or resolve a gateway refund', NOW(6), NOW(6));

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'DELIVERY_FINANCE_INSPECT'
 WHERE r.code IN ('OPS_FINANCE', 'OPS_ADMIN', 'OPS_SUPPORT');

INSERT INTO role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW(6) FROM role r JOIN permission p ON p.code = 'DELIVERY_FINANCE_OPERATE'
 WHERE r.code IN ('OPS_FINANCE', 'OPS_ADMIN');
