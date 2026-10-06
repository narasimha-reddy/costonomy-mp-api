package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditDueState;
import com.costonomy.mp.credit.domain.CreditReminderText;
import com.costonomy.mp.credit.domain.CreditReminderText.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditReminderTextTest {

    @Test
    @DisplayName("rupees are grouped the Indian way, whole rupees without decimals")
    void money() {
        assertThat(CreditReminderText.money(new BigDecimal("0"))).isEqualTo("₹0");
        assertThat(CreditReminderText.money(new BigDecimal("999.00"))).isEqualTo("₹999");
        assertThat(CreditReminderText.money(new BigDecimal("6500.0000"))).isEqualTo("₹6,500");
        assertThat(CreditReminderText.money(new BigDecimal("100000"))).isEqualTo("₹1,00,000");
        assertThat(CreditReminderText.money(new BigDecimal("1234567.5"))).isEqualTo("₹12,34,567.50");
        assertThat(CreditReminderText.money(new BigDecimal("12345.675"))).isEqualTo("₹12,345.68");
    }

    @Test
    @DisplayName("an overdue reminder reads as the plan's example")
    void overdue() {
        var text = CreditReminderText.reminder("Sri Dairy", List.of(new Item("INV-1", new BigDecimal("6500.00"),
                LocalDate.of(2026, 9, 24), CreditDueState.OVERDUE)), null);
        assertThat(text).isEqualTo("Sri Dairy: ₹6,500 overdue since 24 Sep (INV-1). Pay in Mandi or tell them you paid.");
    }

    @Test
    @DisplayName("overdue and upcoming invoices are two sentences; more than three invoice numbers become '+N more'")
    void mixedAndLong() {
        var items = List.of(
                new Item("A", new BigDecimal("100"), LocalDate.of(2026, 9, 1), CreditDueState.OVERDUE),
                new Item("B", new BigDecimal("100"), LocalDate.of(2026, 9, 2), CreditDueState.OVERDUE),
                new Item("C", new BigDecimal("100"), LocalDate.of(2026, 9, 3), CreditDueState.OVERDUE),
                new Item("D", new BigDecimal("100"), LocalDate.of(2026, 9, 4), CreditDueState.OVERDUE),
                new Item("E", new BigDecimal("50"), LocalDate.of(2026, 10, 9), CreditDueState.DUE_SOON));
        assertThat(CreditReminderText.reminder(null, items, " hi  there "))
                .isEqualTo("Your supplier: ₹400 overdue since 1 Sep (A, B, C +1 more). ₹50 due on 9 Oct (E). "
                        + "Pay in Mandi or tell them you paid. Message from Your supplier: hi there");
    }

    @Test
    @DisplayName("the digest names only what is not zero, and is empty when everything is")
    void digest() {
        assertThat(CreditReminderText.digest(0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0)).isEmpty();
        assertThat(CreditReminderText.digest(1, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, 0))
                .isEqualTo("1 payment claim waiting");
        assertThat(CreditReminderText.digest(3, 2, new BigDecimal("14000"), 2, new BigDecimal("22000"), 1, 4))
                .isEqualTo("3 payment claims waiting (2 for 7+ days) · ₹14,000 overdue from 2 restaurants · "
                        + "₹22,000 due this week · 1 credit request to answer · 4 payouts pending");
    }
}
