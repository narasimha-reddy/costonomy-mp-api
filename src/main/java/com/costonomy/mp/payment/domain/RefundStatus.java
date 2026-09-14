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
    FAILED;

    public Set<RefundStatus> allowedTransitions() {
        return switch (this) {
            case REQUESTED -> Set.of(PROCESSING, FAILED);
            case PROCESSING -> Set.of(COMPLETED, FAILED);
            case FAILED -> Set.of(PROCESSING);
            case COMPLETED -> Set.of();
        };
    }

    public boolean canTransitionTo(RefundStatus target) {
        return allowedTransitions().contains(target);
    }
}
