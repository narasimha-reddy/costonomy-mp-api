package com.costonomy.mp.credit.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What a restaurant is shown about an invoice's due date (D-154). The one place the rule lives, pure so every
 * boundary can be tested: the app never does date arithmetic, it displays this and {@link #daysToDue}.
 *
 * <p>{@code today} is the India date (the credit clock). An invoice past {@code overdueAfter} that the sweep
 * has not marked yet is reported as {@link #OVERDUE} already: the grace period is over whatever the sweep's lag.
 */
public enum CreditDueState {
    PAID,
    WRITTEN_OFF,
    /** Past the grace period (or marked overdue). */
    OVERDUE,
    /** Past the due date but still inside the supplier's grace period. */
    IN_GRACE,
    DUE_TODAY,
    /** One to three days ahead. */
    DUE_SOON,
    /** More than three days ahead. */
    DUE_LATER;

    /** Days ahead that still count as "soon". */
    public static final int SOON_DAYS = 3;

    public static CreditDueState of(CreditInvoiceStatus status, LocalDate dueDate, LocalDate overdueAfter,
                                    LocalDate today) {
        if (status == CreditInvoiceStatus.PAID) {
            return PAID;
        }
        if (status == CreditInvoiceStatus.WRITTEN_OFF) {
            return WRITTEN_OFF;
        }
        if (status == CreditInvoiceStatus.OVERDUE) {
            return OVERDUE;
        }
        if (today.isAfter(dueDate)) {
            return today.isAfter(overdueAfter) ? OVERDUE : IN_GRACE;
        }
        long ahead = ChronoUnit.DAYS.between(today, dueDate);
        if (ahead == 0) {
            return DUE_TODAY;
        }
        return ahead <= SOON_DAYS ? DUE_SOON : DUE_LATER;
    }

    /** Days until the due date, negative once past it; null for a settled invoice, which has nothing to count. */
    public static Integer daysToDue(CreditInvoiceStatus status, LocalDate dueDate, LocalDate today) {
        if (status.isSettled()) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(today, dueDate);
    }
}
