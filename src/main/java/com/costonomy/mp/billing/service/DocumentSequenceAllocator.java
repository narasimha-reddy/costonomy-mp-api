package com.costonomy.mp.billing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The next number in a supplier's series for a financial year and document type.
 *
 * <p><b>Joins the caller's transaction (MANDATORY), on purpose.</b> A number taken in a transaction of its own
 * would stay used when the insert that follows loses a race and rolls back, leaving a gap in a series that has to
 * be gap-free. Taken inside the inserting transaction, a rolled-back insert rolls the counter back with it. The
 * row lock also makes one supplier's concurrent allocations take turns.
 */
@Component
@RequiredArgsConstructor
public class DocumentSequenceAllocator {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String scopeKey, int fiscalYear, String docType) {
        jdbc.update("""
                insert ignore into document_sequence (scope_key, fiscal_year, doc_type, next_value)
                values (?, ?, ?, 1)
                """, scopeKey, fiscalYear, docType);
        Long current = jdbc.queryForObject("""
                select next_value from document_sequence
                 where scope_key = ? and fiscal_year = ? and doc_type = ? for update
                """, Long.class, scopeKey, fiscalYear, docType);
        jdbc.update("""
                update document_sequence set next_value = next_value + 1, version = version + 1
                 where scope_key = ? and fiscal_year = ? and doc_type = ?
                """, scopeKey, fiscalYear, docType);
        return current;
    }
}
