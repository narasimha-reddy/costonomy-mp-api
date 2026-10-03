package com.costonomy.mp.payment.provider;

/**
 * What a provider's answer to a refund tells us about whether the money moved (D-110), in
 * our words and not the provider's. The kind decides how to label a refusal and whether the
 * source payment is blocked; it never decides, on its own, that money did not leave. That
 * is always a read of the provider's list of the payment's refunds (see
 * {@code WithdrawalReversalService}), because a message can be reworded and a lost answer
 * can hide a refund that was made.
 */
public enum ProviderFailureKind {

    /** Timeout, I/O error, 5xx, an unreadable answer, an idempotency conflict: we do not know. Never re-credited automatically. */
    AMBIGUOUS(false),
    /** HTTP 429. The provider did not act. Retried without limit and not counted as an attempt. */
    THROTTLED(false),
    /** HTTP 401 or 403. Every call fails until the keys are fixed; nothing is refused about the refund. */
    CONFIG(false),
    /** The provider does not know the payment. */
    PAYMENT_UNKNOWN(true),
    /** "Already refunded": possibly by an earlier send of ours, possibly by someone else. */
    ALREADY_REFUNDED(true),
    /** More than is refundable: possibly the same two causes. */
    OVER_REFUND(true),
    /** The payment was never captured, so there is nothing to refund. */
    NOT_CAPTURED(true),
    /** Past the provider's refund window. */
    WINDOW_PASSED(true),
    /** Our provider account's balance cannot cover the refund. Says nothing about the payment. */
    INSUFFICIENT_BALANCE(true),
    /** Any other 400: the provider validated the request and refused it. */
    REJECTED_OTHER(true),
    /** The provider accepted the refund and then said it failed. */
    PROVIDER_FAILED(true);

    private final boolean definite;

    ProviderFailureKind(boolean definite) {
        this.definite = definite;
    }

    /** The provider answered and refused: a definite "no" that is then checked, as opposed to one that says nothing about the refund. */
    public boolean isDefinite() {
        return definite;
    }

    /** A refusal that will not change however often it is asked, so the source payment is blocked. */
    public boolean isPermanent() {
        return this == PAYMENT_UNKNOWN || this == NOT_CAPTURED || this == WINDOW_PASSED;
    }

    /** Both say something about our account, not about the refund: they stop new withdrawals for a while (the circuit breaker). */
    public boolean tripsBreaker() {
        return this == INSUFFICIENT_BALANCE || this == CONFIG;
    }
}
