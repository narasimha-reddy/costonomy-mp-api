package com.costonomy.mp.procurement.subscription;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Which dates a subscription delivers on. Pure date arithmetic, no I/O.
 *
 * <p>WEEKLY is the same weekday as the start date and ALTERNATE_DAYS is every second day counted from
 * the start date; both used to fire every day because the frequency check ignored the start date.
 */
public final class SubscriptionSchedule {

    private static final int SEARCH_DAYS = 400;

    private SubscriptionSchedule() {
    }

    /** Whether the frequency alone puts a delivery on this date, before skips and the end date. */
    public static boolean matches(LocalDate start, LocalDate date, SubscriptionFrequency frequency) {
        if (date.isBefore(start)) {
            return false;
        }
        return switch (frequency) {
            case DAILY -> true;
            case WEEKDAYS -> date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY;
            case ALTERNATE_DAYS -> ChronoUnit.DAYS.between(start, date) % 2 == 0;
            case WEEKLY -> date.getDayOfWeek() == start.getDayOfWeek();
        };
    }

    /** Whether a delivery is due on this date: in the subscription's span, on schedule, not skipped. */
    public static boolean isDue(LocalDate start, LocalDate end, SubscriptionFrequency frequency,
                                LocalDate date, Set<LocalDate> skipped) {
        if (date.isBefore(start) || (end != null && date.isAfter(end))) {
            return false;
        }
        return !skipped.contains(date) && matches(start, date, frequency);
    }

    /**
     * The first due date on or after {@code from}, or null when there is none before the end date (or
     * within about a year, which bounds the search for a subscription that skips everything).
     */
    public static LocalDate nextOnOrAfter(LocalDate start, LocalDate end, SubscriptionFrequency frequency,
                                          LocalDate from, Set<LocalDate> skipped) {
        LocalDate candidate = from.isBefore(start) ? start : from;
        for (int i = 0; i < SEARCH_DAYS; i++) {
            if (end != null && candidate.isAfter(end)) {
                return null;
            }
            if (isDue(start, end, frequency, candidate, skipped)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return null;
    }
}
