package com.costonomy.mp.wallet.invoice.reader;

import java.util.List;

/**
 * Reads a shop's bill from its pages. The one implementation that is real calls the existing Costonomy
 * cost-app extraction API (D-113): this code holds no extraction prompt of its own.
 */
public interface InvoiceReader {

    /** @throws InvoiceReadException when the reader could not give an answer; the pages are kept and it is tried again. */
    ReadResult read(List<InvoiceFile> pages, ReadContext ctx);

    /** One stored page, as bytes. {@code filename} is made up ("page-1.jpg"), never the customer's. */
    record InvoiceFile(String filename, String contentType, byte[] content) {
    }

    /**
     * Which wallet entry is being read. {@code costOutletId} is the cost-app outlet the marketplace outlet is mapped
     * to (D-115), or null when it has none: then the reading carries no cost-app matches.
     */
    record ReadContext(long outletId, long invoiceId, Long costOutletId) {
        public ReadContext(long outletId, long invoiceId) {
            this(outletId, invoiceId, null);
        }
    }

    /**
     * The reading, or {@code reading == null} with {@code problem} when the reader answered but found
     * no bill in the pages.
     */
    record ReadResult(InvoiceReading reading, String problem) {
        public static ReadResult of(InvoiceReading reading) {
            return new ReadResult(reading, null);
        }

        public static ReadResult noBill(String problem) {
            return new ReadResult(null, problem);
        }
    }
}
