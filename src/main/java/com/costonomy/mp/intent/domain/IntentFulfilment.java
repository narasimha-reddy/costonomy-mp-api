package com.costonomy.mp.intent.domain;

import java.math.BigDecimal;

/**
 * How much of what was asked for was actually offered. §4, §12.
 *
 * <p><b>"Fulfilled" here means accepted</b> — what the supplier committed to
 * supplying. Not ordered, not delivered, not received. §12 asks for one explicit
 * definition documented in code, and this is it: the question an intent exists to
 * answer is "will somebody supply this", and that is answered the moment a
 * supplier says yes. Whether the goods then arrive is the order's business, and
 * receiving has its own three quantities for it.
 *
 * <p>Computed from quantities and never from a status, so an intent cannot report
 * itself fulfilled because an order happened to be created from it.
 */
public enum IntentFulfilment {

    /** Nobody has answered yet. Not the same as nothing being available. */
    AWAITING,
    /** Every line offered in full. */
    FULFILLED,
    /** Something offered, but less than asked for on at least one line. */
    PARTIALLY_FULFILLED,
    /** Answered with nothing. A refusal, stated. */
    NOT_FULFILLED;

    /**
     * @param requested total asked for across the lines
     * @param accepted  total the supplier committed to, or null before an answer
     */
    public static IntentFulfilment of(BigDecimal requested, BigDecimal accepted) {
        if (accepted == null) {
            return AWAITING;
        }
        if (accepted.signum() <= 0) {
            return NOT_FULFILLED;
        }
        // compareTo, never equals: 20 and 20.0000 are the same quantity and
        // different BigDecimals.
        return accepted.compareTo(requested) >= 0 ? FULFILLED : PARTIALLY_FULFILLED;
    }
}
