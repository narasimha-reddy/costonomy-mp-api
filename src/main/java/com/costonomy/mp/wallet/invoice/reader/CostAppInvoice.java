package com.costonomy.mp.wallet.invoice.reader;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

/**
 * The cost app's invoice answer, cut down to the fields we use (D-113, D-114). Besides the bill's own fields we
 * keep two small matches the review screen starts from: a line's {@code sku} (only id, name, unit, price per
 * unit and category) and the bill's {@code supplier} (only id and name). Everything else it sends (its users,
 * costs, yield, wastage, tax codes, item master, contact data) is ignored by Jackson, because no field here
 * names it: it is never parsed into a value we hold, so it cannot be stored or returned. Adding a field here is
 * a decision about what we keep, not a convenience.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CostAppInvoice(
        String invoiceNumber,
        String invoiceDate,
        String vendorName,
        String vendorAddress,
        String customerName,
        List<Item> items,
        @JsonDeserialize(using = Lenient.class) BigDecimal subtotal,
        @JsonDeserialize(using = Lenient.class) BigDecimal taxAmount,
        @JsonDeserialize(using = Lenient.class) BigDecimal deliveryCharges,
        @JsonDeserialize(using = Lenient.class) BigDecimal totalAmount,
        String currency,
        String error,
        List<CostAppInvoice> invoices,
        Supplier supplier) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(
            String itemName,
            String uom,
            @JsonDeserialize(using = Lenient.class) BigDecimal quantity,
            @JsonDeserialize(using = Lenient.class) BigDecimal unitPrice,
            @JsonDeserialize(using = Lenient.class) BigDecimal totalPrice,
            @JsonDeserialize(using = Lenient.class) BigDecimal taxableAmount,
            @JsonDeserialize(using = Lenient.class) BigDecimal taxAmount,
            Sku sku) {
    }

    /**
     * The cost app's SKU ({@code SKUDetailFullResponse}), cut to what the review shows. The price per unit is
     * {@code itemPrice} (what the cost app's own upload screen shows and checks deviations against), else
     * {@code initialItemPrice} for a SKU never bought; its {@code unitPrice} field is not used.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Sku(
            Long id,
            String skuName,
            String unit,
            @JsonDeserialize(using = Lenient.class) BigDecimal itemPrice,
            @JsonDeserialize(using = Lenient.class) BigDecimal initialItemPrice,
            String categoryName) {

        InvoiceReading.SkuMatch toMatch() {
            return new InvoiceReading.SkuMatch(id, skuName, unit, itemPrice != null ? itemPrice : initialItemPrice,
                    categoryName);
        }
    }

    /** The cost app's supplier ({@code SupplierResponse}), cut to id and name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Supplier(Long id, String supplierName) {
    }

    boolean failed() {
        return error != null && !error.isBlank();
    }

    /** The reading we keep, capped. {@code invoiceCount}: how many bills the answer held (D-115). */
    InvoiceReading toReading(int invoiceCount) {
        var mapped = items == null ? List.<InvoiceReading.Item>of() : items.stream()
                .filter(i -> i != null)
                .map(i -> new InvoiceReading.Item(i.itemName(), i.quantity(), i.uom(), i.unitPrice(), i.totalPrice(),
                        i.taxableAmount(), i.taxAmount(), i.sku() == null ? null : i.sku().toMatch()))
                .toList();
        return new InvoiceReading(vendorName, vendorAddress, invoiceNumber, invoiceDate, customerName, currency,
                mapped, subtotal, taxAmount, deliveryCharges, totalAmount,
                supplier == null ? null : new InvoiceReading.SupplierMatch(supplier.id(), supplier.supplierName()),
                invoiceCount)
                .capped();
    }

    /**
     * A number, or a number written as text ("1,120.50", "Rs. 500", "₹ 2,820"); anything else is no value, not an
     * error. A known currency mark in front is dropped first, then grouping commas and spaces; what is left must be a
     * plain decimal ("-12.5"), else it is no value: "Rs. 500" is 500, never 0.5 (D-115).
     */
    static final class Lenient extends JsonDeserializer<BigDecimal> {
        private static final java.util.regex.Pattern PREFIX =
                java.util.regex.Pattern.compile("^(?i)(rs\\.?|inr|₹)\\s*");
        private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("-?\\d+(\\.\\d+)?");

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            var token = p.currentToken();
            if (token != null && token.isNumeric()) {
                return p.getDecimalValue();
            }
            if (token != null && token.isScalarValue() && p.getText() != null) {
                return parse(p.getText());
            }
            p.skipChildren();
            return null;
        }

        static BigDecimal parse(String text) {
            String t = text.strip();
            t = PREFIX.matcher(t).replaceFirst("");
            t = t.replace(",", "").replace(" ", "").replace("\u00A0", "");
            if (!PLAIN.matcher(t).matches()) {
                return null;
            }
            try {
                return new BigDecimal(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
