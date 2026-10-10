package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditClaimAge;
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** How long a claim has waited, in India calendar days, and when that is "stale" (D-168). */
class CreditClaimAgeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("age is the difference of India calendar days, not hours and not UTC days")
    void ageIsInIndiaDays() {
        Instant submitted = Instant.parse("2026-10-01T18:31:00Z"); // 2 Oct 00:01 IST, 1 Oct in UTC
        assertThat(CreditClaimAge.ageDays(submitted, LocalDate.of(2026, 10, 2), IST)).isZero();
        assertThat(CreditClaimAge.ageDays(submitted, LocalDate.of(2026, 10, 9), IST)).isEqualTo(7);
        Instant lateEvening = Instant.parse("2026-10-01T18:29:00Z"); // 1 Oct 23:59 IST
        assertThat(CreditClaimAge.ageDays(lateEvening, LocalDate.of(2026, 10, 2), IST)).isEqualTo(1);
    }

    @Test
    @DisplayName("a waiting claim is stale from 7 days: 6 is not, 7 is, 8 is")
    void staleFromSevenDays() {
        assertThat(CreditClaimAge.isStale(CreditClaimStatus.SUBMITTED, 6)).isFalse();
        assertThat(CreditClaimAge.isStale(CreditClaimStatus.SUBMITTED, 7)).isTrue();
        assertThat(CreditClaimAge.isStale(CreditClaimStatus.SUBMITTED, 8)).isTrue();
    }

    @Test
    @DisplayName("only a claim still waiting for the supplier can be stale")
    void onlySubmittedCanBeStale() {
        for (var status : CreditClaimStatus.values()) {
            if (status != CreditClaimStatus.SUBMITTED) {
                assertThat(CreditClaimAge.isStale(status, 30)).describedAs(status.name()).isFalse();
            }
        }
    }
}
