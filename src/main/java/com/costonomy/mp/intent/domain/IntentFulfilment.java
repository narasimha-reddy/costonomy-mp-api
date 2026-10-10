package com.costonomy.mp.intent.domain;

import java.math.BigDecimal;
import java.util.Collection;

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
     * One line's fulfilment.
     *
     * <p>Deliberately per line. Quantities on an intent are in each SKU's own
     * pack unit, so 20 KG of rice and 5 LTR of oil have no meaningful total —
     * summing them and comparing would make fulfilment depend on which units
     * happened to be in the basket. Roll up with {@link #roll} instead.
     *
     * @param requested what was asked for on this line
     * @param accepted  what the supplier committed to, or null before an answer
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

    /**
     * The whole intent's fulfilment, from its lines'.
     *
     * <p>Composed rather than summed, for the reason {@link #of} gives: there is
     * no total to compare when the lines are in different units. The rules read
     * the way a person would describe the request — everything, nothing, or
     * something in between:
     *
     * <ul>
     *   <li>no lines, or any line still unanswered → {@code AWAITING}. A
     *       half-answered intent is not a partial offer; the supplier is still
     *       typing.</li>
     *   <li>every line in full → {@code FULFILLED}</li>
     *   <li>every line refused → {@code NOT_FULFILLED}</li>
     *   <li>anything else → {@code PARTIALLY_FULFILLED}</li>
     * </ul>
     */
    public static IntentFulfilment roll(Collection<IntentFulfilment> lines) {
        if (lines.isEmpty() || lines.contains(AWAITING)) {
            return AWAITING;
        }
        if (lines.stream().allMatch(line -> line == FULFILLED)) {
            return FULFILLED;
        }
        if (lines.stream().allMatch(line -> line == NOT_FULFILLED)) {
            return NOT_FULFILLED;
        }
        return PARTIALLY_FULFILLED;
    }
}
