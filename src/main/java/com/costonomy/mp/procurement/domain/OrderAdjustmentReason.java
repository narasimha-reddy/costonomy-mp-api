package com.costonomy.mp.procurement.domain;

/** Why an order's final payable came down after it settled at ready (D-129). */
public enum OrderAdjustmentReason {
    /** A catch-weight line weighed lighter than ordered; written once, at ready, when the money settles. */
    WEIGHT_SETTLEMENT,
    /** Goods rejected at the door; written at receiving. */
    DOORSTEP_REJECTION
}
