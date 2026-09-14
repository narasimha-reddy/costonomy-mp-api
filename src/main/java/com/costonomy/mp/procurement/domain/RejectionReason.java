package com.costonomy.mp.procurement.domain;

/**
 * Why a supplier declined an order or a line. Doc 05 §27.
 *
 * <p>A closed set rather than free text, because these feed supplier performance
 * and marketplace intelligence: "out of stock" and "store closed" are the
 * difference between a catalog problem and an operating-hours problem, and free
 * text cannot be counted.
 *
 * <p>A note is accepted alongside, for the detail the code cannot carry.
 */
public enum RejectionReason {

    /** Listed, but not actually available. A catalog accuracy problem. */
    OUT_OF_STOCK,
    /** Available, but not to this outlet — distance, timing, vehicle. */
    UNABLE_TO_DELIVER,
    /** Outside operating hours, or closed unexpectedly. */
    STORE_CLOSED,
    /** The listed price is wrong. Also a catalog accuracy problem, and a serious one. */
    PRICE_ISSUE,
    /** Below what the supplier will dispatch for. */
    BELOW_MINIMUM_ORDER,
    OTHER;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (RejectionReason reason : values()) {
            if (reason.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
