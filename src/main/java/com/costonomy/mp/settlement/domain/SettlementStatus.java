package com.costonomy.mp.settlement.domain;

/**
 * Doc 03 §13: {@code PENDING → CALCULATED → APPROVED → PROCESSING → PAID}, with
 * {@code PROCESSING → FAILED}.
 *
 * <p><b>APPROVED is a human step and cannot be skipped.</b> A settlement is money
 * leaving the platform; the gap between CALCULATED and APPROVED is where somebody
 * looks at the number before it becomes a payment. Automating past it would mean
 * a bug in the calculation pays itself out before anyone reads it.
 *
 * <p><b>FAILED returns to APPROVED, not to PENDING.</b> A payout that failed at
 * the bank has already been calculated and already been approved; sending it back
 * to the start would recalculate it against whatever has changed since, and ask
 * for an approval that was already given.
 */
public enum SettlementStatus {

    /** Created for a period, nothing summed yet. */
    PENDING,
    CALCULATED,
    APPROVED,
    /** Handed to whatever pays it. */
    PROCESSING,
    PAID,
    FAILED;

    public boolean isTerminal() {
        return this == PAID;
    }

    /** Whether the figures can still change. Once approved they are a commitment. */
    public boolean isMutable() {
        return this == PENDING || this == CALCULATED;
    }

    public boolean canTransitionTo(SettlementStatus target) {
        return switch (this) {
            case PENDING -> target == CALCULATED;
            case CALCULATED -> target == APPROVED;
            case APPROVED -> target == PROCESSING;
            case PROCESSING -> target == PAID || target == FAILED;
            // Retried from where it failed, keeping the approval it already has.
            case FAILED -> target == PROCESSING;
            case PAID -> false;
        };
    }
}
