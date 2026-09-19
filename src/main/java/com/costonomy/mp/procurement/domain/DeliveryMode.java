package com.costonomy.mp.procurement.domain;

/**
 * How the goods get from the store to the kitchen. D-091.
 *
 * <p><b>The restaurant chooses, and chooses at order creation</b>, because the
 * restaurant pays the fee — and the fee is part of what is charged, so the mode
 * has to be fixed before a payment intent exists. The supplier's part is to
 * declare which of these they can serve, bounded by their
 * {@code supplier_delivery_policy}.
 *
 * <p>The mode is also an argument to
 * {@link SupplierOrderStatus#allowedTransitions(DeliveryMode)}: the same order
 * status leads somewhere different depending on who is carrying the goods, and
 * who may move it differs with it.
 */
public enum DeliveryMode {

    /**
     * The restaurant collects. {@code READY_FOR_PICKUP} goes straight to
     * {@code COMPLETED} — nothing was delivered, so {@code DELIVERED} would be a
     * status describing something that did not happen.
     */
    PICKUP,

    /**
     * The supplier delivers with their own vehicle.
     *
     * <p>The one mode where a supplier may set {@code OUT_FOR_DELIVERY} and
     * {@code DELIVERED}. §23A.38 forbids a supplier claiming movement on a
     * courier's behalf; here the supplier <em>is</em> the courier, and the
     * prohibition does not apply.
     */
    SUPPLIER_DELIVERY,

    /**
     * A courier booked by the platform.
     *
     * <p>Movement comes from the provider's events and from nowhere else, and
     * the fee comes from a quote taken before the order exists — provider
     * bidding is internal (doc 06 §10), so the restaurant sees one number and
     * never a quote.
     */
    COSTONOMY_DELIVERY;

    /** Whether the supplier is the one carrying the goods. */
    public boolean isSupplierCarried() {
        return this == SUPPLIER_DELIVERY;
    }

    /** Whether a courier's events drive the order's movement. */
    public boolean isCourierCarried() {
        return this == COSTONOMY_DELIVERY;
    }

    /** Whether anything is delivered at all. */
    public boolean isDelivered() {
        return this != PICKUP;
    }
}
