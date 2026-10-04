package com.costonomy.mp.procurement.subscription;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Writes {@code subscription_run} rows. No transaction of its own: it joins the caller's, which is the
 * generator's for a GENERATED row (committed atomically with the order) and
 * {@link SubscriptionRunStore}'s fresh one for everything else.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionRunWriter {

    private final JdbcTemplate jdbc;

    /** The existing outcome for a subscription and date, or null. */
    public String outcomeOf(Long subscriptionId, LocalDate date) {
        var rows = jdbc.queryForList(
                "select outcome from subscription_run where subscription_id = ? and delivery_date = ?",
                String.class, subscriptionId, java.sql.Date.valueOf(date));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Records an outcome. Never throws on a repeat and never downgrades GENERATED: a retry that finds the
     * order already made leaves the row as it was. {@code outcome} is assigned last because MySQL evaluates
     * the assignments left to right and the others test the old value.
     */
    public void upsert(Long subscriptionId, LocalDate date, String outcome, String reason,
                       Long supplierOrderId, BigDecimal amount) {
        jdbc.update("""
                insert into subscription_run
                       (subscription_id, delivery_date, outcome, reason, supplier_order_id, amount, attempts)
                values (?, ?, ?, ?, ?, ?, 1)
                on duplicate key update
                       attempts = if(outcome = 'GENERATED', attempts, attempts + 1),
                       reason = if(outcome = 'GENERATED', reason, values(reason)),
                       supplier_order_id = if(outcome = 'GENERATED', supplier_order_id, values(supplier_order_id)),
                       amount = if(outcome = 'GENERATED', amount, values(amount)),
                       outcome = if(outcome = 'GENERATED', outcome, values(outcome))
                """,
                subscriptionId, java.sql.Date.valueOf(date), outcome,
                reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)),
                supplierOrderId, amount);
    }
}
