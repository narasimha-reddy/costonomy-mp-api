package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditOfferExpiry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The 14-day offer rule in India calendar days (D-166): pure, so every boundary is exact. */
class CreditOfferExpiryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("an offer made on an India day expires at the start of that day plus 14: accepted through day 13, expired from day 14")
    void boundary() {
        Instant offered = Instant.parse("2026-10-01T06:00:00Z"); // 1 Oct 11:30 IST
        assertThat(CreditOfferExpiry.expiresOn(offered, IST)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(CreditOfferExpiry.expired(offered, LocalDate.of(2026, 10, 14), IST)).isFalse();
        assertThat(CreditOfferExpiry.expired(offered, LocalDate.of(2026, 10, 15), IST)).isTrue();
        assertThat(CreditOfferExpiry.expired(offered, LocalDate.of(2026, 10, 16), IST)).isTrue();
    }

    @Test
    @DisplayName("the day is the India day, not the UTC day: 00:00 to 05:29 IST belongs to the previous UTC date")
    void indiaDayNotUtcDay() {
        Instant justAfterIstMidnight = Instant.parse("2026-10-01T18:31:00Z"); // 2 Oct 00:01 IST, still 1 Oct in UTC
        assertThat(CreditOfferExpiry.expiresOn(justAfterIstMidnight, IST)).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(CreditOfferExpiry.expired(justAfterIstMidnight, LocalDate.of(2026, 10, 15), IST)).isFalse();
        assertThat(CreditOfferExpiry.expired(justAfterIstMidnight, LocalDate.of(2026, 10, 16), IST)).isTrue();

        Instant lateEvening = Instant.parse("2026-10-01T18:29:00Z"); // 1 Oct 23:59 IST
        assertThat(CreditOfferExpiry.expiresOn(lateEvening, IST)).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(CreditOfferExpiry.expired(lateEvening, LocalDate.of(2026, 10, 15), IST)).isTrue();
    }
}
