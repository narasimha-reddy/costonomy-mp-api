package com.costonomy.mp.catalog.domain;

import java.math.BigDecimal;

/**
 * Coercing spreadsheet cells into domain values.
 *
 * <p>Sits beside {@link Normalization} as a pure, testable value helper rather
 * than hiding inside the import service: these conversions are where a catalog
 * import quietly succeeds or rejects half a supplier's price list, and they
 * deserve to be readable and directly tested.
 *
 * <p>The principle throughout: <b>be generous about format, strict about
 * meaning.</b> A price written "₹1,450.00" is the same price; a price written
 * "call for price" is not a price, and must become a visible row error rather
 * than a zero.
 */
public final class ImportValues {

    private ImportValues() {
    }

    /**
     * A decimal from a cell.
     *
     * <p>Strips currency symbols, thousands separators and a trailing percent.
     * Returns null when the value genuinely is not a number — the caller turns
     * that into a row-level error. It must never default to zero: a price
     * silently becoming 0 is a supplier selling at nothing.
     */
    public static BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("[₹$,%\\s]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Availability from the many ways a spreadsheet says yes or no.
     *
     * <p>A <b>missing</b> column defaults to AVAILABLE: a plain price list has no
     * availability column, and everything on it is for sale. An <b>unrecognised
     * value</b> returns null and becomes a row error, because guessing could put
     * an out-of-stock item in front of a restaurant — which becomes a supplier
     * rejection, a wasted 60-second SLA, and an order that has to start again.
     */
    public static String normalizeAvailability(String raw) {
        if (raw == null || raw.isBlank()) {
            return SupplierOffer.Availability.AVAILABLE;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "available", "in stock", "instock", "yes", "y", "true", "1", "active" ->
                    SupplierOffer.Availability.AVAILABLE;
            case "out_of_stock", "out of stock", "outofstock", "no", "n", "false", "0", "inactive" ->
                    SupplierOffer.Availability.OUT_OF_STOCK;
            default -> null;
        };
    }
}
