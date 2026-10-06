package com.costonomy.mp.billing.service;

import com.costonomy.mp.procurement.domain.Pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an order's stored line figures into invoice lines. Pure arithmetic over what the order already charged:
 * the stored taxable value and GST of each line, at the billable quantity for a weighed line. Nothing is priced
 * again from raw weights, so the invoice can only say what the buyer was actually asked to pay.
 */
public final class InvoiceCalculator {

    /** One invoice line. */
    public record InvoiceLine(Long orderItemId, String productName, String hsnCode, BigDecimal quantity,
                              String unit, BigDecimal unitPrice, BigDecimal gstRate, BigDecimal taxable,
                              BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {
    }

    /** The invoice's lines and totals. */
    public record Result(List<InvoiceLine> lines, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
                         BigDecimal igst, BigDecimal deliveryFee, BigDecimal total) {
    }

    private InvoiceCalculator() {
    }

    /**
     * @throws IllegalStateException when the invoice does not add up to what the order says is payable: that is a
     *                               bug or corrupt data, and nothing is written.
     */
    public static Result calculate(BillingDirectory.OrderFacts order, List<BillingDirectory.Line> orderLines,
                                   boolean interState) {
        var lines = new ArrayList<InvoiceLine>();
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal linesTotal = BigDecimal.ZERO;

        for (var line : orderLines) {
            BigDecimal quantity = line.suppliedQuantity();
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            var split = split(line.lineGst(), interState);
            lines.add(new InvoiceLine(line.id(), line.productName(), line.hsnCode(), quantity, line.unit(),
                    line.unitPrice(), line.gstRate(), line.lineItemValue(), split[0], split[1], split[2],
                    line.lineTotal()));
            taxable = taxable.add(line.lineItemValue());
            cgst = cgst.add(split[0]);
            sgst = sgst.add(split[1]);
            igst = igst.add(split[2]);
            linesTotal = linesTotal.add(line.lineTotal());
        }

        BigDecimal deliveryFee = order.deliveryFee() == null ? BigDecimal.ZERO : order.deliveryFee();
        BigDecimal total = Pricing.money(linesTotal.add(deliveryFee));

        // What the order says the buyer owed for the supply as it left the supplier: final payable plus whatever a
        // doorstep rejection has since taken off (that comes back through a credit note), or, before the order
        // settled, the accepted amount less the weight adjustment.
        BigDecimal expected = Pricing.money(order.payableAtDispatch());
        if (total.compareTo(expected) != 0) {
            throw new IllegalStateException("Invoice for order %d totals %s but the order says %s was payable"
                    .formatted(order.id(), total.toPlainString(), expected.toPlainString()));
        }
        return new Result(lines, Pricing.money(taxable), Pricing.money(cgst), Pricing.money(sgst),
                Pricing.money(igst), Pricing.money(deliveryFee), total);
    }

    /** [cgst, sgst, igst]: an intra-state supply splits the tax in two (SGST takes the odd paisa), inter-state is IGST. */
    public static BigDecimal[] split(BigDecimal gst, boolean interState) {
        if (interState) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, gst};
        }
        BigDecimal cgst = gst.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return new BigDecimal[] {cgst, gst.subtract(cgst), BigDecimal.ZERO};
    }
}
