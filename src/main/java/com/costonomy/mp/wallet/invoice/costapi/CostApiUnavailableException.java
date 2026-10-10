package com.costonomy.mp.wallet.invoice.costapi;

import com.costonomy.mp.wallet.invoice.reader.InvoiceReadException;

/**
 * The cost app cannot be used right now: our sign-in is refused, not configured, backing off after a failure, every
 * token we have is refused, or the cost app answered 403, 408, 429 or 5xx (D-114, D-115). It is not the bill's fault,
 * so a reading that fails this way does not use up one of the bill's attempts. The message is ours and never quotes a
 * token, a password or the cost app's answer.
 *
 * <p>{@link #extractionMayHaveRun()} says whether the cost app may have started reading the bill before it failed
 * (a 408, a 5xx, a dropped connection): such a call still counts against the bill's ceiling on extraction calls. A
 * sign-in failure, a 403 or a 429 is refused before any reading starts and does not count.
 */
public class CostApiUnavailableException extends InvoiceReadException {

    private final boolean extractionMayHaveRun;

    public CostApiUnavailableException(String message) {
        this(message, false);
    }

    public CostApiUnavailableException(String message, boolean extractionMayHaveRun) {
        super(message, null);
        this.extractionMayHaveRun = extractionMayHaveRun;
    }

    public boolean extractionMayHaveRun() {
        return extractionMayHaveRun;
    }
}
