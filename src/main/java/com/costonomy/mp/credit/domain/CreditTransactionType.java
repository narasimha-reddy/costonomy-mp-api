package com.costonomy.mp.credit.domain;

/** Ledger movement types. Doc 02 §3. */
public enum CreditTransactionType {

    RESERVE,
    UTILIZE,
    RELEASE,
    REPAYMENT,
    LIMIT_CHANGE,
    /** A supplier's manual correction. Always carries a reason (doc 01 §18). */
    ADJUSTMENT,
    /** The supplier undid a payment it recorded: the debt it had cleared is owed again (B6, D-169). */
    PAYMENT_REVERSED,
    /** The supplier (or the system, for a cancelled order) took an amount off an invoice without a payment (B7, D-175). */
    CREDIT_NOTE,
    /** The supplier gave up on an amount still owed (B8, D-179). */
    WRITE_OFF
}
