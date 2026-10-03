package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.FromInvoice;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.ItemIn;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.Line;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.Party;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.Review;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.ReviewRequest;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.Sku;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bill review (D-114): the draft the screen starts from, the checks on what it sends, and the money, which
 * only the server adds up (BigDecimal, two decimals, half up). Follows the cost app's upload screen: a line's
 * amount is before tax, its total is amount + tax, the subtotal and tax are the sums of the lines, delivery is a
 * charge of its own (tax inclusive, pre-filled from the bill, not derived from the lines), and the total is
 * subtotal + tax + delivery. A line's price is flagged when it is more than 50% away from the SKU's price.
 */
public final class InvoiceReviews {

    public static final int MAX_ITEMS = 100;
    public static final int MAX_NAME = 150;
    public static final int MAX_INVOICE_NUMBER = 60;
    public static final int MAX_UNIT = 20;
    public static final int MAX_DATE_TEXT = 30;
    static final Set<String> PAYMENT_STATUSES = Set.of("PENDING", "COMPLETED");
    static final BigDecimal DEVIATION = new BigDecimal("0.5");

    private InvoiceReviews() {
    }

    // ── draft ────────────────────────────────────────────────────────────

    /**
     * What the screen starts from when there is no review yet. From a reading: the cost app's supplier match (else
     * the name as read, without an id), each line with its SKU match, today as the stock-in date (India), PENDING,
     * the delivery as read (else 0). Without a reading (an unreadable bill): an empty form with no lines.
     */
    public static Review draft(InvoiceReading reading, LocalDate today) {
        if (reading == null) {
            return compute(new Party(null, ""), null, null, today.toString(), "PENDING", List.of(), BigDecimal.ZERO,
                    false, null, null, null);
        }
        Party supplier = reading.supplierMatch() != null
                ? new Party(reading.supplierMatch().id(), reading.supplierMatch().name())
                : new Party(null, reading.vendorName() == null ? "" : cap(reading.vendorName(), MAX_NAME));
        var lines = new ArrayList<Line>();
        List<InvoiceReading.Item> items = reading.items() == null ? List.of() : reading.items();
        for (int i = 0; i < items.size() && i < MAX_ITEMS; i++) {
            var item = items.get(i);
            var match = item.skuMatch();
            Sku sku = match == null ? null : new Sku(match.id(), match.name(), match.unit(), match.unitPrice());
            BigDecimal tax = item.tax() != null ? item.tax() : BigDecimal.ZERO;
            BigDecimal amount = item.amount() != null ? item.amount()
                    : item.total() != null && item.tax() != null ? item.total().subtract(item.tax())
                    : item.total();
            String unit = sku != null && sku.unit() != null ? sku.unit() : cap(item.unit(), MAX_UNIT);
            BigDecimal quantity = item.quantity() == null || item.quantity().scale() <= 3 ? item.quantity()
                    : item.quantity().setScale(3, RoundingMode.HALF_UP);
            lines.add(line(i + 1, fromInvoice(item), sku, quantity, unit, amount, tax, false));
        }
        // Tax printed only for the whole bill (D-115): the lines carry none, so the bill's figure is kept.
        BigDecimal lineTax = BigDecimal.ZERO;
        for (Line l : lines) {
            lineTax = lineTax.add(l.tax());
        }
        BigDecimal taxOverride = lineTax.signum() == 0 && reading.tax() != null && reading.tax().signum() > 0
                ? scale(reading.tax()) : null;
        return compute(supplier, cap(reading.invoiceNumber(), MAX_INVOICE_NUMBER), reading.invoiceDate(),
                today.toString(), "PENDING", lines, readDelivery(reading), false, taxOverride, null, null);
    }

    // ── review ───────────────────────────────────────────────────────────

    /**
     * The review the user saved, checked and computed. Throws VALIDATION_ERROR with one plain sentence per field
     * ({@code details.fields}).
     */
    public static Review review(ReviewRequest in, InvoiceReading reading, LocalDate today, Instant now, Long actorId) {
        var errors = new LinkedHashMap<String, Object>();

        Party supplier = null;
        if (in.supplier() == null || blank(in.supplier().name())) {
            errors.put("supplier.name", "Enter the supplier's name.");
        } else if (in.supplier().name().strip().length() > MAX_NAME) {
            errors.put("supplier.name", "The supplier's name can be at most " + MAX_NAME + " characters.");
        } else if (in.supplier().id() != null && in.supplier().id() <= 0) {
            errors.put("supplier.id", "Choose a supplier from the list, or leave it empty for a new one.");
        } else {
            supplier = new Party(in.supplier().id(), in.supplier().name().strip());
        }

        String invoiceNumber = blank(in.invoiceNumber()) ? null : in.invoiceNumber().strip();
        if (invoiceNumber != null && invoiceNumber.length() > MAX_INVOICE_NUMBER) {
            errors.put("invoiceNumber", "The invoice number can be at most " + MAX_INVOICE_NUMBER + " characters.");
        }

        String invoiceDate = blank(in.invoiceDate()) ? null : in.invoiceDate().strip();
        if (invoiceDate != null) {
            boolean asRead = reading != null && invoiceDate.equals(reading.invoiceDate());
            if (!asRead && (invoiceDate.length() > MAX_DATE_TEXT || parseDate(invoiceDate) == null)) {
                errors.put("invoiceDate", "Enter the invoice date as YYYY-MM-DD.");
            }
        }

        LocalDate stockIn = blank(in.stockInDate()) ? null : parseDate(in.stockInDate().strip());
        if (stockIn == null) {
            errors.put("stockInDate", "Enter the stock-in date as YYYY-MM-DD.");
        } else if (stockIn.isAfter(today.plusDays(1))) {
            errors.put("stockInDate", "The stock-in date cannot be later than tomorrow.");
        }

        String paymentStatus = in.paymentStatus() == null ? null : in.paymentStatus().strip();
        if (paymentStatus == null || !PAYMENT_STATUSES.contains(paymentStatus)) {
            errors.put("paymentStatus", "Choose PENDING or COMPLETED.");
        }

        BigDecimal delivery = in.delivery() == null ? BigDecimal.ZERO : in.delivery();
        money(errors, "delivery", delivery, "Delivery charges");
        if (in.taxOverride() != null) {
            money(errors, "taxOverride", in.taxOverride(), "Tax");
        }

        List<ItemIn> items = in.items() == null ? List.of() : in.items();
        if (items.isEmpty()) {
            errors.put("items", "Add at least one item.");
        } else if (items.size() > MAX_ITEMS) {
            errors.put("items", "A bill can have at most " + MAX_ITEMS + " items.");
        }
        List<InvoiceReading.Item> readItems = reading == null || reading.items() == null ? List.of() : reading.items();
        var lines = new ArrayList<Line>();
        var seen = new HashSet<Integer>();
        for (int i = 0; i < items.size() && i < MAX_ITEMS; i++) {
            var item = items.get(i);
            String at = "items[" + i + "]";
            if (item == null) {
                errors.put(at, "This line is empty.");
                continue;
            }
            int before = errors.size();
            FromInvoice from = null;
            if (item.lineNo() != null) {
                if (item.lineNo() < 1 || item.lineNo() > readItems.size()) {
                    errors.put(at + ".lineNo", "This line is not on the bill.");
                } else if (!seen.add(item.lineNo())) {
                    errors.put(at + ".lineNo", "Each bill line can appear once.");
                } else {
                    from = fromInvoice(readItems.get(item.lineNo() - 1));
                }
            }
            var s = item.sku();
            if (s == null || blank(s.name())) {
                errors.put(at + ".sku.name", "Choose or name the SKU for this line.");
            } else if (s.name().strip().length() > MAX_NAME) {
                errors.put(at + ".sku.name", "The SKU name can be at most " + MAX_NAME + " characters.");
            }
            if (s != null) {
                if (s.id() != null && s.id() <= 0) {
                    errors.put(at + ".sku.id", "Choose a SKU from the list, or leave it empty for a new one.");
                }
                if (s.unit() != null && s.unit().strip().length() > MAX_UNIT) {
                    errors.put(at + ".sku.unit", "The unit can be at most " + MAX_UNIT + " characters.");
                }
                if (s.unitPrice() != null) {
                    price(errors, at + ".sku.unitPrice", s.unitPrice());
                }
            }
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                errors.put(at + ".quantity", "Enter a quantity above 0.");
            } else if (!fits(item.quantity(), 9, 3)) {
                errors.put(at + ".quantity", "Use at most 9 digits and 3 decimals for the quantity.");
            }
            if (item.unit() != null && item.unit().strip().length() > MAX_UNIT) {
                errors.put(at + ".unit", "The unit can be at most " + MAX_UNIT + " characters.");
            }
            if (item.amount() == null) {
                errors.put(at + ".amount", "Enter the amount.");
            } else {
                money(errors, at + ".amount", item.amount(), "The amount");
            }
            BigDecimal tax = item.tax() == null ? BigDecimal.ZERO : item.tax();
            money(errors, at + ".tax", tax, "Tax");
            if (errors.size() != before) {
                continue;
            }
            Sku sku = new Sku(s.id(), s.name().strip(), blank(s.unit()) ? null : s.unit().strip(), s.unitPrice());
            String unit = !blank(item.unit()) ? item.unit().strip() : sku.unit();
            lines.add(line(item.lineNo(), from, sku, item.quantity(), unit, item.amount(), tax,
                    Boolean.TRUE.equals(item.ignoredDeviation())));
        }

        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                    Map.of("fields", errors));
        }
        BigDecimal deliveryScaled = scale(delivery);
        boolean overridden = deliveryScaled.compareTo(readDelivery(reading)) != 0;
        return compute(supplier, invoiceNumber, invoiceDate, stockIn.toString(), paymentStatus, lines, deliveryScaled,
                overridden, in.taxOverride() == null ? null : scale(in.taxOverride()), now, actorId);
    }

    /** The bill total to check against what was paid: the reviewed one when there is a review, else the reading's. */
    public static BigDecimal bestTotal(Review review, BigDecimal readTotal) {
        return review != null && review.total() != null ? review.total() : readTotal;
    }

    // ── money ────────────────────────────────────────────────────────────

    private static Review compute(Party supplier, String invoiceNumber, String invoiceDate, String stockInDate,
                                  String paymentStatus, List<Line> lines, BigDecimal delivery,
                                  boolean deliveryOverridden, BigDecimal taxOverride, Instant reviewedAt,
                                  Long reviewedBy) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (Line line : lines) {
            subtotal = subtotal.add(line.amount() == null ? BigDecimal.ZERO : line.amount());
            tax = tax.add(line.tax() == null ? BigDecimal.ZERO : line.tax());
        }
        BigDecimal d = scale(delivery);
        subtotal = scale(subtotal);
        tax = taxOverride != null ? scale(taxOverride) : scale(tax);
        return new Review(supplier, invoiceNumber, invoiceDate, stockInDate, paymentStatus, List.copyOf(lines), d,
                deliveryOverridden, taxOverride, subtotal, tax, scale(subtotal.add(tax).add(d)), reviewedAt,
                reviewedBy);
    }

    private static Line line(Integer lineNo, FromInvoice from, Sku sku, BigDecimal quantity, String unit,
                             BigDecimal amount, BigDecimal tax, boolean ignoredDeviation) {
        BigDecimal a = amount == null ? null : scale(amount);
        BigDecimal t = scale(tax == null ? BigDecimal.ZERO : tax);
        BigDecimal lineTotal = a == null ? null : scale(a.add(t));
        BigDecimal itemPrice = lineTotal != null && quantity != null && quantity.signum() > 0
                ? lineTotal.divide(quantity, 2, RoundingMode.HALF_UP) : null;
        return new Line(lineNo, from, sku, plain(quantity), unit, a, t, lineTotal, itemPrice,
                deviation(itemPrice, sku == null ? null : sku.unitPrice()), ignoredDeviation);
    }

    /** ABOVE or BELOW when the price is more than 50% away from the SKU's; null when it is not, or unknown. */
    static String deviation(BigDecimal itemPrice, BigDecimal reference) {
        if (itemPrice == null || reference == null || itemPrice.signum() <= 0 || reference.signum() <= 0) {
            return null;
        }
        if (itemPrice.compareTo(reference.multiply(BigDecimal.ONE.add(DEVIATION))) > 0) {
            return "ABOVE";
        }
        if (itemPrice.compareTo(reference.multiply(BigDecimal.ONE.subtract(DEVIATION))) < 0) {
            return "BELOW";
        }
        return null;
    }

    private static FromInvoice fromInvoice(InvoiceReading.Item item) {
        return new FromInvoice(item.name(), item.quantity(), item.unit(), item.unitPrice(), item.total());
    }

    private static BigDecimal readDelivery(InvoiceReading reading) {
        return scale(reading == null || reading.delivery() == null ? BigDecimal.ZERO : reading.delivery());
    }

    private static void money(Map<String, Object> errors, String field, BigDecimal value, String what) {
        if (value.signum() < 0) {
            errors.put(field, what + " cannot be negative.");
        } else if (!fits(value, 12, 2)) {
            errors.put(field, "Use at most 12 digits and 2 decimals.");
        }
    }

    /** A SKU's price per unit: the cost app keeps up to 4 decimals (per-gram prices), so 4 are allowed (D-115). */
    private static void price(Map<String, Object> errors, String field, BigDecimal value) {
        if (value.signum() < 0) {
            errors.put(field, "The price cannot be negative.");
        } else if (!fits(value, 12, 4)) {
            errors.put(field, "Use at most 12 digits and 4 decimals for the price.");
        }
    }

    static boolean fits(BigDecimal value, int integerDigits, int decimals) {
        BigDecimal v = value.stripTrailingZeros();
        int scale = Math.max(v.scale(), 0);
        int integer = v.precision() - v.scale();
        return scale <= decimals && integer <= integerDigits;
    }

    /** 2.000 → 2, 1.250 → 1.25, 1000 stays 1000 (never 1E+3). */
    private static BigDecimal plain(BigDecimal v) {
        if (v == null) {
            return null;
        }
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
