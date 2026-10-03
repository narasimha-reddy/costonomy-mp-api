package com.costonomy.mp.wallet.domain;

/**
 * Where a Razorpay top-up has got to (D-107).
 *
 * <p>Only CREATED can become anything else, and EXPIRED can still become
 * CREDITED: a payment that turns up after we gave up on it is money we hold, and
 * "we had stopped looking" is not a reason to keep it.
 */
public enum WalletTopUpStatus {

    /** Waiting for the restaurant's payment to be captured. */
    CREATED,
    /** Captured, and the wallet credited. Terminal. */
    CREDITED,
    /**
     * Captured, but crediting would have broken a wallet limit, so the payment is
     * being returned to where it came from. Internal: the API shows it as CREATED
     * until the return is confirmed, because the client's contract has no such
     * state and "still being sorted out" is what it is.
     */
    REFUND_PENDING,
    /** That return has completed. Terminal. */
    REFUNDED,
    /** The Razorpay order could not be created; nothing was ever payable. Terminal. */
    FAILED,
    /** Nobody paid within a day, and Razorpay confirmed it. */
    EXPIRED;

    /** The status the API reports. */
    public String apiName() {
        return this == REFUND_PENDING ? CREATED.name() : name();
    }
}
