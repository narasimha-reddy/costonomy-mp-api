package com.costonomy.mp.trust.domain;

import java.util.Set;

/**
 * Doc 03 §12: {@code OPEN → UNDER_REVIEW → RESPONDED → RESOLVED}, with
 * {@code REJECTED} reachable from the first two.
 *
 * <p><b>A dispute is separate from order status.</b> Nothing here moves a supplier
 * order, and nothing an order does moves a dispute. Doc 01 §22 is explicit that an
 * order stays DELIVERED while a dispute runs, and §23A.26 requires the app to say
 * so — a restaurant must not have to choose between having their delivery recorded
 * and complaining about it.
 */
public enum DisputeStatus {

    OPEN,
    /** The supplier has seen it and is looking into it. */
    UNDER_REVIEW,
    /** The supplier has answered. The restaurant decides whether that settles it. */
    RESPONDED,
    RESOLVED,
    /** Refused by the supplier, or withdrawn. Terminal, and still on the record. */
    REJECTED;

    private static final Set<DisputeStatus> TERMINAL = Set.of(RESOLVED, REJECTED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(DisputeStatus target) {
        return switch (this) {
            case OPEN -> target == UNDER_REVIEW || target == RESPONDED
                    || target == RESOLVED || target == REJECTED;
            case UNDER_REVIEW -> target == RESPONDED || target == RESOLVED || target == REJECTED;
            // A response is not the end: the restaurant may accept it, or the two
            // may keep talking. Only they can close it.
            case RESPONDED -> target == RESOLVED || target == REJECTED || target == UNDER_REVIEW;
            case RESOLVED, REJECTED -> false;
        };
    }
}
