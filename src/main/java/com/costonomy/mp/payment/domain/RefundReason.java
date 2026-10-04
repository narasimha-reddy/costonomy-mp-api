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
    /** A line rejected at the doorstep (D-128): the buyer pays less than the captured amount. */
    DOORSTEP_REJECTION,
    DUPLICATE_PAYMENT,
    PROVIDER_REVERSAL,
    /**
     * Wallet money sent back to the card it came from (D-104). Not a new refund
     * of the payment: that happened when the money was credited to the wallet,
     * and counting it again would refund the same rupee twice.
     */
    WALLET_WITHDRAWAL;

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
