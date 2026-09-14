package com.costonomy.mp.credit.domain;

/** Doc 01 §18's dues view. */
public enum CreditInvoiceStatus {

    ISSUED,
    PARTIALLY_PAID,
    PAID,
    /**
     * Past {@code overdue_after}, which is the due date <em>plus the grace
     * period</em>. A supplier who grants five days of grace has not been kept
     * waiting on day one, and calling that overdue would suspend restaurants who
     * are paying exactly as agreed.
     */
    OVERDUE,
    /** Written off by the supplier. Mandi never does this on their behalf. */
    WRITTEN_OFF;

    public boolean isSettled() {
        return this == PAID || this == WRITTEN_OFF;
    }
}
