package com.costonomy.mp.procurement.domain;

/**
 * A supplier's answer on one line. Doc 04 §11, doc 14.
 *
 * <p>{@code REJECTED} is an accepted quantity of zero, stated explicitly. Doc 04
 * §11 requires a zero to be deliberate rather than an omission, which is also why
 * {@code accepted_quantity} is nullable: null is "not answered", zero is
 * "declined".
 */
public enum OrderItemStatus {
    PENDING,
    /** Accepted in full. */
    ACCEPTED,
    /** Accepted in part. The shortfall returns to the requirement. */
    PARTIALLY_ACCEPTED,
    /** Declined outright — accepted quantity zero. */
    REJECTED,
}
