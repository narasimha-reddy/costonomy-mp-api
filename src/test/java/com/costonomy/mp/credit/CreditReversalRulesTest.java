package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditReversalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure rules of undoing a payment: how long it can be undone, and what the invoice becomes. */
class CreditReversalRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Test
    @DisplayName("only what the supplier put on the books can be undone: a Mandi wallet payment never")
    void onlySupplierOwnedSourcesAreReversible() {
        assertThat(CreditReversalRules.sourceAllowed(CreditPaymentSource.SUPPLIER_RECORDED)).isTrue();
        assertThat(CreditReversalRules.sourceAllowed(CreditPaymentSource.CLAIM_CONFIRMED)).isTrue();
        assertThat(CreditReversalRules.sourceAllowed(CreditPaymentSource.WALLET)).isFalse();
    }

    @Test
    @DisplayName("TZ06: the window is 7 India days after the recorded day, 30 for a cheque")
    void windowLength() {
        Instant recorded = Instant.parse("2026-03-01T06:00:00Z"); // 11:30 IST on 1 March
        assertThat(CreditReversalRules.lastDay(recorded, "UPI", IST)).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(CreditReversalRules.lastDay(recorded, "CASH", IST)).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(CreditReversalRules.lastDay(recorded, "CHEQUE", IST)).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("TZ06: the recorded day is the India day: 23:50 IST and 00:10 IST are different days, whatever the UTC date")
    void istBoundary() {
        // 18:20 UTC is 23:50 IST on 1 March; 18:40 UTC is 00:10 IST on 2 March.
        Instant late = Instant.parse("2026-03-01T18:20:00Z");
        Instant early = Instant.parse("2026-03-01T18:40:00Z");
        assertThat(CreditReversalRules.lastDay(late, "UPI", IST)).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(CreditReversalRules.lastDay(early, "UPI", IST)).isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    @DisplayName("the last day is still open; the day after is closed")
    void openOnTheLastDay() {
        LocalDate last = LocalDate.of(2026, 3, 8);
        assertThat(CreditReversalRules.isOpen(last, last.minusDays(1))).isTrue();
        assertThat(CreditReversalRules.isOpen(last, last)).isTrue();
        assertThat(CreditReversalRules.isOpen(last, last.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("what the invoice becomes: past its grace period it is OVERDUE, otherwise ISSUED with nothing paid, PARTIALLY_PAID with some")
    void statusAfter() {
        LocalDate pastGrace = TODAY.minusDays(1);
        LocalDate lastGraceDay = TODAY; // overdue begins the day after
        assertThat(CreditReversalRules.statusAfter(pastGrace, BigDecimal.ZERO, TODAY)).isEqualTo(CreditInvoiceStatus.OVERDUE);
        assertThat(CreditReversalRules.statusAfter(pastGrace, new BigDecimal("5"), TODAY)).isEqualTo(CreditInvoiceStatus.OVERDUE);
        assertThat(CreditReversalRules.statusAfter(lastGraceDay, BigDecimal.ZERO, TODAY)).isEqualTo(CreditInvoiceStatus.ISSUED);
        assertThat(CreditReversalRules.statusAfter(lastGraceDay, new BigDecimal("0.01"), TODAY))
                .isEqualTo(CreditInvoiceStatus.PARTIALLY_PAID);
        assertThat(CreditReversalRules.statusAfter(TODAY.plusDays(30), BigDecimal.ZERO, TODAY)).isEqualTo(CreditInvoiceStatus.ISSUED);
    }
}
