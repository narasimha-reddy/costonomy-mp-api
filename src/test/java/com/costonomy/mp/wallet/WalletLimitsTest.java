package com.costonomy.mp.wallet;

import com.costonomy.mp.wallet.service.WalletLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The month a top-up counts in is the restaurant's, not UTC's (D-107). */
class WalletLimitsTest {

    private final WalletLimits limits = new WalletLimits(
            new BigDecimal("100000"), new BigDecimal("1000000"), BigDecimal.TEN, new BigDecimal("100000"));

    @Test
    @DisplayName("the month runs from midnight IST on the 1st, which is 18:30 UTC the evening before")
    void monthBoundsAreIst() {
        var midMonth = Instant.parse("2026-09-15T10:00:00Z");

        assertThat(limits.monthStart(midMonth)).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
        assertThat(limits.monthEnd(midMonth)).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
    }

    @Test
    @DisplayName("half past six in the evening UTC on the 30th is already next month in India")
    void lateUtcIsNextMonthInIndia() {
        var instant = Instant.parse("2026-09-30T18:30:00Z");

        assertThat(limits.monthStart(instant)).isEqualTo(Instant.parse("2026-09-30T18:30:00Z"));
        assertThat(limits.monthEnd(instant)).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        // One second earlier it is still September.
        assertThat(limits.monthStart(instant.minusSeconds(1))).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
    }

    @Test
    @DisplayName("December rolls into January")
    void yearEnd() {
        assertThat(limits.monthEnd(Instant.parse("2026-12-10T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-12-31T18:30:00Z"));
    }
}
