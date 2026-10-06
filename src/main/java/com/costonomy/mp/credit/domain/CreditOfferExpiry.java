package com.costonomy.mp.credit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * When a credit offer the restaurant has not accepted lapses (D-137). The one place the rule lives, pure so every
 * boundary can be tested.
 *
 * <p>An offer lasts 14 India calendar days: made on day D, it can be accepted through D+13 and has expired from
 * D+14. The day is the India day ({@code zone}, the credit clock's), never the UTC day: an offer made at 00:30 IST
 * belongs to the new India day although UTC still says yesterday.
 */
public final class CreditOfferExpiry {

    public static final int DAYS = 14;

    private CreditOfferExpiry() {
    }

    /** The first India day on which the offer made at {@code offeredAt} is no longer open. */
    public static LocalDate expiresOn(Instant offeredAt, ZoneId zone) {
        return LocalDate.ofInstant(offeredAt, zone).plusDays(DAYS);
    }

    public static boolean expired(Instant offeredAt, LocalDate today, ZoneId zone) {
        return !today.isBefore(expiresOn(offeredAt, zone));
    }
}
