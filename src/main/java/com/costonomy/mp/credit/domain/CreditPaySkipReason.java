package com.costonomy.mp.credit.domain;

/** Why a captured payment did not pay an invoice: it changed after the restaurant chose it (D-196). */
public enum CreditPaySkipReason {
    /** Paid in full meanwhile. */
    SETTLED,
    /** The supplier wrote it off meanwhile. */
    WRITTEN_OFF,
    /** Its outstanding amount is no longer what was charged (credit note, part payment, reversal, cancellation). */
    AMOUNT_CHANGED,
    /** The supplier store can no longer be paid through Mandi. */
    SUPPLIER_UNAVAILABLE
}
