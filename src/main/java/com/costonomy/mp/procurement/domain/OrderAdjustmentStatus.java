package com.costonomy.mp.procurement.domain;

/** Whether the money behind an adjustment has moved (D-129). */
public enum OrderAdjustmentStatus {
    APPLIED,
    /**
     * The final payable is already reduced, but the refund cannot be made yet because the card payment behind
     * the order has not been captured. A scheduled job applies it once the payment is captured.
     */
    PENDING_CAPTURE
}
