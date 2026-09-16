package com.costonomy.mp.supplier.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * When a store is open. Doc 03 §15.
 *
 * <p><b>This is a trading rule, not a display preference.</b> A store that is
 * shut cannot answer an order, and an order placed against one sits counting down
 * to an expiry nobody was ever going to prevent — the restaurant waits the full
 * window to learn what was knowable when they tapped. So the same hours that this
 * screen edits are read by discovery when it decides who to show.
 *
 * <p>Defaults are all seven days, 10:00 to 21:00, because that is what most of
 * this market does and a supplier who never opens this screen should still be
 * findable. <b>Absent is the default, not "closed"</b> — every store predating
 * this field would otherwise vanish from search the day it shipped.
 */
public record OperatingHours(Set<DayOfWeek> days, LocalTime opensAt, LocalTime closesAt) {

    /** Asia/Kolkata. A store's hours are local to the store, not to the reader. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public static final LocalTime DEFAULT_OPEN = LocalTime.of(10, 0);
    public static final LocalTime DEFAULT_CLOSE = LocalTime.of(21, 0);

    public static OperatingHours defaults() {
        return new OperatingHours(EnumSet.allOf(DayOfWeek.class), DEFAULT_OPEN, DEFAULT_CLOSE);
    }

    public OperatingHours {
        days = days == null || days.isEmpty()
                ? EnumSet.allOf(DayOfWeek.class)
                : EnumSet.copyOf(days);
        opensAt = opensAt == null ? DEFAULT_OPEN : opensAt;
        closesAt = closesAt == null ? DEFAULT_CLOSE : closesAt;
    }

    /**
     * Whether the store is trading at {@code at}.
     *
     * <p>Handles a window that crosses midnight — 18:00 to 02:00 is a real way to
     * run a supply business, and treating {@code closesAt < opensAt} as "always
     * shut" would close those stores permanently. On such a window the day tested
     * is the day the shift <em>started</em>: an order at 01:00 on Tuesday belongs
     * to Monday's opening.
     */
    public boolean isOpenAt(ZonedDateTime at) {
        ZonedDateTime local = at.withZoneSameInstant(ZONE);
        LocalTime time = local.toLocalTime();

        if (closesAt.isAfter(opensAt)) {
            return days.contains(local.getDayOfWeek())
                    && !time.isBefore(opensAt) && time.isBefore(closesAt);
        }
        if (closesAt.equals(opensAt)) {
            // Equal ends mean around the clock, which is how a 24-hour store is
            // expressed without a separate flag to forget.
            return days.contains(local.getDayOfWeek());
        }
        // Crosses midnight.
        return time.isBefore(closesAt)
                ? days.contains(local.getDayOfWeek().minus(1))
                : !time.isBefore(opensAt) && days.contains(local.getDayOfWeek());
    }

    public List<String> dayNames() {
        return days.stream().sorted().map(Enum::name).toList();
    }
}
