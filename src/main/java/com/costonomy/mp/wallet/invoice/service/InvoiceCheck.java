package com.costonomy.mp.wallet.invoice.service;

import com.costonomy.mp.wallet.invoice.web.InvoiceDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** The bill's total against what the wallet paid. A difference of half a rupee or less is a match. */
public final class InvoiceCheck {

    static final BigDecimal TOLERANCE = new BigDecimal("0.5");

    private InvoiceCheck() {
    }

    /** {@code difference} is the bill total minus what was paid: positive when the bill is the larger. */
    public static InvoiceDtos.Check of(BigDecimal paid, BigDecimal billTotal) {
        return of(paid, billTotal, billTotal);
    }

    /**
     * {@code billTotal}: the reviewed total, else the reading's; {@code readingTotal}: the total as read, compared on
     * its own (D-115).
     */
    public static InvoiceDtos.Check of(BigDecimal paid, BigDecimal billTotal, BigDecimal readingTotal) {
        BigDecimal paidRupees = paid.setScale(2, RoundingMode.HALF_UP);
        BigDecimal bill = billTotal == null ? null : billTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal read = readingTotal == null ? null : readingTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal difference = bill == null ? null : bill.subtract(paidRupees);
        return new InvoiceDtos.Check(paidRupees, bill,
                difference == null ? null : difference.abs().compareTo(TOLERANCE) <= 0, difference, read,
                read == null ? null : read.subtract(paidRupees).abs().compareTo(TOLERANCE) <= 0);
    }
}
