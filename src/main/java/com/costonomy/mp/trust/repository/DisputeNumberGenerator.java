package com.costonomy.mp.trust.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Mints dispute numbers — {@code DSP-260915-000042}. Mirrors
 * {@code OrderNumberGenerator} and {@code InvoiceNumberGenerator}: a per-day
 * counter rather than a count of today's rows, because a count is a read-then-write
 * race that would fail a submission over a cosmetic identifier.
 */
@Component
@RequiredArgsConstructor
public class DisputeNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        jdbc.update("""
                insert into dispute_number_sequence (sequence_date, next_value, updated_at)
                values (?, 2, now(6))
                on duplicate key update next_value = next_value + 1, updated_at = now(6)
                """, today);

        Long current = jdbc.queryForObject(
                "select next_value from dispute_number_sequence where sequence_date = ?",
                Long.class, today);

        long claimed = (current == null ? 2L : current) - 1L;
        return "DSP-%s-%06d".formatted(today.format(DATE_PART), claimed);
    }
}
