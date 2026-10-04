package com.costonomy.mp.wallet.invoice.reader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What we keep of a bill reading (D-113, D-114). The bill's own fields, plus two small matches the cost app
 * made against its own lists, which the review screen starts from: per line {@code skuMatch} (id, name, unit,
 * current price per unit, category) and for the bill {@code supplierMatch} (id, name). Nothing else of the cost
 * app (its users, costs, yield, wastage, tax codes, item master) is part of this type. Every string is
 * length-capped and the items are capped, because the text is whatever was printed on a stranger's paper.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InvoiceReading(
        String vendorName,
        String vendorAddress,
        String invoiceNumber,
        /** As read ("04/09/26"); never parsed. */
        String invoiceDate,
        String customerName,
        String currency,
        List<Item> items,
        BigDecimal subtotal,
        BigDecimal tax,
        BigDecimal delivery,
        BigDecimal total,
        /** The cost app's supplier for this bill, or null when it matched none. */
        SupplierMatch supplierMatch,
        /**
         * How many bills the reader found in the pages (D-115); only the first is kept. Null when not known (older
         * readings, the fake reader).
         */
        Integer invoiceCount) {

    public static final int MAX_TEXT = 500;
    public static final int MAX_ITEMS = 100;
    public static final int MAX_NAME = 150;
    public static final int MAX_UNIT = 20;
    public static final int MAX_CATEGORY = 100;
    /** The stored columns are DECIMAL(19,4); a figure this large or larger is not a reading (D-115). */
    public static final BigDecimal MAX_NUMBER = new BigDecimal("1E14");
    public static final int MAX_CURRENCY = 16;
    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("\\b([A-Z]{3})\\b");

    /** A reading without a supplier match (older readings, and bills the cost app could not match). */
    public InvoiceReading(String vendorName, String vendorAddress, String invoiceNumber, String invoiceDate,
                          String customerName, String currency, List<Item> items, BigDecimal subtotal,
                          BigDecimal tax, BigDecimal delivery, BigDecimal total) {
        this(vendorName, vendorAddress, invoiceNumber, invoiceDate, customerName, currency, items, subtotal, tax,
                delivery, total, null, null);
    }

    /** A reading with a supplier match, without a bill count. */
    public InvoiceReading(String vendorName, String vendorAddress, String invoiceNumber, String invoiceDate,
                          String customerName, String currency, List<Item> items, BigDecimal subtotal,
                          BigDecimal tax, BigDecimal delivery, BigDecimal total, SupplierMatch supplierMatch) {
        this(vendorName, vendorAddress, invoiceNumber, invoiceDate, customerName, currency, items, subtotal, tax,
                delivery, total, supplierMatch, null);
    }

    /**
     * One line as printed. {@code amount} is the line before tax (the cost app's {@code taxableAmount}) and
     * {@code tax} its tax; {@code total} is what the line says in all. {@code skuMatch} is null when the cost app
     * matched no SKU.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String name, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal total,
                       BigDecimal amount, BigDecimal tax, SkuMatch skuMatch) {

        public Item(String name, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal total) {
            this(name, quantity, unit, unitPrice, total, null, null, null);
        }
    }

    /**
     * The cost app's SKU for a line. {@code unitPrice} is the SKU's current price per unit as the cost app's
     * own review screen shows it ("₹360.00/KG", and the reference for "deviates from ₹360"): its
     * {@code itemPrice}, or {@code initialItemPrice} for a SKU with no purchase yet.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkuMatch(Long id, String name, String unit, BigDecimal unitPrice, String categoryName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplierMatch(Long id, String name) {
    }

    /**
     * The same reading with every string capped, the items limited and every value made to fit what is stored
     * (D-115): the currency is a three-letter code when one is printed ("Indian Rupees (INR)" is INR), else at most
     * 16 characters, else none; a number of 10^14 or more is unreadable and dropped; at most 4 decimals are kept.
     */
    public InvoiceReading capped() {
        var capped = new ArrayList<Item>();
        if (items != null) {
            for (Item item : items) {
                if (capped.size() >= MAX_ITEMS) {
                    break;
                }
                if (item == null) {
                    continue;
                }
                capped.add(new Item(cap(item.name()), number(item.quantity()), cap(item.unit(), MAX_UNIT),
                        number(item.unitPrice()), number(item.total()), number(item.amount()), number(item.tax()),
                        capped(item.skuMatch())));
            }
        }
        return new InvoiceReading(cap(vendorName), cap(vendorAddress), cap(invoiceNumber), cap(invoiceDate),
                cap(customerName), currency(currency), List.copyOf(capped), number(subtotal), number(tax),
                number(delivery), number(total), capped(supplierMatch), invoiceCount);
    }

    /** The same reading without the cost app's supplier and SKU matches (an outlet with no cost outlet, D-115). */
    public InvoiceReading withoutMatches() {
        var plain = new ArrayList<Item>();
        if (items != null) {
            for (Item item : items) {
                plain.add(item == null ? null : new Item(item.name(), item.quantity(), item.unit(), item.unitPrice(),
                        item.total(), item.amount(), item.tax(), null));
            }
        }
        return new InvoiceReading(vendorName, vendorAddress, invoiceNumber, invoiceDate, customerName, currency,
                plain, subtotal, tax, delivery, total, null, invoiceCount);
    }

    /** A three-letter code when one is printed, else the text when it fits 16 characters, else null. */
    static String currency(String text) {
        String t = cap(text, MAX_TEXT);
        if (t == null) {
            return null;
        }
        if (t.length() == 3 && t.chars().allMatch(Character::isLetter)) {
            return t.toUpperCase(java.util.Locale.ROOT);
        }
        var m = CODE.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        return t.length() <= MAX_CURRENCY ? t : null;
    }

    /** Null when unreadable (10^14 or more either way); at most 4 decimals. */
    public static BigDecimal number(BigDecimal v) {
        if (v == null || v.abs().compareTo(MAX_NUMBER) >= 0) {
            return null;
        }
        return v.scale() > 4 ? v.setScale(4, java.math.RoundingMode.HALF_UP) : v;
    }

    /** A price per unit as the review takes it: at most 4 decimals, null when unreadable. */
    public static BigDecimal price(BigDecimal v) {
        return number(v);
    }

    private static SkuMatch capped(SkuMatch m) {
        if (m == null || m.id() == null) {
            return null;
        }
        String name = cap(m.name(), MAX_NAME);
        return name == null ? null
                : new SkuMatch(m.id(), name, cap(m.unit(), MAX_UNIT), number(m.unitPrice()), cap(m.categoryName(), MAX_CATEGORY));
    }

    private static SupplierMatch capped(SupplierMatch m) {
        if (m == null || m.id() == null) {
            return null;
        }
        String name = cap(m.name(), MAX_NAME);
        return name == null ? null : new SupplierMatch(m.id(), name);
    }

    static String cap(String text) {
        return cap(text, MAX_TEXT);
    }

    static String cap(String text, int max) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
