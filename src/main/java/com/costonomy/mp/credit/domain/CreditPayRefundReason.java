package com.costonomy.mp.credit.domain;

/** Why money from a credit payment goes back to the restaurant (D-196). */
public enum CreditPayRefundReason {
    /** The shares of invoices that changed before the capture was applied. */
    SKIPPED_INVOICES,
    /** A second captured payment on an attempt that was already applied: all of it goes back. */
    DUPLICATE_CAPTURE
}
