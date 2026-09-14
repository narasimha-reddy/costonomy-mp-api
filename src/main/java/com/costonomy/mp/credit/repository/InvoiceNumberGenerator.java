package com.costonomy.mp.credit.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Mints invoice numbers — {@code INV-260915-000042}. Mirrors
 * {@code OrderNumberGenerator}, for the same reasons: a per-day counter rather
 * than a count of today's rows, because a count is a read-then-write race that
 * would fail a checkout over a cosmetic identifier.
 *
 * <p>{@code REQUIRES_NEW}, so a rolled-back utilization leaves a gap rather than
 * risking two invoices sharing a number. A finance team can live with a gap; two
 * invoices with one number is a reconciliation that never balances.
 */
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        jdbc.update("""
                insert into credit_invoice_sequence (sequence_date, next_value, updated_at)
                values (?, 2, now(6))
                on duplicate key update next_value = next_value + 1, updated_at = now(6)
                """, today);

        Long current = jdbc.queryForObject(
                "select next_value from credit_invoice_sequence where sequence_date = ?",
                Long.class, today);

        long claimed = (current == null ? 2L : current) - 1L;
        return "INV-%s-%06d".formatted(today.format(DATE_PART), claimed);
    }
}
