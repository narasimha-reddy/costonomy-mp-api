package com.costonomy.mp.credit.domain;

/** Who or what took an amount off an invoice (B7, B8). */
public enum CreditNoteKind {
    /** The supplier issued it (short supply, quality, price, goodwill). */
    MANUAL,
    /** The order was cancelled after the draw; the system issued it in the cancel transaction (D-176). */
    SYSTEM_CANCEL,
    /** The supplier gave up on what was still owed (D-179). */
    WRITE_OFF
}
