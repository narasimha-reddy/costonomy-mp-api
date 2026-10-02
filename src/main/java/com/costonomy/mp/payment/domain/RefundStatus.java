package com.costonomy.mp.payment.domain;

import java.util.Set;

/** Refund lifecycle. Doc 03 §7, doc 22. */
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    /**
     * The provider declined or could not process it.
     *
     * <p>Not terminal: doc 22 requires a retry not to create a duplicate refund,
     * which is why the same refund row is retried rather than a new one raised.
     */
    FAILED,
    /**
     * Stopped for a person to look at (D-101): the provider refused it outright,
     * or it failed {@code RefundService.MAX_ATTEMPTS} times. Retrying either
     * blindly is how a refund failed every thirty seconds for ever with nobody
     * told. Still counted against what the payment can refund, because the money
     * may yet go back.
     */
    NEEDS_REVIEW,
    /**
     * The provider refused this send outright (D-110): a definite answer, not a lost one.
     * Not the end: before anything is decided the provider's own list of the payment's
     * refunds is read, because only a refund of ours that is <em>not</em> there proves the
     * money did not leave. Then it becomes REVERSED (a withdrawal part goes back to the
     * wallet), NEEDS_REVIEW (a cancellation refund, or a payment someone else refunded), or
     * is adopted (ours was there after all).
     *
     * <p>Counted as in flight: the money has not been returned to anyone.
     */
    REJECTED,
    /**
     * Terminal (D-110). The money never left and is back where it came from: a withdrawal
     * part credited back to the wallet (ledger kind WITHDRAWAL_REVERSAL), or a cancellation
     * refund redirected to the wallet by operations. Not in flight: it is neither owed to
     * the provider's payee nor counted against what the payment can still refund.
     */
    REVERSED;

    public Set<RefundStatus> allowedTransitions() {
        return switch (this) {
            case REQUESTED -> Set.of(PROCESSING, FAILED);
            case PROCESSING -> Set.of(COMPLETED, FAILED, NEEDS_REVIEW, REJECTED);
            case FAILED -> Set.of(PROCESSING, NEEDS_REVIEW);
            // Verified: none of ours at the provider (REVERSED), ours found (PROCESSING or
            // COMPLETED), or not decidable by the system (NEEDS_REVIEW).
            // REQUESTED: the refusal was contradicted by the provider's own answers (it says it does not know a payment
            // it plainly knows), so the part is sent again and not put back.
            case REJECTED -> Set.of(REVERSED, PROCESSING, COMPLETED, NEEDS_REVIEW, REQUESTED);
            // A person decided to try again, found it had gone (COMPLETED), or put the money back.
            case NEEDS_REVIEW -> Set.of(PROCESSING, COMPLETED, REVERSED, REQUESTED);
            case COMPLETED, REVERSED -> Set.of();
        };
    }

    public boolean canTransitionTo(RefundStatus target) {
        return allowedTransitions().contains(target);
    }
}
