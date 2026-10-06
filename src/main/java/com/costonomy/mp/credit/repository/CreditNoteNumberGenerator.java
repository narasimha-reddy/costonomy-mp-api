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
 * Mints credit note numbers, {@code CLN-260915-000042}, the way {@link InvoiceNumberGenerator} mints invoice numbers:
 * a per-day counter in its own transaction, so a rolled-back note leaves a gap and never two notes with one number.
 */
@Component
@RequiredArgsConstructor
public class CreditNoteNumberGenerator {

    private static final DateTimeFormatter DATE_PART = DateTimeFormatter.ofPattern("yyMMdd");

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        jdbc.update("""
                insert into credit_invoice_note_sequence (sequence_date, next_value, updated_at)
                values (?, 2, now(6))
                on duplicate key update next_value = next_value + 1, updated_at = now(6)
                """, today);

        Long current = jdbc.queryForObject(
                "select next_value from credit_invoice_note_sequence where sequence_date = ?", Long.class, today);

        long claimed = (current == null ? 2L : current) - 1L;
        return "CLN-%s-%06d".formatted(today.format(DATE_PART), claimed);
    }
}
