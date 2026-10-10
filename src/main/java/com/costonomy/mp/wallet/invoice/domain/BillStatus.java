package com.costonomy.mp.wallet.invoice.domain;

/**
 * Where a wallet payment's bill has got to, as the History and the details page show it (D-116). The words on the
 * chip are the client's; these are the values.
 */
public enum BillStatus {
    /** A payment that needs a bill (eligible, on or after the tracking start), with none and not waived. */
    PENDING,
    /** The bill is stored and being read. */
    READING,
    /** The bill is read, not yet reviewed. */
    ADDED,
    /** The bill is read and the user saved a review of it. */
    REVIEWED,
    /** The bill could not be read; the pages are still there. */
    UNREADABLE,
    /** 'No bill needed' (details page and statements only; the list shows no chip). Never a filter value. */
    NOT_REQUIRED
}
