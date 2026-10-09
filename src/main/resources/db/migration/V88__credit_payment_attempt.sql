-- V88 — Paying credit invoices online (UPI, card) or from the wallet, whole invoices, no supplier approval (D-196).
--
-- One credit_payment_attempt row per "Pay" a restaurant starts. It is written before Razorpay is asked, so a
-- payment that arrives can always be matched to something (the wallet top-up shape, V45). The invoices it covers,
-- with the outstanding amount each was charged at, are credit_payment_attempt_invoice rows. When the capture is
-- applied, each invoice is either paid (through the same applyPayment every repayment uses) or skipped because it
-- changed meanwhile; a skipped invoice's share goes back to the original card or UPI as a credit_payment_refund row.

CREATE TABLE credit_payment_attempt (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id           BIGINT        NOT NULL,
    created_by          BIGINT        NOT NULL,

    -- What the restaurant chose: UPI | CARD | WALLET. UPI and CARD are one channel (ONLINE): one Razorpay
    -- order, and the choice only pre-selects the method in Razorpay's checkout.
    method              VARCHAR(16)   NOT NULL,
    channel             VARCHAR(16)   NOT NULL,

    -- CREATED   waiting for the payment to be captured (ONLINE only)
    -- PAID      captured (or debited from the wallet) and at least one invoice paid with it (terminal)
    -- FAILED    the Razorpay order could not be created; nothing was ever payable (terminal)
    -- EXPIRED   not paid within the expiry window; a payment that turns up later is still applied
    -- REFUNDED  captured, but every invoice had changed, so all of it goes back (terminal)
    status              VARCHAR(16)   NOT NULL,

    invoice_count       INT           NOT NULL,
    -- The sum of the invoices' outstanding amounts at creation: what the suppliers are paid.
    amount              DECIMAL(19,4) NOT NULL,
    -- The fee snapshot ("decide later": 0 today). Never paid out to a supplier.
    fee_percent         DECIMAL(9,4)  NOT NULL DEFAULT 0,
    fee_amount          DECIMAL(19,4) NOT NULL DEFAULT 0,
    -- amount + fee_amount: exactly what Razorpay is asked to collect (or the wallet debited).
    total_charged       DECIMAL(19,4) NOT NULL,
    currency            CHAR(3)       NOT NULL DEFAULT 'INR',

    razorpay_order_id   VARCHAR(64)   NULL,
    -- Set in the transaction that applies the capture. Unique: one payment applies to one attempt, once.
    razorpay_payment_id VARCHAR(64)   NULL,
    -- As Razorpay reports it (upi, card, netbanking, wallet, emi ...), or WALLET for a Mandi wallet payment.
    payment_method      VARCHAR(32)   NULL,
    -- A card's last four digits or a provider wallet's name; never a full card number or a UPI address (D-108).
    payment_detail      VARCHAR(64)   NULL,

    -- outlet-{outletId}-{client Idempotency-Key}
    idempotency_key     VARCHAR(200)  NOT NULL,
    -- outlet-{outletId}-{channel}-{sha256 hex of the sorted invoice ids joined by ","}; set only while CREATED,
    -- NULL in every other state. One open attempt per selection: a second tap reopens it.
    open_selection_key  VARCHAR(120)  NULL,

    failure_reason      VARCHAR(500)  NULL,
    paid_at             TIMESTAMP(6)  NULL,
    expired_at          TIMESTAMP(6)  NULL,
    -- When the poller last asked Razorpay (an unchanged row is never rewritten, so it is explicit, as in V45).
    checked_at          TIMESTAMP(6)  NULL,
    -- When the "captured but not applied" alert was logged, so it is logged once.
    stuck_alerted_at    TIMESTAMP(6)  NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_payment_attempt_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_credit_payment_attempt_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_credit_payment_attempt_order UNIQUE (razorpay_order_id),
    CONSTRAINT uk_credit_payment_attempt_payment UNIQUE (razorpay_payment_id),
    CONSTRAINT uk_credit_payment_attempt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT uk_credit_payment_attempt_open_selection UNIQUE (open_selection_key),
    CONSTRAINT ck_credit_payment_attempt_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_payment_attempt_fee CHECK (fee_amount >= 0),
    CONSTRAINT ck_credit_payment_attempt_total CHECK (total_charged = amount + fee_amount),
    CONSTRAINT ck_credit_payment_attempt_method CHECK (method IN ('UPI', 'CARD', 'WALLET')),
    CONSTRAINT ck_credit_payment_attempt_channel CHECK (channel IN ('ONLINE', 'WALLET')),
    CONSTRAINT ck_credit_payment_attempt_status CHECK (status IN ('CREATED', 'PAID', 'FAILED', 'EXPIRED', 'REFUNDED')),

    KEY ix_credit_payment_attempt_outlet (outlet_id, created_at),
    KEY ix_credit_payment_attempt_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_payment_refund (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    attempt_id          BIGINT        NOT NULL,
    -- The captured payment the money goes back to.
    razorpay_payment_id VARCHAR(64)   NOT NULL,
    -- ORIGINAL (a Razorpay refund to the card or UPI it came from). WALLET is reserved for a later ops undo of a
    -- wallet payment; v1 never writes it.
    destination         VARCHAR(16)   NOT NULL DEFAULT 'ORIGINAL',
    -- SKIPPED_INVOICES  the shares of invoices that changed before the capture was applied
    -- DUPLICATE_CAPTURE a second captured payment on an attempt that was already applied: all of it goes back
    reason              VARCHAR(32)   NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,
    -- PENDING until Razorpay reports the refund processed, then COMPLETED. After 5 failed sends it stays PENDING
    -- and is logged at ERROR for a person (the D-101 / top-up shape).
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    provider_refund_id  VARCHAR(64)   NULL,
    attempts            INT           NOT NULL DEFAULT 0,
    last_tried_at       TIMESTAMP(6)  NULL,
    completed_at        TIMESTAMP(6)  NULL,
    failure_reason      VARCHAR(500)  NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_credit_payment_refund_attempt FOREIGN KEY (attempt_id) REFERENCES credit_payment_attempt (id),
    CONSTRAINT uk_credit_payment_refund_payment_reason UNIQUE (razorpay_payment_id, reason),
    CONSTRAINT uk_credit_payment_refund_provider UNIQUE (provider_refund_id),
    CONSTRAINT ck_credit_payment_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_payment_refund_destination CHECK (destination IN ('ORIGINAL', 'WALLET')),
    CONSTRAINT ck_credit_payment_refund_reason CHECK (reason IN ('SKIPPED_INVOICES', 'DUPLICATE_CAPTURE')),
    CONSTRAINT ck_credit_payment_refund_status CHECK (status IN ('PENDING', 'COMPLETED')),

    KEY ix_credit_payment_refund_attempt (attempt_id),
    KEY ix_credit_payment_refund_due (status, last_tried_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE credit_payment_attempt_invoice (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    attempt_id          BIGINT        NOT NULL,
    credit_invoice_id   BIGINT        NOT NULL,
    credit_agreement_id BIGINT        NOT NULL,
    supplier_store_id   BIGINT        NOT NULL,
    -- The invoice's outstanding when the attempt was created: what was charged for it, and what it must still owe,
    -- exactly, for the capture to pay it.
    expected_amount     DECIMAL(19,4) NOT NULL,
    -- PENDING | PAID | SKIPPED
    outcome             VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    -- SETTLED | WRITTEN_OFF | AMOUNT_CHANGED | SUPPLIER_UNAVAILABLE (only when SKIPPED)
    skip_reason         VARCHAR(32)   NULL,
    credit_repayment_id BIGINT        NULL,
    credit_payment_id   BIGINT        NULL,
    credit_payment_refund_id BIGINT   NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_cpai_attempt FOREIGN KEY (attempt_id) REFERENCES credit_payment_attempt (id),
    CONSTRAINT fk_cpai_invoice FOREIGN KEY (credit_invoice_id) REFERENCES credit_invoice (id),
    CONSTRAINT fk_cpai_agreement FOREIGN KEY (credit_agreement_id) REFERENCES credit_agreement (id),
    CONSTRAINT fk_cpai_store FOREIGN KEY (supplier_store_id) REFERENCES supplier_store (id),
    CONSTRAINT fk_cpai_repayment FOREIGN KEY (credit_repayment_id) REFERENCES credit_repayment (id),
    CONSTRAINT fk_cpai_payment FOREIGN KEY (credit_payment_id) REFERENCES credit_payment (id),
    CONSTRAINT fk_cpai_refund FOREIGN KEY (credit_payment_refund_id) REFERENCES credit_payment_refund (id),
    CONSTRAINT uk_cpai_attempt_invoice UNIQUE (attempt_id, credit_invoice_id),
    CONSTRAINT ck_cpai_expected CHECK (expected_amount > 0),
    CONSTRAINT ck_cpai_outcome CHECK (outcome IN ('PENDING', 'PAID', 'SKIPPED')),

    KEY ix_cpai_invoice (credit_invoice_id, outcome)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A repayment made by an attempt points back at it. One repayment per (attempt, line): the second guard, after the
-- attempt row lock, against a capture applied twice. MySQL allows many NULLs in a UNIQUE key, so wallet and
-- supplier-recorded repayments are not limited by it. provider_payment_id stays NULL for these rows: one Razorpay
-- payment funds several lines, and that column is unique.
ALTER TABLE credit_repayment
    ADD COLUMN credit_payment_attempt_id BIGINT NULL AFTER provider_payment_id,
    ADD CONSTRAINT fk_credit_repayment_attempt FOREIGN KEY (credit_payment_attempt_id) REFERENCES credit_payment_attempt (id),
    ADD CONSTRAINT uk_credit_repayment_attempt_agreement UNIQUE (credit_payment_attempt_id, credit_agreement_id);
