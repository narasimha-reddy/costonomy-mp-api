package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The days a statement covers, both ends inclusive, as calendar dates in India (D-108).
 *
 * <p>A statement is for whole days at the restaurant, so a period is dates, and turns into
 * instants only at the last moment: {@code from} at 00:00 IST up to, not including,
 * midnight after {@code to}. An entry at 23:59:59.999999 on the last day is in; one a
 * microsecond later is not.
 *
 * <p>All the rules about what may be asked for live in {@link #resolve}, and it takes
 * "today" as an argument so the edges (a financial year that has not ended, a period that
 * reaches tomorrow) are tested on fixed dates instead of whatever the clock says.
 */
public record StatementPeriod(LocalDate from, LocalDate to) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** Longest custom period, inclusive of both ends: a leap year. */
    public static final int MAX_DAYS = 366;

    public enum Range { LAST_30(30), LAST_90(90), LAST_180(180), LAST_365(365), CUSTOM(0);
        final int days;

        Range(int days) {
            this.days = days;
        }
    }

    /** First instant of the period. */
    public Instant start() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /** First instant after the period. */
    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /**
     * Work out the period from what was asked, or say what is wrong with it.
     *
     * <p>Either {@code range} (with {@code from}/{@code to} only for CUSTOM) or
     * {@code financialYear}, never both and never neither: two answers to "which days?"
     * means one of them is being ignored, and a statement that quietly covers the wrong days
     * is the one mistake this file must not make.
     *
     * <p>{@code LAST_n} is n days ending today, today included. A financial year is 1 April to
     * 31 March; the current one runs to today, and one that has not started is refused.
     */
    public static StatementPeriod resolve(String range, String from, String to, String financialYear,
                                          LocalDate today) {
        boolean hasRange = range != null && !range.isBlank();
        boolean hasFy = financialYear != null && !financialYear.isBlank();
        if (hasRange == hasFy) {
            throw invalid(hasRange
                    ? "Choose either a range or a financial year, not both."
                    : "Choose a range or a financial year.");
        }
        if (hasFy) {
            if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
                throw invalid("from and to go with range=CUSTOM only.");
            }
            return financialYear(financialYear.trim(), today);
        }

        Range parsed;
        try {
            parsed = Range.valueOf(range.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid("range must be LAST_30, LAST_90, LAST_180, LAST_365 or CUSTOM.");
        }
        if (parsed != Range.CUSTOM) {
            if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
                throw invalid("from and to go with range=CUSTOM only.");
            }
            return new StatementPeriod(today.minusDays(parsed.days - 1L), today);
        }

        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            throw invalid("A custom range needs both from and to.");
        }
        LocalDate start = date("from", from);
        LocalDate end = date("to", to);
        if (start.isAfter(end)) {
            throw invalid("from must not be after to.");
        }
        if (end.isAfter(today)) {
            throw invalid("to can't be in the future.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw invalid("Choose a period of at most %d days.".formatted(MAX_DAYS));
        }
        return new StatementPeriod(start, end);
    }

    private static StatementPeriod financialYear(String text, LocalDate today) {
        // 2025-26: the second half must be exactly the year after the first, so "2025-27"
        // and "2025-25" are typos, not years.
        if (!text.matches("\\d{4}-\\d{2}")) {
            throw invalid("financialYear must look like 2025-26.");
        }
        int startYear = Integer.parseInt(text.substring(0, 4));
        int endShort = Integer.parseInt(text.substring(5));
        if (startYear < 2000 || endShort != (startYear + 1) % 100) {
            throw invalid("financialYear must look like 2025-26.");
        }
        var start = LocalDate.of(startYear, 4, 1);
        var end = LocalDate.of(startYear + 1, 3, 31);
        if (start.isAfter(today)) {
            throw invalid("That financial year hasn't started yet.");
        }
        return new StatementPeriod(start, end.isAfter(today) ? today : end);
    }

    private static LocalDate date(String name, String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeException ex) {
            throw invalid("%s must be a date like 2026-09-30.".formatted(name));
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message);
    }
}
