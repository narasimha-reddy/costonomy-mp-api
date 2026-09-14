package com.costonomy.mp.procurement.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Mints human order numbers — {@code MP-260914-001284}. Doc 02 §6.
 *
 * <p>Uses a dedicated per-day counter rather than counting today's orders. A count
 * is a read-then-write race: two concurrent submissions read the same value, mint
 * the same number, and the unique index rejects one of them — turning a cosmetic
 * concern into a failed checkout.
 *
 * <p>The {@code INSERT … ON DUPLICATE KEY UPDATE} takes a row lock for the day, so
 * concurrent callers serialise on it and each gets a distinct value.
 *
 * <p>{@code REQUIRES_NEW} so the counter advances even if the surrounding
 * submission later rolls back. That deliberately leaves gaps in the sequence:
 * these are identifiers, not a count of orders, and a gap is far cheaper than two
 * orders sharing a number — which is what reusing the value would risk.
 */
@Component
@RequiredArgsConstructor
public class OrderNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        jdbc.update("""
                insert into order_number_sequence (sequence_date, next_value, updated_at)
                values (?, 2, now(6))
                on duplicate key update next_value = next_value + 1, updated_at = now(6)
                """, today);

        Long current = jdbc.queryForObject(
                "select next_value from order_number_sequence where sequence_date = ?",
                Long.class, today);

        // next_value points at the *next* number, so the one just claimed is one less.
        long claimed = (current == null ? 2L : current) - 1L;

        return "MP-%s-%06d".formatted(today.format(DATE_PART), claimed);
    }
}
