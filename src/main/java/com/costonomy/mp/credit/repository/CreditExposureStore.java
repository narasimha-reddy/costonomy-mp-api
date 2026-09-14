package com.costonomy.mp.credit.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The only place {@code reserved_amount} and {@code utilized_amount} are written.
 *
 * <p>Every method here is a <b>single conditional UPDATE that re-checks its own
 * precondition in the same statement</b>, and returns whether it applied. That is
 * what makes doc 10 §2's credit reservation race safe, and it is a stronger
 * guarantee than optimistic locking gives:
 *
 * <p>The obvious implementation — load the agreement, compute
 * {@code available}, compare, add to {@code reserved}, save — is a read-modify-write.
 * Two concurrent orders for 60% of the limit both read the same row, both find
 * 100% available, and both save. With {@code @Version} one of them then fails, so
 * the outcome is correct but the failure is reported as a lost write, which tells
 * the restaurant nothing (D-018). With the conditional UPDATE, InnoDB serialises
 * the two statements on the row and the second one's {@code WHERE} clause simply
 * does not match: it applies zero rows, and the caller can say exactly what
 * happened — there was not enough credit.
 *
 * <p>The database also carries a CHECK constraint for the same invariant. The
 * UPDATE is what makes the failure informative; the CHECK is what makes it
 * impossible, including for any code path written later that forgets this class.
 */
@Component
@RequiredArgsConstructor
public class CreditExposureStore {

    private final JdbcTemplate jdbc;

    /** Exposure as the database has it, right now. */
    public record Balances(BigDecimal reserved, BigDecimal utilized, BigDecimal available) {
    }

    /**
     * Read the balances straight from the row.
     *
     * <p>Deliberately not through the repository. These columns were just changed
     * by SQL the entity manager knows nothing about, so a JPA read inside the same
     * transaction returns the <b>stale</b> instance from the first-level cache —
     * and a ledger row built from it would snapshot balances that were never true.
     */
    public Balances read(Long agreementId) {
        return jdbc.queryForObject("""
                select reserved_amount, utilized_amount,
                       approved_limit - reserved_amount - utilized_amount
                  from credit_agreement where id = ?
                """,
                (rs, row) -> new Balances(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                agreementId);
    }

    /**
     * Hold {@code amount} against the limit.
     *
     * <p>The status check belongs in the WHERE clause too: an agreement suspended
     * between the caller's read and this write must not be drawn on, and the only
     * way to be sure is to make the suspension and the reservation contend for the
     * same row.
     *
     * @return true if the credit was held; false if there was not enough, or the
     *         agreement is no longer ACTIVE
     */
    public boolean reserve(Long agreementId, BigDecimal amount) {
        int applied = jdbc.update("""
                update credit_agreement
                   set reserved_amount = reserved_amount + ?,
                       version = version + 1,
                       updated_at = now(6)
                 where id = ?
                   and status = 'ACTIVE'
                   and approved_limit - reserved_amount - utilized_amount >= ?
                """, amount, agreementId, amount);
        return applied == 1;
    }

    /**
     * Convert a held amount into a drawn one.
     *
     * <p>{@code utilized} may be less than {@code reserved} — a partial acceptance
     * draws only what the supplier committed to and gives the rest back (doc 01
     * §19). Both movements are one statement so no observer ever sees the money
     * counted twice or not at all.
     *
     * <p>No limit re-check: utilizing moves an amount that is already inside the
     * limit from one column to another, and it never increases exposure.
     */
    public boolean utilize(Long agreementId, BigDecimal reserved, BigDecimal utilized) {
        int applied = jdbc.update("""
                update credit_agreement
                   set reserved_amount = reserved_amount - ?,
                       utilized_amount = utilized_amount + ?,
                       version = version + 1,
                       updated_at = now(6)
                 where id = ?
                   and reserved_amount >= ?
                """, reserved, utilized, agreementId, reserved);
        return applied == 1;
    }

    /** Give back a held amount that was never drawn — a rejection or a timeout. */
    public boolean release(Long agreementId, BigDecimal amount) {
        int applied = jdbc.update("""
                update credit_agreement
                   set reserved_amount = reserved_amount - ?,
                       version = version + 1,
                       updated_at = now(6)
                 where id = ?
                   and reserved_amount >= ?
                """, amount, agreementId, amount);
        return applied == 1;
    }

    /**
     * Reduce what is drawn, because the restaurant repaid it.
     *
     * <p>The guard is {@code utilized_amount >= ?} rather than a trust in the
     * caller's arithmetic: a repayment larger than the debt would otherwise drive
     * utilization negative and quietly inflate the available limit.
     */
    public boolean repay(Long agreementId, BigDecimal amount) {
        int applied = jdbc.update("""
                update credit_agreement
                   set utilized_amount = utilized_amount - ?,
                       version = version + 1,
                       updated_at = now(6)
                 where id = ?
                   and utilized_amount >= ?
                """, amount, agreementId, amount);
        return applied == 1;
    }
}
