package com.costonomy.mp.payment.domain;

import java.util.Set;

/**
 * Payment lifecycle. Doc 03 §6.
 *
 * <pre>
 * CREATED → AUTHORIZED → CAPTURE_PENDING → CAPTURED
 * </pre>
 *
 * <p>{@code CAPTURE_PENDING} is not bookkeeping — it is the state that makes
 * capture survivable. Capturing means calling a provider over a network, which
 * must not happen inside the transaction that records the supplier's acceptance:
 * a slow gateway would hold a database transaction open, and a failure would roll
 * back an acceptance that genuinely happened. So acceptance marks the payment
 * {@code CAPTURE_PENDING} and commits; the capture is performed afterwards and can
 * be retried until it succeeds.
 *
 * <p>Doc 03 §6 also warns that provider webhooks arrive out of order. Transitions
 * are therefore checked rather than applied: a late {@code authorized} event
 * cannot drag a captured payment backwards.
 */
public enum PaymentStatus {

    /** An intent exists. The customer has not paid. */
    CREATED,
    /** The provider is holding the money. Nothing has been taken. */
    AUTHORIZED,
    /** The supplier accepted; the capture has not completed yet. */
    CAPTURE_PENDING,
    /** Money taken. */
    CAPTURED,
    /** Authorisation failed or was declined. */
    FAILED,
    /**
     * The hold was dropped without taking anything.
     *
     * <p>What happens when a supplier rejects or times out. Distinct from a
     * refund: nothing was taken, so nothing is returned — which reaches the
     * customer faster and does not appear on their statement as a reversal.
     */
    RELEASED,
    PARTIALLY_REFUNDED,
    FULLY_REFUNDED;

    public Set<PaymentStatus> allowedTransitions() {
        return switch (this) {
            case CREATED -> Set.of(AUTHORIZED, FAILED);
            case AUTHORIZED -> Set.of(CAPTURE_PENDING, RELEASED, FAILED);
            // Back to AUTHORIZED when a capture fails and is worth retrying — the
            // money is still held, so the payment is exactly where it was.
            case CAPTURE_PENDING -> Set.of(CAPTURED, AUTHORIZED, FAILED);
            case CAPTURED -> Set.of(PARTIALLY_REFUNDED, FULLY_REFUNDED);
            case PARTIALLY_REFUNDED -> Set.of(PARTIALLY_REFUNDED, FULLY_REFUNDED);
            case FAILED, RELEASED, FULLY_REFUNDED -> Set.of();
        };
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Whether the provider is holding money we could still take. */
    public boolean isHoldingFunds() {
        return this == AUTHORIZED || this == CAPTURE_PENDING;
    }

    /**
     * Whether the order this payment funds may be shown to its supplier.
     *
     * <p>Guardrail 16 and doc 01 §14: a payment failure means the supplier never
     * sees the order. This is the predicate that enforces it.
     */
    public boolean fundsSecured() {
        return this == AUTHORIZED || this == CAPTURE_PENDING || this == CAPTURED;
    }

    public boolean isSettled() {
        return this == CAPTURED || this == RELEASED || this == FAILED
                || this == FULLY_REFUNDED || this == PARTIALLY_REFUNDED;
    }
}
