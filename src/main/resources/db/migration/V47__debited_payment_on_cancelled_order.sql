-- V47 — What paid for an order, and cancellations that must return debited money (D-109).
--
-- A payment made by UPI, netbanking, a wallet app or the like debits the payer
-- the moment it is authorised, unlike a card, where the bank only puts a hold.
-- Cancelling such an order used to mark the payment RELEASED ("nothing was
-- taken") while the money had left the payer's account and Razorpay would not
-- give it back until it was captured. This migration records how each payment
-- was made and the state of a cancellation that is still returning money.
--
-- Additive only. Nothing here changes an existing row's meaning, and there is no
-- backfill: Flyway cannot ask Razorpay. The method is filled in as the payment is
-- next read from Razorpay, and a cancellation with an unknown method is decided
-- by asking Razorpay live.
--
-- (V44 to V46 are taken by branches not yet on this stack; the gap is legal.)
ALTER TABLE payment
    -- As Razorpay names it: card, upi, netbanking, wallet, emi, paylater. Not
    -- payment_method, which is the funding kind (PREPAID) other code reads.
    ADD COLUMN provider_method        VARCHAR(32)   NULL AFTER payment_method,
    -- A card's last four digits or a wallet's name. Never a UPI address, a card
    -- number or a bank account.
    ADD COLUMN provider_method_detail VARCHAR(64)   NULL AFTER provider_method,
    -- What Razorpay kept on capture, in rupees: Razorpay's `fee`, which already
    -- includes the GST it charges on it (its `tax` is a part of `fee`, not an
    -- addition to it). Costonomy's cost of a cancelled non-card order, recorded so
    -- it can be seen and reported.
    ADD COLUMN provider_fee           DECIMAL(19,4) NULL,
    -- How long, in minutes, Razorpay was told to hold this payment's authorisation
    -- (manual_expiry_period), fixed when the Razorpay order was made. What the guard
    -- on "ready" measures against, not the setting as it reads today: raising the
    -- setting later must not stretch the hold of an order made before, which Razorpay
    -- will still return at the old time. Null on a payment made before this column:
    -- those fall back to the current setting.
    ADD COLUMN hold_minutes           INT           NULL,
    -- The order was cancelled. Set whatever state the payment was in, so a payment
    -- still waiting for its money (CREATED) knows to send it straight back.
    ADD COLUMN cancel_requested_at    TIMESTAMP(6)  NULL,
    ADD COLUMN cancel_attempts        INT           NOT NULL DEFAULT 0,
    -- Why a payment ended RELEASED: CARD_HOLD_DROPPED (a card hold we let lapse) or
    -- PROVIDER_AUTO_REFUND (Razorpay returned the money itself at expiry).
    ADD COLUMN release_reason         VARCHAR(32)   NULL,
    -- A person has to look; the cancellation job skips the row until they do.
    ADD COLUMN review_required_at     TIMESTAMP(6)  NULL,
    ADD COLUMN review_reason          VARCHAR(200)  NULL;

-- The cancellation job reads CANCEL_PENDING rows least recently worked first.
CREATE INDEX ix_payment_status_updated ON payment (status, updated_at);
