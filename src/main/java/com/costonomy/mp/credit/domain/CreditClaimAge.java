package com.costonomy.mp.credit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * How long a claim has waited, in India calendar days, and when that is "stale" (D-168). Pure, so the boundaries
 * are testable; the app shows what the server sends and does no date arithmetic.
 */
public final class CreditClaimAge {

    /** A claim still waiting for the supplier this many days after it was made is stale; nothing is auto-rejected. */
    public static final int STALE_DAYS = 7;

    private CreditClaimAge() {
    }

    /** India days from the day the claim was made to {@code today}. */
    public static int ageDays(Instant createdAt, LocalDate today, ZoneId zone) {
        return (int) ChronoUnit.DAYS.between(LocalDate.ofInstant(createdAt, zone), today);
    }

    /** Only a claim still waiting for the supplier can be stale. */
    public static boolean isStale(CreditClaimStatus status, int ageDays) {
        return status == CreditClaimStatus.SUBMITTED && ageDays >= STALE_DAYS;
    }
}
