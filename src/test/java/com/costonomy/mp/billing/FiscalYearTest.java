package com.costonomy.mp.billing;

import com.costonomy.mp.billing.service.FiscalYear;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiscalYearTest {

    @Test
    @DisplayName("the financial year turns over at midnight India time on 1 April")
    void turnsOverAtIstMidnight() {
        // 31 March 2026, 23:59 IST
        assertThat(FiscalYear.startYear(Instant.parse("2026-03-31T18:29:59Z"))).isEqualTo(2025);
        // 1 April 2026, 00:00 IST
        assertThat(FiscalYear.startYear(Instant.parse("2026-03-31T18:30:00Z"))).isEqualTo(2026);
        assertThat(FiscalYear.startYear(Instant.parse("2026-12-15T10:00:00Z"))).isEqualTo(2026);
        assertThat(FiscalYear.startYear(Instant.parse("2027-02-10T10:00:00Z"))).isEqualTo(2026);
    }

    @Test
    @DisplayName("numbers are at most 16 characters: INV/2627/000123 is 15, and a longer one is refused")
    void numbers() {
        assertThat(FiscalYear.label(2026)).isEqualTo("2627");
        assertThat(FiscalYear.label(2099)).isEqualTo("9900");
        assertThat(FiscalYear.number("INV", 2026, 123)).isEqualTo("INV/2627/000123").hasSize(15);
        assertThat(FiscalYear.number("CN", 2026, 1)).isEqualTo("CN/2627/000001");
        assertThat(FiscalYear.number("INV", 2026, 9_999_999)).hasSize(16);
        assertThatThrownBy(() -> FiscalYear.number("INV", 2026, 10_000_000))
                .isInstanceOf(IllegalStateException.class);
    }
}
