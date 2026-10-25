package com.costonomy.mp.delivery.domain;

import java.util.Set;

/**
 * The states of a courier payable (D-204). Plain strings in the entity, as the other delivery columns are, with the
 * legal moves kept here so a service cannot invent one.
 */
public final class CourierPayableState {

    /** Booked; the courier has not yet done anything that costs money. */
    public static final String ACCRUED = "ACCRUED";
    /** Owed. Waiting for the payment job (an API rail) or for a person (a manual rail). */
    public static final String DUE = "DUE";
    /** A payment is in flight under a lease. */
    public static final String PROCESSING = "PROCESSING";
    /** The last payment definitely failed; it will be tried again at {@code nextRetryAt}. */
    public static final String FAILED = "FAILED";
    /** Retries are used up. A person must act. */
    public static final String EXHAUSTED = "EXHAUSTED";
    /** A payment may or may not have gone. Asked about, never repeated blindly. */
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String PAID = "PAID";
    /** Nothing is owed: the courier was cancelled at no charge. */
    public static final String VOID = "VOID";

    private CourierPayableState() {
    }

    public static Set<String> allowedFrom(String from) {
        return switch (from) {
            case ACCRUED -> Set.of(DUE, VOID, ACCRUED);
            case DUE -> Set.of(PROCESSING, PAID, VOID, ACCRUED, DUE);
            case PROCESSING -> Set.of(PAID, FAILED, EXHAUSTED, AMBIGUOUS);
            case FAILED -> Set.of(PROCESSING, PAID, EXHAUSTED, DUE, VOID, FAILED);
            case EXHAUSTED -> Set.of(DUE, PAID, VOID, EXHAUSTED);
            case AMBIGUOUS -> Set.of(PAID, FAILED, DUE, EXHAUSTED, AMBIGUOUS);
            // Settled. A later credit or fee is flagged for reconciliation; the state does not move.
            case PAID -> Set.of(PAID);
            case VOID -> Set.of(DUE, VOID);
            default -> Set.of();
        };
    }

    public static boolean canMove(String from, String to) {
        return allowedFrom(from).contains(to);
    }

    /** States a payment job may pick up. */
    public static boolean payable(String state) {
        return DUE.equals(state) || FAILED.equals(state);
    }
}
