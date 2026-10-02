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
    NEEDS_REVIEW;

    public Set<RefundStatus> allowedTransitions() {
        return switch (this) {
            case REQUESTED -> Set.of(PROCESSING, FAILED);
            case PROCESSING -> Set.of(COMPLETED, FAILED, NEEDS_REVIEW);
            case FAILED -> Set.of(PROCESSING, NEEDS_REVIEW);
            // A person decided to try again.
            case NEEDS_REVIEW -> Set.of(PROCESSING);
            case COMPLETED -> Set.of();
        };
    }

    public boolean canTransitionTo(RefundStatus target) {
        return allowedTransitions().contains(target);
    }
}
