package com.costonomy.mp.procurement.domain;

/**
 * Who cancelled an order. D-091.
 *
 * <p>An attribute rather than three statuses, deliberately, and the opposite of
 * the call D-089 made in keeping {@code EXPIRED} and {@code ORDER_CREATION_EXPIRED}
 * apart. That distinction was between two different things happening. Here one
 * thing happens — the order is cancelled, and money goes back — and only the
 * actor differs. A status separates events; an attribute separates actors, and a
 * reliability metric reads this column as easily as it would read a status name
 * while every switch over the lifecycle stays at eight cases.
 */
public enum CancelledBy {

    /** The kitchen withdrew, where policy permits it (doc 01 §13). */
    RESTAURANT,

    /**
     * The supplier could not fulfil after all.
     *
     * <p>This is what used to be {@code REJECTED}. After D-088 the supplier has
     * already committed on the request and been paid against, so the honest name
     * for backing out is a cancellation with a refund, not a rejection.
     */
    SUPPLIER,

    /** Nobody decided: an unfunded draft swept up, or a platform action. */
    SYSTEM
}
