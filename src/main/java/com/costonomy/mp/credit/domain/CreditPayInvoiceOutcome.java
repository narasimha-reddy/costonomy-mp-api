package com.costonomy.mp.credit.domain;

/** What became of one invoice of a credit payment attempt (D-196). */
public enum CreditPayInvoiceOutcome {
    PENDING,
    PAID,
    SKIPPED
}
