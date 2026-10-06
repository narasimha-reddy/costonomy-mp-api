package com.costonomy.mp.credit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The pure rules of undoing a payment the supplier recorded (B6, D-140): how long it can be undone and what the
 * invoice becomes. Pure, so every boundary can be tested; the dates are India calendar days.
 */
public final class CreditReversalRules {

    /** Days after the recorded day a payment can be undone. */
    public static final int WINDOW_DAYS = 7;
    /** A cheque can bounce days later, so it has longer. */
    public static final int CHEQUE_WINDOW_DAYS = 30;

    private CreditReversalRules() {
    }

    /** The last India day on which the payment can still be undone: the recorded day plus 7 (30 for a cheque). */
    public static LocalDate lastDay(Instant recordedAt, String method, ZoneId zone) {
        int days = CreditPaymentMethod.CHEQUE.name().equals(method) ? CHEQUE_WINDOW_DAYS : WINDOW_DAYS;
        return LocalDate.ofInstant(recordedAt, zone).plusDays(days);
    }

    /** Only what the supplier itself put on the books can be undone by it; a payment through Mandi (WALLET) never. */
    public static boolean sourceAllowed(CreditPaymentSource source) {
        return source == CreditPaymentSource.SUPPLIER_RECORDED || source == CreditPaymentSource.CLAIM_CONFIRMED;
    }

    public static boolean isOpen(LocalDate lastDay, LocalDate today) {
        return !today.isAfter(lastDay);
    }

    /**
     * What an invoice is once a payment is taken back off it: OVERDUE when its grace period is over (the date rule of
     * {@code CreditOverdueMarker}: overdue begins the day after {@code overdueAfter}), else ISSUED with nothing taken
     * off and PARTIALLY_PAID with something taken off. {@code reducedAfter} is paid plus credited (B7): a credit note
     * on the invoice means it is not untouched even when no payment is left.
     */
    public static CreditInvoiceStatus statusAfter(LocalDate overdueAfter, BigDecimal reducedAfter, LocalDate today) {
        if (overdueAfter.isBefore(today)) {
            return CreditInvoiceStatus.OVERDUE;
        }
        return reducedAfter.signum() == 0 ? CreditInvoiceStatus.ISSUED : CreditInvoiceStatus.PARTIALLY_PAID;
    }
}
