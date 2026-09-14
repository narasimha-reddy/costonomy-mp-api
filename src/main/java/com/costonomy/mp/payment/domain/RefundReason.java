package com.costonomy.mp.payment.domain;

/** Why money is being returned. Doc 22. */
public enum RefundReason {
    /** The supplier declined after the money had been captured. */
    SUPPLIER_REJECTION,
    /** Captured in full, then accepted in part. */
    PARTIAL_ACCEPTANCE,
    CANCELLATION,
    DELIVERY_FAILURE,
    DISPUTE_RESOLVED,
    DUPLICATE_PAYMENT,
    PROVIDER_REVERSAL;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (RefundReason reason : values()) {
            if (reason.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
