package com.costonomy.mp.procurement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money arithmetic for a procurement line. Doc 02 §1, guardrail 3.
 *
 * <p><b>The only place a line total is computed.</b> The cart, the validation, the
 * supplier order and — later — the commission all come through here, so a
 * recommendation, a cart and an invoice cannot disagree about what 20 kg at ₹410
 * with 5% GST costs. A second implementation anywhere would eventually differ in
 * the last paisa, and the restaurant would be the one to notice.
 *
 * <p>Rounding is HALF_UP at 2 decimal places on every money result. The columns
 * hold DECIMAL(19,4), which leaves room for intermediate precision, but a figure
 * that reaches a person — a line total, a GST amount, an order total — is rounded
 * to paise. Leaving four decimals in a total produces invoices that do not add up
 * when a human checks them with a calculator.
 */
public final class Pricing {

    private Pricing() {
    }

    /** Money is presented and settled in paise. */
    public static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The commercial value of a line, before tax.
     *
     * <p>Rounded before GST is applied, so GST is computed on the figure that will
     * appear on the invoice rather than on an unrounded intermediate. Doing it the
     * other way round produces a GST amount that does not reconcile against the
     * printed line value.
     */
    public static BigDecimal lineItemValue(BigDecimal unitPrice, BigDecimal quantity) {
        return unitPrice.multiply(quantity).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** GST on a line, at the rate that applied when the price was snapshotted. */
    public static BigDecimal lineGst(BigDecimal lineItemValue, BigDecimal gstRatePercent) {
        return lineItemValue
                .multiply(gstRatePercent)
                .divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineTotal(BigDecimal lineItemValue, BigDecimal lineGst) {
        return lineItemValue.add(lineGst).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A unit price with its GST added.
     *
     * <p>What a buyer actually pays per unit, which is the figure to put in front
     * of somebody comparing two suppliers — one quoting exclusive and the other
     * inclusive is the classic way to make the dearer offer look cheaper.
     *
     * <p>Built from {@link #lineGst} on a single unit rather than by multiplying
     * by {@code 1 + rate/100}, so the rounding matches the line totals exactly.
     * The other way is off by a paisa often enough to be noticed on an invoice.
     */
    public static BigDecimal inclusiveOfGst(BigDecimal unitPrice, BigDecimal gstRatePercent) {
        if (unitPrice == null || gstRatePercent == null) {
            return null;
        }
        BigDecimal unit = money(unitPrice);
        return unit.add(lineGst(unit, gstRatePercent)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Normalise any money figure to the scale everything else uses. */
    public static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Compute discount amount (MRP - Selling Price).
     * Returns null if MRP is null, sellingPrice is null, or sellingPrice >= MRP.
     */
    public static BigDecimal discountAmount(BigDecimal mrp, BigDecimal sellingPrice) {
        if (mrp == null || sellingPrice == null || mrp.compareTo(sellingPrice) <= 0) {
            return null;
        }
        return mrp.subtract(sellingPrice).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Compute discount percentage: ((MRP - Selling Price) / MRP) * 100 rounded to integer percent.
     * Returns null if MRP is null, sellingPrice is null, or sellingPrice >= MRP or MRP <= 0.
     */
    public static Integer discountPercent(BigDecimal mrp, BigDecimal sellingPrice) {
        if (mrp == null || sellingPrice == null || mrp.compareTo(sellingPrice) <= 0 || mrp.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return mrp.subtract(sellingPrice)
                .multiply(HUNDRED)
                .divide(mrp, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /**
     * Whether two prices differ.
     *
     * <p>{@code compareTo}, never {@code equals}: {@code 410.00} and {@code 410.0000}
     * are the same price, and treating them as a change would make every cart
     * revalidation demand confirmation of a price that never moved — which teaches
     * restaurants to click through the confirmation that exists to protect them.
     */
    public static boolean differs(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }

    /** The taxable value, GST and total of a rejected part of a line. */
    public record Rejection(BigDecimal taxable, BigDecimal gst, BigDecimal total) {
    }

    /**
     * What rejecting {@code rejectedQuantity} of a line takes off, from the line's stored figures. The whole line is
     * exactly what it was billed at, so rounding can never refund a paisa more than was charged; a part is priced at
     * the line's unit price and rate and never exceeds the line. Receiving (the refund) and billing (the credit note)
     * both use this, so the two cannot differ by a paisa.
     */
    public static Rejection rejection(BigDecimal lineItemValue, BigDecimal lineGst, BigDecimal lineTotal,
                                      BigDecimal suppliedQuantity, BigDecimal rejectedQuantity,
                                      BigDecimal unitPrice, BigDecimal gstRatePercent) {
        if (rejectedQuantity.compareTo(suppliedQuantity) == 0 && lineTotal != null) {
            return new Rejection(lineItemValue, lineGst, lineTotal);
        }
        BigDecimal price = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        BigDecimal rate = gstRatePercent == null ? BigDecimal.ZERO : gstRatePercent;
        BigDecimal value = lineItemValue(price, rejectedQuantity);
        BigDecimal gst = lineGst(value, rate);
        BigDecimal total = lineTotal(value, gst);
        if (lineTotal != null && total.compareTo(lineTotal) > 0) {
            total = lineTotal;
            gst = gst.min(lineGst == null ? gst : lineGst);
            value = total.subtract(gst);
        }
        return new Rejection(value, gst, total);
    }
}
