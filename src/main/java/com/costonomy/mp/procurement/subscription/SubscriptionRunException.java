package com.costonomy.mp.procurement.subscription;

import java.math.BigDecimal;

/**
 * A generation attempt that did not produce an order. Thrown out of the generator's transaction so
 * everything it wrote rolls back, then recorded by {@link SubscriptionRunStore} in its own transaction.
 */
public class SubscriptionRunException extends RuntimeException {

    /** The outcomes that are failures of an attempt (the others are results of a decision). */
    public enum Outcome { SKIPPED_NO_OFFER, FUNDING_FAILED, SKIPPED_INVALID }

    private final Outcome outcome;
    private final BigDecimal amount;

    public SubscriptionRunException(Outcome outcome, String reason, BigDecimal amount) {
        super(reason);
        this.outcome = outcome;
        this.amount = amount;
    }

    public Outcome outcome() {
        return outcome;
    }

    public BigDecimal amount() {
        return amount;
    }
}
