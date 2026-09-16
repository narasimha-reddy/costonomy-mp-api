package com.costonomy.mp.supplier;

import com.costonomy.mp.supplier.domain.OperatingHours;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a store is open — which decides whether a restaurant is shown it at all.
 */
class OperatingHoursTest {

    private static ZonedDateTime at(String isoLocal) {
        return ZonedDateTime.of(java.time.LocalDateTime.parse(isoLocal), OperatingHours.ZONE);
    }

    @Test
    @DisplayName("defaults are every day, 10:00 to 21:00")
    void defaults() {
        var hours = OperatingHours.defaults();
        assertThat(hours.days()).containsExactlyInAnyOrder(DayOfWeek.values());
        assertThat(hours.opensAt()).isEqualTo(LocalTime.of(10, 0));
        assertThat(hours.closesAt()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    @DisplayName("absent values fall back to the defaults rather than closing the store")
    void absentMeansDefault() {
        // Every store predating this field has none. Reading that as "closed"
        // would remove all of them from search the day it shipped.
        var hours = new OperatingHours(null, null, null);
        assertThat(hours.days()).hasSize(7);
        assertThat(hours.isOpenAt(at("2026-09-16T12:00"))).isTrue();

        assertThat(new OperatingHours(EnumSet.noneOf(DayOfWeek.class), null, null).days())
                .hasSize(7);
    }

    @Test
    @DisplayName("open inside the window, shut outside it")
    void withinTheWindow() {
        var hours = OperatingHours.defaults();
        assertThat(hours.isOpenAt(at("2026-09-16T09:59"))).isFalse();
        assertThat(hours.isOpenAt(at("2026-09-16T10:00"))).isTrue();
        assertThat(hours.isOpenAt(at("2026-09-16T20:59"))).isTrue();
        // Closing time is when it shuts, not the last minute it is open.
        assertThat(hours.isOpenAt(at("2026-09-16T21:00"))).isFalse();
    }

    @Test
    @DisplayName("a day not traded is shut all day")
    void closedDays() {
        // 2026-09-16 is a Wednesday, 2026-09-20 a Sunday.
        var weekdays = new OperatingHours(
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                LocalTime.of(10, 0), LocalTime.of(21, 0));
        assertThat(weekdays.isOpenAt(at("2026-09-16T12:00"))).isTrue();
        assertThat(weekdays.isOpenAt(at("2026-09-20T12:00"))).isFalse();
    }

    @Test
    @DisplayName("a window crossing midnight stays open, and belongs to the day it started")
    void crossesMidnight() {
        // 18:00–02:00 is a real way to run a supply business. Treating
        // closesAt < opensAt as "always shut" would close those stores for good.
        var nights = new OperatingHours(
                EnumSet.of(DayOfWeek.WEDNESDAY), LocalTime.of(18, 0), LocalTime.of(2, 0));

        assertThat(nights.isOpenAt(at("2026-09-16T19:00"))).isTrue();   // Wed evening
        assertThat(nights.isOpenAt(at("2026-09-17T01:00"))).isTrue();   // Thu 1am, Wed's shift
        assertThat(nights.isOpenAt(at("2026-09-17T03:00"))).isFalse();  // after close
        assertThat(nights.isOpenAt(at("2026-09-16T17:00"))).isFalse();  // before open
    }

    @Test
    @DisplayName("equal open and close means around the clock")
    void allDay() {
        // Expressed without a separate always-open flag that somebody would forget
        // to check.
        var always = new OperatingHours(
                EnumSet.allOf(DayOfWeek.class), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);
        assertThat(always.isOpenAt(at("2026-09-16T03:00"))).isTrue();
        assertThat(always.isOpenAt(at("2026-09-16T23:59"))).isTrue();
    }

    @Test
    @DisplayName("hours are the store's local time, not the reader's")
    void storeLocalTime() {
        var hours = OperatingHours.defaults();
        // 06:00 UTC is 11:30 in Asia/Kolkata — open, however far away the caller is.
        assertThat(hours.isOpenAt(ZonedDateTime.parse("2026-09-16T06:00:00Z"))).isTrue();
        // 17:00 UTC is 22:30 — shut.
        assertThat(hours.isOpenAt(ZonedDateTime.parse("2026-09-16T17:00:00Z"))).isFalse();
    }
}
