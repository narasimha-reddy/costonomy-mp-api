package com.costonomy.mp.wallet.invoice.web;

import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** What the bill endpoints return (D-113). No storage keys, bucket names, tokens or reader data. */
public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    public record Invoice(
            String status,
            Instant createdAt,
            Long uploadedBy,
            int pageCount,
            List<Page> pages,
            /** Null until the bill has been read. */
            InvoiceReading reading,
            Check check,
            /** A plain sentence, or null. */
            String error,
            int attempts,
            /** Tries that found the cost app unavailable; they use no attempt (D-115). */
            int unavailableCount,
            /** When the next try is due after the cost app was unavailable, else null (D-115). */
            Instant nextTryAt,
            /** Send it back with a review; a review against an older version is a 409 INVOICE_CHANGED. */
            Long version,
            /**
             * What the review screen starts from (D-114): built from the reading, or an empty form when the bill could
             * not be read. Present whenever the status is READ or UNREADABLE, also once a review exists (D-115), so
             * "start over" can always go back to what was read. Null while READING.
             */
            InvoiceReviewDtos.Review draft,
            /** The user's saved review, or null. {@code reading} is never changed by it. */
            InvoiceReviewDtos.Review review) {
    }

    public record Page(int page, String contentType, long sizeBytes, String url, Instant expiresAt) {
    }

    /**
     * The bill's total against what the wallet paid. {@code billTotal} is the reviewed total once there is a review,
     * else the reading's; {@code readingTotal} and {@code matchesReading} always compare the total as originally read
     * (D-115), so an edit cannot hide a difference. A difference of half a rupee or less is a match.
     */
    public record Check(BigDecimal paid, BigDecimal billTotal, Boolean matches, BigDecimal difference,
                        BigDecimal readingTotal, Boolean matchesReading) {
    }

    /** On the transaction page: enough to show a thumbnail and a line. */
    public record Summary(String status, String vendorName, BigDecimal total, String thumbnailUrl) {
    }
}
