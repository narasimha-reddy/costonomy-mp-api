package com.costonomy.mp.quickscan.domain;

import java.util.Set;

/**
 * A QuickScan payment's lifecycle. D-106.
 *
 * <p>Money leaves the wallet the moment a payment is created — before the
 * payout has gone anywhere — because the wallet debit is the one write that
 * must not race a second click. Everything after that is the payout catching
 * up with what the wallet already did.
 */
public enum QuickScanStatus {

    /** Debited from the wallet; the payout has not finished. */
    PAYOUT_PENDING,
    /** The payout reached the shop. */
    PAID,
    /**
     * The payout was refused or reversed. The money is back in the wallet —
     * it never reached anyone, or came back after it did.
     */
    FAILED,
    /**
     * Retries exhausted with no answer (D-101's shape). The outcome is
     * unknown — the payout may have reached the shop — so the money is
     * <b>not</b> auto-returned. A person has to look.
     */
    NEEDS_REVIEW;

    public Set<QuickScanStatus> allowedTransitions() {
        return switch (this) {
            case PAYOUT_PENDING -> Set.of(PAID, FAILED, NEEDS_REVIEW);
            // A payout the provider later reverses moves a PAID row to FAILED,
            // and the money is returned then, once.
            case PAID -> Set.of(FAILED);
            case NEEDS_REVIEW -> Set.of(PAID, FAILED);
            case FAILED -> Set.of();
        };
    }

    public boolean canTransitionTo(QuickScanStatus target) {
        return allowedTransitions().contains(target);
    }
}
