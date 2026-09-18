package com.costonomy.mp.procurement.domain;

import java.util.Set;

/**
 * Supplier order lifecycle. Doc 03 §5, doc 01 §12.
 *
 * <pre>
 * DRAFT → PENDING_ACCEPTANCE → CONFIRMED → PREPARING → READY_FOR_PICKUP
 *       → OUT_FOR_DELIVERY → DELIVERED → COMPLETED
 * </pre>
 *
 * <p>Three properties this enum has to preserve.
 *
 * <p><b>REJECTED and EXPIRED are separate.</b> Doc 01 §12 rule 11 and doc 13:
 * a supplier declining is a decision; a supplier not answering is a failure to
 * respond. They mean different things commercially, feed different performance
 * signals, and a restaurant reads them differently. Collapsing them would be the
 * easy simplification and would destroy that.
 *
 * <p><b>PARTIALLY_ACCEPTED is a first-class outcome, not a variant of CONFIRMED.</b>
 * Doc 01 §10: the restaurant must be able to take the partial, source the rest
 * elsewhere, or reject the whole thing — and is never forced to accept.
 *
 * <p><b>Acceptance and expiry race, and exactly one wins.</b> Doc 03 §5 and
 * doc 10 §2. Enforced by optimistic locking on the aggregate, not by this enum —
 * but the enum is why it matters: both transitions are legal from
 * PENDING_ACCEPTANCE, so nothing here prevents both happening.
 */
public enum SupplierOrderStatus {

    /** Created, not yet visible to the supplier. */
    DRAFT,
    /** With the supplier, counting down against {@code acceptance_deadline}. */
    PENDING_ACCEPTANCE,
    /** Accepted in full. */
    CONFIRMED,
    /** Accepted in reduced quantities. The shortfall returns to the requirement. */
    PARTIALLY_ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,

    /** The supplier declined, with a reason. */
    REJECTED,
    /** The deadline passed with no answer. Distinct from REJECTED, deliberately. */
    EXPIRED,
    /** Cancelled by the restaurant, where policy permits (doc 01 §13). */
    CANCELLED;

    public Set<SupplierOrderStatus> allowedTransitions() {
        return switch (this) {
            // CONFIRMED as well as PENDING_ACCEPTANCE: an order created from an
            // accepted intent has already been agreed to, and routing it through
            // PENDING_ACCEPTANCE would ask the supplier to accept what they just
            // accepted. PENDING_ACCEPTANCE remains for orders still created the
            // old way, and goes when that path does.
            case DRAFT -> Set.of(PENDING_ACCEPTANCE, CONFIRMED, CANCELLED);
            case PENDING_ACCEPTANCE -> Set.of(
                    CONFIRMED, PARTIALLY_ACCEPTED, REJECTED, EXPIRED, CANCELLED);
            case CONFIRMED, PARTIALLY_ACCEPTED -> Set.of(PREPARING, CANCELLED);
            case PREPARING -> Set.of(READY_FOR_PICKUP, CANCELLED);
            // No cancellation past pickup. Doc 01 §13: once goods have left, the
            // path is return or dispute, not cancellation.
            case READY_FOR_PICKUP -> Set.of(OUT_FOR_DELIVERY);
            case OUT_FOR_DELIVERY -> Set.of(DELIVERED);
            case DELIVERED -> Set.of(COMPLETED);
            case COMPLETED, REJECTED, EXPIRED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(SupplierOrderStatus target) {
        return allowedTransitions().contains(target);
    }

    /** Whether the supplier still owes an answer. */
    public boolean isAwaitingResponse() {
        return this == PENDING_ACCEPTANCE;
    }

    /** Whether the supplier committed to any quantity. */
    public boolean isAccepted() {
        return this == CONFIRMED || this == PARTIALLY_ACCEPTED;
    }

    /** Whether this order ended without the supplier committing to anything. */
    public boolean isUnfulfilled() {
        return this == REJECTED || this == EXPIRED || this == CANCELLED;
    }
}
