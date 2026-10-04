package com.costonomy.mp.procurement.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionScheduleTest {

    // 2026-10-05 is a Monday.
    private static final LocalDate START = LocalDate.of(2026, 10, 5);

    private List<LocalDate> due(SubscriptionFrequency frequency, LocalDate start, int days, Set<LocalDate> skipped) {
        var dates = new ArrayList<LocalDate>();
        for (int i = 0; i < days; i++) {
            var date = start.plusDays(i);
            if (SubscriptionSchedule.isDue(start, null, frequency, date, skipped)) {
                dates.add(date);
            }
        }
        return dates;
    }

    @Test
    @DisplayName("weekly is the same weekday as the start date, not every day")
    void weekly() {
        assertThat(due(SubscriptionFrequency.WEEKLY, START, 22, Set.of()))
                .containsExactly(START, START.plusWeeks(1), START.plusWeeks(2), START.plusWeeks(3));
        var wednesday = START.plusDays(2);
        assertThat(due(SubscriptionFrequency.WEEKLY, wednesday, 15, Set.of()))
                .containsExactly(wednesday, wednesday.plusWeeks(1), wednesday.plusWeeks(2));
    }

    @Test
    @DisplayName("alternate days counts even day offsets from the start date")
    void alternateDays() {
        assertThat(due(SubscriptionFrequency.ALTERNATE_DAYS, START, 8, Set.of()))
                .containsExactly(START, START.plusDays(2), START.plusDays(4), START.plusDays(6));
        var tuesday = START.plusDays(1);
        assertThat(due(SubscriptionFrequency.ALTERNATE_DAYS, tuesday, 5, Set.of()))
                .containsExactly(tuesday, tuesday.plusDays(2), tuesday.plusDays(4));
    }

    @Test
    @DisplayName("weekdays skip Saturday and Sunday; daily is every day")
    void weekdaysAndDaily() {
        assertThat(due(SubscriptionFrequency.WEEKDAYS, START, 7, Set.of())).hasSize(5);
        assertThat(due(SubscriptionFrequency.DAILY, START, 7, Set.of())).hasSize(7);
    }

    @Test
    @DisplayName("skipped dates and the end date remove deliveries; nothing is due before the start")
    void skipsEndAndStart() {
        assertThat(due(SubscriptionFrequency.DAILY, START, 3, Set.of(START.plusDays(1))))
                .containsExactly(START, START.plusDays(2));
        assertThat(SubscriptionSchedule.isDue(START, START.plusDays(1), SubscriptionFrequency.DAILY,
                START.plusDays(2), Set.of())).isFalse();
        assertThat(SubscriptionSchedule.isDue(START, null, SubscriptionFrequency.DAILY,
                START.minusDays(1), Set.of())).isFalse();
    }

    @Test
    @DisplayName("next due date: first on-schedule, non-skipped date on or after a day; null once the span ends")
    void nextOnOrAfter() {
        assertThat(SubscriptionSchedule.nextOnOrAfter(START, null, SubscriptionFrequency.WEEKLY,
                START.plusDays(1), Set.of())).isEqualTo(START.plusWeeks(1));
        assertThat(SubscriptionSchedule.nextOnOrAfter(START, null, SubscriptionFrequency.WEEKLY,
                START.plusWeeks(1), Set.of(START.plusWeeks(1)))).isEqualTo(START.plusWeeks(2));
        assertThat(SubscriptionSchedule.nextOnOrAfter(START, START.plusDays(3), SubscriptionFrequency.WEEKLY,
                START.plusDays(1), Set.of())).isNull();
        assertThat(SubscriptionSchedule.nextOnOrAfter(START, null, SubscriptionFrequency.DAILY,
                START.minusDays(5), Set.of())).isEqualTo(START);
    }
}
