package com.costonomy.mp.wallet.invoice.reader;

/** The reader did not answer: down, slow, or an answer we cannot use. The message is ours, never the reader's body. */
public class InvoiceReadException extends RuntimeException {

    public InvoiceReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
