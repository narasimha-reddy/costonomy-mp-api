package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryProviderStats;
import com.costonomy.mp.delivery.repository.DeliveryProviderStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Computes and persists one day's reliability snapshot for every provider.
 *
 * <p><b>Why JDBC and not JPQL?</b> The aggregation joins four tables
 * ({@code delivery}, {@code delivery_event}, {@code delivery_quote},
 * {@code delivery_ledger}) and uses conditional aggregation. A JPA query for
 * this would be either unreadably long or silently wrong once a join condition
 * drifts from the schema. Plain SQL is the right tool here — it reads like the
 * question it is answering.
 *
 * <p>Idempotency: if a row already exists for {@code (provider_code,
 * window_date)} the insert is skipped. The job can therefore be re-run safely
 * after a failure without double-counting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryStatsAggregationService {

    private final JdbcTemplate jdbc;
    private final DeliveryProviderStatsRepository statsRepository;

    /**
     * Aggregate metrics for every active provider for {@code windowDate}.
     * Typically called with yesterday's date by the nightly job.
     *
     * <p>Each metric counts only events where <em>this provider</em> was the
     * active provider on the delivery at the time — a DRIVER_CANCELLED by
     * Pidge that handed off to Shadowfax counts against Pidge, not Shadowfax.
     *
     * @return number of provider rows written (0 when already present for all)
     */
    @Transactional
    public int aggregate(LocalDate windowDate) {
        log.info("Aggregating delivery provider stats for {}", windowDate);

        List<ProviderRow> rows = fetchRawStats(windowDate);
        if (rows.isEmpty()) {
            log.info("No delivery activity for {} — nothing to write", windowDate);
            return 0;
        }

        int written = 0;
        for (ProviderRow raw : rows) {
            boolean alreadyPresent = statsRepository
                    .findByProviderCodeAndWindowDate(raw.providerCode(), windowDate)
                    .isPresent();

            if (alreadyPresent) {
                log.debug("Stats already present for {} on {} — skipping", raw.providerCode(), windowDate);
                continue;
            }

            var stats = new DeliveryProviderStats();
            stats.setProviderCode(raw.providerCode());
            stats.setWindowDate(windowDate);
            stats.setTotalBookings(raw.totalBookings());
            stats.setDriverCancellations(raw.driverCancellations());
            stats.setPickupFailures(raw.pickupFailures());
            stats.setDeliveryFailures(raw.deliveryFailures());
            stats.setEtaOverruns(raw.etaOverruns());
            stats.setCompletedDeliveries(raw.completedDeliveries());
            stats.setAvgActualEtaMinutes(raw.avgActualEtaMinutes());
            stats.setAvgQuotedEtaMinutes(raw.avgQuotedEtaMinutes());
            stats.setAvgPriceDeviationInr(raw.avgPriceDeviationInr());

            statsRepository.save(stats);
            written++;

            log.info("Wrote stats for {} on {}: bookings={} cancellations={} pickupFails={} " +
                            "deliveryFails={} etaOverruns={} completed={}",
                    raw.providerCode(), windowDate,
                    raw.totalBookings(), raw.driverCancellations(), raw.pickupFailures(),
                    raw.deliveryFailures(), raw.etaOverruns(), raw.completedDeliveries());
        }

        return written;
    }

    // -----------------------------------------------------------------------
    // Raw SQL aggregation
    // -----------------------------------------------------------------------

    /**
     * Fetches per-provider metrics for {@code windowDate} using conditional
     * aggregation across four tables.
     *
     * <p>The query anchors on the DRIVER_ASSIGNED event to define "a booking":
     * a delivery only contributes to a provider's count on the day the driver
     * was assigned, not the day quoting happened. This aligns with what
     * operations cares about — how many drivers did a provider send out today.
     *
     * <p>ETA overrun: actual elapsed minutes from DRIVER_ASSIGNED to DELIVERED
     * exceeds the quoted ETA stored on the selected {@code delivery_quote} row.
     *
     * <p>Price deviation: actual amount in {@code delivery_ledger} minus quoted
     * amount. Positive means the provider charged more than quoted (surge).
     */
    private List<ProviderRow> fetchRawStats(LocalDate windowDate) {
        // language=SQL
        String sql = """
                SELECT
                    assigned.provider_code,

                    -- Total bookings: deliveries where a driver was assigned
                    -- by this provider on windowDate.
                    COUNT(DISTINCT assigned.delivery_id)                          AS total_bookings,

                    -- Driver cancelled: DRIVER_CANCELLED event applied on the
                    -- same delivery after DRIVER_ASSIGNED, from same provider.
                    COUNT(DISTINCT cancelled.delivery_id)                         AS driver_cancellations,

                    -- Pickup failed.
                    COUNT(DISTINCT pickup_fail.delivery_id)                       AS pickup_failures,

                    -- Delivery failed.
                    COUNT(DISTINCT delivery_fail.delivery_id)                     AS delivery_failures,

                    -- ETA overruns: delivered but late vs quoted ETA.
                    COUNT(DISTINCT eta_overrun.delivery_id)                       AS eta_overruns,

                    -- Deliveries reaching DELIVERED state on windowDate.
                    COUNT(DISTINCT delivered.delivery_id)                         AS completed_deliveries,

                    -- Average actual delivery time (minutes, DRIVER_ASSIGNED → DELIVERED).
                    AVG(
                        CASE WHEN delivered.delivery_id IS NOT NULL
                             THEN TIMESTAMPDIFF(MINUTE, assigned_ts.occurred_at, delivered_ts.occurred_at)
                        END
                    )                                                             AS avg_actual_eta_minutes,

                    -- Average quoted ETA for bookings on windowDate.
                    AVG(dq.eta_minutes)                                           AS avg_quoted_eta_minutes,

                    -- Average price deviation: ledger amount – quoted amount.
                    AVG(
                        CASE WHEN dl.amount IS NOT NULL AND dq.amount IS NOT NULL
                             THEN dl.amount - dq.amount
                        END
                    )                                                             AS avg_price_deviation_inr

                FROM (
                    -- Anchor: one row per delivery that had a driver assigned
                    -- by this provider on windowDate.
                    SELECT de.delivery_id, de.provider_code
                    FROM delivery_event de
                    WHERE de.status      = 'DRIVER_ASSIGNED'
                      AND de.disposition = 'APPLIED'
                      AND DATE(de.occurred_at) = ?
                ) assigned

                -- The precise timestamp of DRIVER_ASSIGNED (for elapsed-time calc).
                JOIN delivery_event assigned_ts
                     ON  assigned_ts.delivery_id  = assigned.delivery_id
                     AND assigned_ts.status        = 'DRIVER_ASSIGNED'
                     AND assigned_ts.disposition   = 'APPLIED'
                     AND DATE(assigned_ts.occurred_at) = ?

                -- Left-join the selected quote to get quoted ETA & quoted price.
                LEFT JOIN delivery_quote dq
                     ON  dq.delivery_id    = assigned.delivery_id
                     AND dq.provider_code  = assigned.provider_code
                     AND dq.selected       = TRUE

                -- Left-join ledger for actual cost.
                LEFT JOIN delivery_ledger dl
                     ON  dl.delivery_id   = assigned.delivery_id
                     AND dl.provider_code = assigned.provider_code

                -- DRIVER_CANCELLED on this delivery, same provider.
                LEFT JOIN (
                    SELECT DISTINCT de.delivery_id
                    FROM delivery_event de
                    WHERE de.status      = 'DRIVER_CANCELLED'
                      AND de.disposition = 'APPLIED'
                ) cancelled ON cancelled.delivery_id = assigned.delivery_id

                -- PICKUP_FAILED on this delivery.
                LEFT JOIN (
                    SELECT DISTINCT de.delivery_id
                    FROM delivery_event de
                    WHERE de.status      = 'PICKUP_FAILED'
                      AND de.disposition = 'APPLIED'
                ) pickup_fail ON pickup_fail.delivery_id = assigned.delivery_id

                -- DELIVERY_FAILED on this delivery.
                LEFT JOIN (
                    SELECT DISTINCT de.delivery_id
                    FROM delivery_event de
                    WHERE de.status      = 'DELIVERY_FAILED'
                      AND de.disposition = 'APPLIED'
                ) delivery_fail ON delivery_fail.delivery_id = assigned.delivery_id

                -- Delivered on windowDate.
                LEFT JOIN (
                    SELECT DISTINCT de.delivery_id
                    FROM delivery_event de
                    WHERE de.status      = 'DELIVERED'
                      AND de.disposition = 'APPLIED'
                      AND DATE(de.occurred_at) = ?
                ) delivered ON delivered.delivery_id = assigned.delivery_id

                -- Precise DELIVERED timestamp for elapsed-time calc.
                LEFT JOIN delivery_event delivered_ts
                     ON  delivered_ts.delivery_id  = assigned.delivery_id
                     AND delivered_ts.status        = 'DELIVERED'
                     AND delivered_ts.disposition   = 'APPLIED'

                -- ETA overrun: delivered AND actual time > quoted ETA.
                LEFT JOIN (
                    SELECT DISTINCT da.delivery_id
                    FROM delivery_event da
                    JOIN delivery_event dd
                         ON dd.delivery_id  = da.delivery_id
                         AND dd.status       = 'DELIVERED'
                         AND dd.disposition  = 'APPLIED'
                    JOIN delivery_quote dqo
                         ON dqo.delivery_id  = da.delivery_id
                         AND dqo.selected    = TRUE
                    WHERE da.status      = 'DRIVER_ASSIGNED'
                      AND da.disposition = 'APPLIED'
                      AND TIMESTAMPDIFF(MINUTE, da.occurred_at, dd.occurred_at) > dqo.eta_minutes
                ) eta_overrun ON eta_overrun.delivery_id = assigned.delivery_id

                GROUP BY assigned.provider_code
                ORDER BY assigned.provider_code
                """;

        return jdbc.query(sql,
                (rs, rowNum) -> new ProviderRow(
                        rs.getString("provider_code"),
                        rs.getInt("total_bookings"),
                        rs.getInt("driver_cancellations"),
                        rs.getInt("pickup_failures"),
                        rs.getInt("delivery_failures"),
                        rs.getInt("eta_overruns"),
                        rs.getInt("completed_deliveries"),
                        rs.getObject("avg_actual_eta_minutes") == null ? null
                                : (int) Math.round(rs.getDouble("avg_actual_eta_minutes")),
                        rs.getObject("avg_quoted_eta_minutes") == null ? null
                                : (int) Math.round(rs.getDouble("avg_quoted_eta_minutes")),
                        rs.getBigDecimal("avg_price_deviation_inr")
                ),
                windowDate.toString(), windowDate.toString(), windowDate.toString());
    }

    /** Raw projection from the aggregation query. */
    private record ProviderRow(
            String providerCode,
            int totalBookings,
            int driverCancellations,
            int pickupFailures,
            int deliveryFailures,
            int etaOverruns,
            int completedDeliveries,
            Integer avgActualEtaMinutes,
            Integer avgQuotedEtaMinutes,
            BigDecimal avgPriceDeviationInr) {
    }
}
