package com.costonomy.mp.credit.domain;

/** What became of one invoice of a credit payment attempt (D-199). */
public enum CreditPayInvoiceOutcome {
    PENDING,
    PAID,
    SKIPPED
}
