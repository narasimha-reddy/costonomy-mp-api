package com.costonomy.mp.wallet.invoice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The day from which a payment without a bill counts as "Bill pending" (D-116). Older payments would all say
 * pending the day the feature goes live, so they show nothing (a bill can still be added from their details page).
 *
 * <p>{@code costonomy.mp.invoices.tracking-start} ({@code INVOICE_TRACKING_START}) is an ISO date, {@code 2026-10-01},
 * starting at midnight in Asia/Kolkata. Blank means tracking is off: nothing is ever PENDING, while bills that exist
 * still show their status. A value that is not a date refuses to start the application rather than being ignored.
 */
@Component
public class BillTracking {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private volatile Instant start;

    public BillTracking(@Value("${costonomy.mp.invoices.tracking-start:}") String startDate) {
        this.start = parse(startDate);
    }

    /** The first instant that is tracked, or empty when tracking is off. */
    public Optional<Instant> start() {
        return Optional.ofNullable(start);
    }

    /** For tests: change the start (null turns tracking off). Not used by the application. */
    public void setStartForTests(LocalDate date) {
        this.start = date == null ? null : date.atStartOfDay(IST).toInstant();
    }

    static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).atStartOfDay(IST).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "costonomy.mp.invoices.tracking-start must be a date like 2026-10-01, or blank to turn it off");
        }
    }
}
