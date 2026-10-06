package com.costonomy.mp.billing.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The Indian financial year, April to March, in India time. A document belongs to the year it is issued in. */
public final class FiscalYear {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private FiscalYear() {
    }

    /** The calendar year the financial year starts in: 2026 for anything from 1 April 2026 to 31 March 2027. */
    public static int startYear(Instant instant) {
        ZonedDateTime at = instant.atZone(IST);
        return at.getMonthValue() >= 4 ? at.getYear() : at.getYear() - 1;
    }

    /** "2627" for the year starting in 2026. */
    public static String label(int startYear) {
        return "%02d%02d".formatted(startYear % 100, (startYear + 1) % 100);
    }

    /** At most 16 characters, the most a GST invoice number may have (Rule 46): INV/2627/000123 is 15. */
    public static String number(String prefix, int startYear, long sequence) {
        String number = "%s/%s/%06d".formatted(prefix, label(startYear), sequence);
        if (number.length() > 16) {
            throw new IllegalStateException("Document number " + number + " is longer than 16 characters");
        }
        return number;
    }
}
