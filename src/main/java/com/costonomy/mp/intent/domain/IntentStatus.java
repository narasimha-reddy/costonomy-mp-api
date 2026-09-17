package com.costonomy.mp.intent.domain;

import java.util.Set;

/**
 * Where an intent is in its life. Doc: Intent architecture §11.
 *
 * <pre>
 * DRAFT → OPEN → RESPONSES_RECEIVED → ORDERED
 * </pre>
 *
 * with {@code CANCELLED}, {@code EXPIRED} and {@code ORDER_CREATION_EXPIRED}.
 *
 * <p><b>This is not fulfilment.</b> §4 requires fulfilment to be computed from
 * quantities — accepted against requested — and forbids deciding it from order
 * status. So a lifecycle status says what may happen next, and
 * {@link IntentFulfilment} says how much of the ask was met. An intent can be
 * {@code ORDERED} and only a third fulfilled; the two answer different questions
 * and collapsing them is how "partially fulfilled" comes to mean four things.
 */
public enum IntentStatus {

    /**
     * Being assembled. This is the basket: adding a supplier's SKU creates or
     * updates the draft intent for that supplier, so the cart screen is a list of
     * drafts, one per supplier.
     */
    DRAFT,
    /** Sent. The supplier can see it and has not answered. */
    OPEN,
    /**
     * The supplier answered. Frozen from here: neither side may change the
     * intent, because it now describes a commercial proposal someone made.
     */
    RESPONSES_RECEIVED,
    /** An order was created from it. Terminal — one intent, at most one order. */
    ORDERED,
    /** Withdrawn by the restaurant before an order existed. */
    CANCELLED,
    /** Nobody answered in time. */
    EXPIRED,
    /**
     * Answered, but the restaurant did not order inside the window.
     *
     * <p>Distinct from {@code EXPIRED} because the supplier did their part: a
     * response that went unused is not a supplier who ignored a request, and the
     * two must not be averaged into one performance figure.
     */
    ORDER_CREATION_EXPIRED;

    public Set<IntentStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> Set.of(OPEN, CANCELLED);
            case OPEN -> Set.of(RESPONSES_RECEIVED, CANCELLED, EXPIRED);
            case RESPONSES_RECEIVED -> Set.of(ORDERED, CANCELLED, ORDER_CREATION_EXPIRED);
            case ORDERED, CANCELLED, EXPIRED, ORDER_CREATION_EXPIRED -> Set.of();
        };
    }

    public boolean canTransitionTo(IntentStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** Whether the restaurant may still add, remove or re-quantify lines. */
    public boolean isEditable() {
        return this == DRAFT;
    }
}
