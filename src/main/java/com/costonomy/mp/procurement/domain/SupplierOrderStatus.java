package com.costonomy.mp.procurement.domain;

import java.util.Set;

/**
 * Supplier order lifecycle. Doc 03 §5, doc 01 §12, D-091.
 *
 * <pre>
 * DRAFT → CONFIRMED → PREPARING → READY_FOR_PICKUP → OUT_FOR_DELIVERY
 *       → DELIVERED → COMPLETED,   with CANCELLED
 * </pre>
 *
 * <p><b>An order is never accepted.</b> D-088 moved the supplier's commitment to
 * the request; an order exists because they already said yes and the restaurant
 * paid against that answer. So the four statuses that asked them again —
 * {@code PENDING_ACCEPTANCE}, {@code PARTIALLY_ACCEPTED}, {@code REJECTED} and
 * {@code EXPIRED} — are gone, and V29 mapped the rows that held them.
 *
 * <p><b>A supplier who cannot fulfil cancels.</b> Money has already moved, and
 * the difference between rejecting and cancelling is a refund. One
 * {@code CANCELLED}, with {@link CancelledBy} recording whose decision it was.
 *
 * <p><b>Partial supply lives on the request.</b> The supplier offers less, the
 * restaurant orders what was offered, and the shortfall stays visible on the
 * request — rather than being encoded as a flavour of order that every screen
 * then has to explain.
 */
public enum SupplierOrderStatus {

    /**
     * Created, not yet funded, and not visible to the supplier.
     *
     * <p>Guardrail 16 / D-020: a supplier never sees an unfunded order. The
     * order row exists before the payment because the payment is taken against
     * it, and {@code DRAFT} is what keeps that row private until it clears.
     */
    DRAFT,

    /** Funded. The supplier's to prepare. */
    CONFIRMED,

    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,

    /**
     * Ended before the goods moved. {@link CancelledBy} says by whom.
     *
     * <p>Closed from {@code READY_FOR_PICKUP} onward — doc 01 §13: once goods
     * have left, the path is return or dispute, not cancellation.
     */
    CANCELLED;

    /**
     * Where this status may go next, given how the goods travel.
     *
     * <p><b>The mode is an argument because the answer genuinely depends on it.</b>
     * A collected order completes at {@code READY_FOR_PICKUP}; a delivered one
     * goes out from there. Encoding both as unconditionally legal would let a
     * pickup order be marked out for delivery, and the guard that mattered would
     * have moved into whichever service remembered to write it.
     */
    public Set<SupplierOrderStatus> allowedTransitions(DeliveryMode mode) {
        return switch (this) {
            // No PENDING_ACCEPTANCE: the supplier accepted the request, so a
            // funded order is confirmed. CANCELLED covers the payment that never
            // completed.
            case DRAFT -> Set.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> Set.of(PREPARING, CANCELLED);
            case PREPARING -> Set.of(READY_FOR_PICKUP, CANCELLED);
            // The branch. Nothing is delivered on a pickup, so DELIVERED would
            // describe something that did not happen.
            case READY_FOR_PICKUP -> mode == DeliveryMode.PICKUP
                    ? Set.of(COMPLETED)
                    : Set.of(OUT_FOR_DELIVERY);
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(COMPLETED);
            case COMPLETED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(SupplierOrderStatus target, DeliveryMode mode) {
        return allowedTransitions(mode).contains(target);
    }

    public boolean isTerminal() {
        // Terminality does not depend on the mode: the two ends are the two ends
        // whoever carried the goods.
        return this == COMPLETED || this == CANCELLED;
    }

    /** Whether the supplier has the order and owes work on it. */
    public boolean isWithSupplier() {
        return this == CONFIRMED || this == PREPARING;
    }

    /** Whether the supplier has committed to supplying. */
    public boolean isAccepted() {
        return this != DRAFT && this != CANCELLED;
    }

    /** Whether this order ended without the goods reaching the kitchen. */
    public boolean isUnfulfilled() {
        return this == CANCELLED;
    }

    /** Whether the goods have left the store, and cancellation is closed. */
    public boolean hasLeftTheStore() {
        return this == OUT_FOR_DELIVERY || this == DELIVERED || this == COMPLETED;
    }
}
