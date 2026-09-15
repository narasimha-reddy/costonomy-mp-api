package com.costonomy.mp.settlement.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Mints settlement numbers — {@code STL-260915-000042}. The fourth of these, and
 * the same reasoning each time: a per-day counter rather than a count of today's
 * rows, because a count is a read-then-write race.
 */
@Component
@RequiredArgsConstructor
public class SettlementNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        jdbc.update("""
                insert into settlement_number_sequence (sequence_date, next_value, updated_at)
                values (?, 2, now(6))
                on duplicate key update next_value = next_value + 1, updated_at = now(6)
                """, today);

        Long current = jdbc.queryForObject(
                "select next_value from settlement_number_sequence where sequence_date = ?",
                Long.class, today);

        long claimed = (current == null ? 2L : current) - 1L;
        return "STL-%s-%06d".formatted(today.format(DATE_PART), claimed);
    }
}
