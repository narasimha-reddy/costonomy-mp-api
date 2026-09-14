package com.costonomy.mp.credit.domain;

/** Ledger movement types. Doc 02 §3. */
public enum CreditTransactionType {

    RESERVE,
    UTILIZE,
    RELEASE,
    REPAYMENT,
    LIMIT_CHANGE,
    /** A supplier's manual correction. Always carries a reason (doc 01 §18). */
    ADJUSTMENT
}
