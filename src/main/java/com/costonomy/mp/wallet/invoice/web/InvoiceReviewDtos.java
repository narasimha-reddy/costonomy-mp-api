package com.costonomy.mp.wallet.invoice.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The user's review of a bill (D-114): what the review screen sends, and what it gets back. Money in the answer is
 * always computed by the server; any computed field the screen sends back (line totals, subtotal, total...) is
 * ignored, as is any other field this type does not name.
 */
public final class InvoiceReviewDtos {

    private InvoiceReviewDtos() {
    }

    // ── what the screen sends: PUT .../invoice/review ────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewRequest(
            /** The bill's {@code version} as last read; a stale one is a 409 INVOICE_CHANGED. */
            Long version,
            PartyIn supplier,
            String invoiceNumber,
            /** As read from the bill, or an ISO date (YYYY-MM-DD); may be empty. */
            String invoiceDate,
            /** ISO date; at most one day after today (India time). */
            String stockInDate,
            /** PENDING or COMPLETED. */
            String paymentStatus,
            List<ItemIn> items,
            /** Delivery charges on the bill, tax inclusive; empty is 0. */
            BigDecimal delivery,
            /**
             * The bill's tax when it is printed only for the whole bill (D-115): when set, the review's tax is this
             * instead of the sum of the line taxes. Null: the sum of the line taxes. At least 0, 2 decimals.
             */
            BigDecimal taxOverride) {
    }

    /** {@code id} null: a supplier the user named ("Create Supplier"), recorded on our bill only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PartyIn(Long id, String name) {
    }

    /** {@code id} null: a SKU the user named ("Create SKU"), recorded on our bill only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuIn(Long id, String name, String unit, BigDecimal unitPrice) {
    }

    /**
     * One line. {@code lineNo} is the bill line it came from (1-based, as in {@code draft}; each at most once), or null
     * for a line the user added. {@code fromInvoice} and {@code deliveryOverridden} are never read from a request:
     * the server derives them (from the reading by {@code lineNo}, and from the delivery against the bill's).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemIn(Integer lineNo, SkuIn sku, BigDecimal quantity, String unit, BigDecimal amount,
                         BigDecimal tax, Boolean ignoredDeviation) {
    }

    // ── what the screen gets: invoice.draft and invoice.review ───────────

    /**
     * A bill as reviewed ({@code review}), or the server's starting point for the screen ({@code draft}, built from
     * the reading; {@code reviewedAt} and {@code reviewedBy} are null there). {@code subtotal} is the sum of the
     * line amounts, {@code tax} is {@code taxOverride} when set, else the sum of the line taxes, and {@code total} =
     * subtotal + tax + delivery, as the cost app's own review screen adds them up (D-115).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Review(
            Party supplier,
            String invoiceNumber,
            String invoiceDate,
            String stockInDate,
            String paymentStatus,
            List<Line> items,
            BigDecimal delivery,
            boolean deliveryOverridden,
            /** The bill-level tax that replaces the sum of the line taxes, or null (D-115). */
            BigDecimal taxOverride,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            Instant reviewedAt,
            Long reviewedBy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Party(Long id, String name) {
    }

    /** What the bill said for a line; null on a line the user added. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FromInvoice(String name, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal total) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sku(Long id, String name, String unit, BigDecimal unitPrice) {
    }

    /**
     * One line. {@code lineTotal} = amount + tax; {@code itemPrice} = lineTotal / quantity; {@code deviation} is
     * ABOVE or BELOW when itemPrice is more than 50% away from the SKU's price per unit (the cost app's rule), else
     * null. All three are computed by the server.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(
            Integer lineNo,
            FromInvoice fromInvoice,
            Sku sku,
            BigDecimal quantity,
            String unit,
            BigDecimal amount,
            BigDecimal tax,
            BigDecimal lineTotal,
            BigDecimal itemPrice,
            String deviation,
            boolean ignoredDeviation) {
    }
}
