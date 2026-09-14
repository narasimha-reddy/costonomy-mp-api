package com.costonomy.mp.credit.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Doc 03 §8: {@code REQUESTED → APPROVED → ACTIVE}, with REJECTED, SUSPENDED,
 * EXPIRED and CLOSED.
 *
 * <p><b>APPROVED is not usable credit.</b> It means the supplier agreed to terms
 * that differ from the ask and the restaurant has not accepted them yet — doc 05
 * §20 lists "approved with modified terms" as its own status, and doc 03 gives
 * {@code APPROVED → ACTIVE} as a separate transition, so there is a step between
 * them and someone has to take it. Only {@link #ACTIVE} can fund an order, which
 * is why {@link #canFund()} exists rather than callers testing for a status.
 */
public enum CreditAgreementStatus {

    REQUESTED,
    /** Supplier agreed, on terms awaiting the restaurant's acceptance. */
    APPROVED,
    ACTIVE,
    REJECTED,
    /** Overdue beyond what the supplier tolerates. Existing debt survives. */
    SUSPENDED,
    EXPIRED,
    CLOSED;

    private static final Set<CreditAgreementStatus> TERMINAL =
            EnumSet.of(REJECTED, EXPIRED, CLOSED);

    /** Whether an order may draw on this agreement. Only one status can. */
    public boolean canFund() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(CreditAgreementStatus target) {
        return switch (this) {
            case REQUESTED -> target == APPROVED || target == ACTIVE || target == REJECTED;
            case APPROVED -> target == ACTIVE || target == REJECTED || target == EXPIRED;
            // SUSPENDED and back is the whole point of suspension: a restaurant
            // that clears its overdue balance gets its credit line back without
            // renegotiating it.
            case ACTIVE -> target == SUSPENDED || target == EXPIRED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED || target == EXPIRED;
            case REJECTED, EXPIRED, CLOSED -> false;
        };
    }
}
