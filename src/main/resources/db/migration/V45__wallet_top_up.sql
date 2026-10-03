-- V45 — Adding money to the wallet through Razorpay (D-107).
--
-- Until now the only way to fill a wallet was a mock endpoint that credits a
-- balance with no money behind it. A real top-up is a Razorpay payment: the
-- restaurant pays, Razorpay captures it into our balance, and only then does the
-- wallet ledger say so. This table is the record of one such attempt — created
-- before the restaurant is sent to pay, so that money arriving for it can always
-- be matched to something, and so that "did that top-up ever land?" has a row
-- to look at rather than a hole.
--
-- The wallet balance is still a column moved only by WalletService. This table
-- never changes it; it decides *whether* WalletService may, once, for this
-- payment.

CREATE TABLE wallet_top_up (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    outlet_id           BIGINT        NOT NULL,
    created_by          BIGINT        NOT NULL,

    -- Exactly what Razorpay is asked to collect and exactly what may be credited.
    -- The client's word for the amount is never used after this row is written.
    amount              DECIMAL(19,4) NOT NULL,

    -- CREATED        waiting for the restaurant's payment to be captured
    -- CREDITED       captured, and the wallet credited (terminal)
    -- REFUND_PENDING captured, but crediting would have broken a wallet limit, so
    --                the payment is being returned to its source
    -- REFUNDED       that return has completed (terminal)
    -- FAILED         the Razorpay order could not be created; nothing was ever
    --                payable (terminal)
    -- EXPIRED        nobody paid within a day and Razorpay said so (terminal, but
    --                a payment that turns up late is still credited)
    status              VARCHAR(32)   NOT NULL,

    -- NULL only for the moment between inserting the row and Razorpay answering.
    -- Unique: one Razorpay order is one top-up, which is what makes "this payment
    -- belongs to this top-up" a lookup instead of a judgement.
    razorpay_order_id   VARCHAR(64)   NULL,

    -- Set in the same statement that moves the row out of CREATED. Unique, so a
    -- captured payment can never be credited to two top-ups even if every other
    -- guard were wrong.
    razorpay_payment_id VARCHAR(64)   NULL,

    -- The provider refund returning a payment that could not be credited.
    provider_refund_id  VARCHAR(64)   NULL,
    refund_attempts     INT           NOT NULL DEFAULT 0,

    -- outlet-{outletId}-{client key}: the same client key from two outlets cannot
    -- collide, and a replay after the idempotency record has expired still finds
    -- this row instead of opening a second Razorpay order.
    idempotency_key     VARCHAR(200)  NOT NULL,

    failure_reason      VARCHAR(500)  NULL,
    credited_at         TIMESTAMP(6)  NULL,

    -- When the poller last asked Razorpay about this top-up (see QuickScan's
    -- checked_at: an unchanged row is never rewritten, so it has to be explicit).
    checked_at          TIMESTAMP(6)  NULL,

    created_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_wallet_top_up_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT fk_wallet_top_up_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT uk_wallet_top_up_order UNIQUE (razorpay_order_id),
    CONSTRAINT uk_wallet_top_up_payment UNIQUE (razorpay_payment_id),
    CONSTRAINT uk_wallet_top_up_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_wallet_top_up_amount CHECK (amount > 0),

    KEY ix_wallet_top_up_outlet (outlet_id, status, credited_at),
    KEY ix_wallet_top_up_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
