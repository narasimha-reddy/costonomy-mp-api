package com.costonomy.mp.wallet.invoice.domain;

public enum InvoiceStatus {
    /** Pages stored; not read yet, or a reading failed and will be tried again. */
    READING,
    READ,
    /** Gave up after the attempts allowed. The pages are kept and can still be viewed. */
    UNREADABLE
}
