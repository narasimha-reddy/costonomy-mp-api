package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which days a statement may cover, on fixed dates so the edges do not move with the clock (D-108). */
class StatementPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private static StatementPeriod resolve(String range, String from, String to, String fy) {
        return StatementPeriod.resolve(range, from, to, fy, TODAY);
    }

    private static void rejects(String range, String from, String to, String fy, String messagePart) {
        assertThatThrownBy(() -> resolve(range, from, to, fy))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).containsIgnoringCase(messagePart);
                });
    }

    @Test
    @DisplayName("LAST_n is n days ending today, today included")
    void lastN() {
        assertThat(resolve("LAST_30", null, null, null))
                .isEqualTo(new StatementPeriod(LocalDate.of(2026, 8, 31), TODAY));
        assertThat(resolve("LAST_30", null, null, null).days()).isEqualTo(30);
        assertThat(resolve("LAST_90", null, null, null).days()).isEqualTo(90);
        assertThat(resolve("LAST_180", null, null, null).days()).isEqualTo(180);
        assertThat(resolve("last_365", null, null, null).days()).isEqualTo(365);
    }

    @Test
    @DisplayName("a period is whole IST days: midnight to midnight, the last microsecond included")
    void istBounds() {
        var p = resolve("CUSTOM", "2026-09-01", "2026-09-29", null);
        assertThat(p.start()).isEqualTo(Instant.parse("2026-08-31T18:30:00Z"));
        assertThat(p.endExclusive()).isEqualTo(Instant.parse("2026-09-29T18:30:00Z"));
    }

    @Test
    @DisplayName("a financial year is 1 April to 31 March, either side of the boundary")
    void financialYearBoundaries() {
        var past = resolve(null, null, null, "2025-26");
        assertThat(past.from()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(past.to()).isEqualTo(LocalDate.of(2026, 3, 31));
        // 31 March 23:59:59.999999 IST is in the year that ends; 1 April 00:00 IST is in the next.
        assertThat(Instant.parse("2026-03-31T18:29:59.999999Z")).isBefore(past.endExclusive());
        assertThat(Instant.parse("2026-03-31T18:30:00Z")).isEqualTo(past.endExclusive());
        assertThat(resolve(null, null, null, "2024-25").to()).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    @Test
    @DisplayName("the current financial year runs to today; one that has not started is refused")
    void financialYearCurrentAndFuture() {
        var current = resolve(null, null, null, "2026-27");
        assertThat(current.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(current.to()).isEqualTo(TODAY);
        rejects(null, null, null, "2027-28", "hasn't started");
        assertThat(StatementPeriod.resolve(null, null, null, "2026-27", LocalDate.of(2026, 4, 1)).days()).isEqualTo(1);
        rejects2("2026-27", LocalDate.of(2026, 3, 31));
    }

    private static void rejects2(String fy, LocalDate today) {
        assertThatThrownBy(() -> StatementPeriod.resolve(null, null, null, fy, today))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a century-end financial year reads 1999-00, and typos are refused")
    void financialYearShape() {
        assertThat(StatementPeriod.resolve(null, null, null, "2099-00", LocalDate.of(2100, 1, 1)).from())
                .isEqualTo(LocalDate.of(2099, 4, 1));
        rejects(null, null, null, "2025-27", "look like");
        rejects(null, null, null, "2025-25", "look like");
        rejects(null, null, null, "2025", "look like");
        rejects(null, null, null, "25-26", "look like");
        rejects(null, null, null, "1999-00", "look like");
    }

    @Test
    @DisplayName("a custom range: inclusive ends, from not after to, to not in the future")
    void custom() {
        assertThat(resolve("CUSTOM", "2026-09-29", "2026-09-29", null).days()).isEqualTo(1);
        rejects("CUSTOM", "2026-09-10", "2026-09-09", null, "after");
        rejects("CUSTOM", "2026-09-10", "2026-09-30", null, "future");
        rejects("CUSTOM", null, "2026-09-10", null, "both");
        rejects("CUSTOM", "2026-09-10", null, null, "both");
        rejects("CUSTOM", "10-09-2026", "2026-09-10", null, "date");
        rejects("CUSTOM", "2026-02-30", "2026-03-10", null, "date");
    }

    @Test
    @DisplayName("at most 366 days, both ends counted")
    void span() {
        // 366 days ending today.
        assertThat(resolve("CUSTOM", "2025-09-29", "2026-09-29", null).days()).isEqualTo(366);
        rejects("CUSTOM", "2025-09-28", "2026-09-29", null, "366");
        // A leap year is allowed whole.
        assertThat(StatementPeriod.resolve("CUSTOM", "2024-01-01", "2024-12-31", null, TODAY).days()).isEqualTo(366);
    }

    @Test
    @DisplayName("exactly one way to say which days")
    void oneWay() {
        rejects(null, null, null, null, "range or a financial year");
        rejects("LAST_30", null, null, "2025-26", "not both");
        rejects("LAST_30", "2026-09-01", null, null, "CUSTOM");
        rejects(null, "2026-09-01", null, "2025-26", "CUSTOM");
        rejects("LAST_7", null, null, null, "range must be");
    }
}
