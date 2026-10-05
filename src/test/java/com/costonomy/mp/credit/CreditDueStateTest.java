package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.costonomy.mp.credit.domain.CreditInvoiceStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** The one rule that turns an invoice and today's India date into what the restaurant is shown. */
class CreditDueStateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    /** An open invoice due {@code dueInDays} from today, with {@code grace} days of grace. */
    private static CreditDueState of(CreditInvoiceStatus status, int dueInDays, int grace) {
        LocalDate due = TODAY.plusDays(dueInDays);
        return CreditDueState.of(status, due, due.plusDays(grace), TODAY);
    }

    @Test
    @DisplayName("settled invoices are PAID or WRITTEN_OFF whatever the dates say")
    void settled() {
        assertThat(of(PAID, -30, 0)).isEqualTo(CreditDueState.PAID);
        assertThat(of(WRITTEN_OFF, -30, 0)).isEqualTo(CreditDueState.WRITTEN_OFF);
        assertThat(of(PAID, 2, 0)).isEqualTo(CreditDueState.PAID);
    }

    @Test
    @DisplayName("an invoice already marked OVERDUE is OVERDUE")
    void overdueStatus() {
        assertThat(of(OVERDUE, -10, 5)).isEqualTo(CreditDueState.OVERDUE);
        assertThat(of(OVERDUE, -10, 5)).isEqualTo(CreditDueState.OVERDUE);
    }

    @Test
    @DisplayName("due today is DUE_TODAY, one to three days ahead is DUE_SOON, four or more is DUE_LATER")
    void futureBoundaries() {
        for (CreditInvoiceStatus open : new CreditInvoiceStatus[]{ISSUED, PARTIALLY_PAID}) {
            assertThat(of(open, 0, 5)).isEqualTo(CreditDueState.DUE_TODAY);
            assertThat(of(open, 1, 5)).isEqualTo(CreditDueState.DUE_SOON);
            assertThat(of(open, 3, 5)).isEqualTo(CreditDueState.DUE_SOON);
            assertThat(of(open, 4, 5)).isEqualTo(CreditDueState.DUE_LATER);
            assertThat(of(open, 90, 5)).isEqualTo(CreditDueState.DUE_LATER);
        }
    }

    @Test
    @DisplayName("past due is IN_GRACE up to and including overdue_after, and OVERDUE the day after")
    void graceBoundary() {
        // Due yesterday, 2 days of grace: overdue_after is tomorrow.
        assertThat(of(ISSUED, -1, 2)).isEqualTo(CreditDueState.IN_GRACE);
        // Due 2 days ago, 2 days of grace: overdue_after is today, still in grace.
        assertThat(of(ISSUED, -2, 2)).isEqualTo(CreditDueState.IN_GRACE);
        // Due 3 days ago, 2 days of grace: overdue_after was yesterday; the sweep has not caught up yet.
        assertThat(of(ISSUED, -3, 2)).isEqualTo(CreditDueState.OVERDUE);
        // No grace at all: the day after the due date is already late.
        assertThat(of(PARTIALLY_PAID, -1, 0)).isEqualTo(CreditDueState.OVERDUE);
    }

    @Test
    @DisplayName("daysToDue is negative once past due, zero today, and null once settled")
    void daysToDue() {
        assertThat(CreditDueState.daysToDue(ISSUED, TODAY.plusDays(4), TODAY)).isEqualTo(4);
        assertThat(CreditDueState.daysToDue(ISSUED, TODAY, TODAY)).isZero();
        assertThat(CreditDueState.daysToDue(OVERDUE, TODAY.minusDays(6), TODAY)).isEqualTo(-6);
        assertThat(CreditDueState.daysToDue(PAID, TODAY, TODAY)).isNull();
        assertThat(CreditDueState.daysToDue(WRITTEN_OFF, TODAY, TODAY)).isNull();
    }
}
