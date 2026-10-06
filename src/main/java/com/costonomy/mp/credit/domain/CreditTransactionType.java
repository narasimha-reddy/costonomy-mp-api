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
    /** The supplier undid a payment it recorded: the debt it had cleared is owed again (B6, D-140). */
    PAYMENT_REVERSED
}
