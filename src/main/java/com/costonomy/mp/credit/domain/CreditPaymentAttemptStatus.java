package com.costonomy.mp.credit.domain;

/** Where a {@link CreditPaymentAttempt} stands (D-196). */
public enum CreditPaymentAttemptStatus {
    /** Waiting for the payment to be captured (online only). */
    CREATED,
    /** Captured (or debited from the wallet) and at least one invoice paid with it. Terminal. */
    PAID,
    /** The Razorpay order could not be created; nothing was ever payable. Terminal. */
    FAILED,
    /** Not paid within the expiry window. A payment that turns up later is still applied. */
    EXPIRED,
    /** Captured, but every invoice had changed, so all of it goes back. Terminal. */
    REFUNDED
}
