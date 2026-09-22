package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryProviderMetrics;
import com.costonomy.mp.delivery.repository.DeliveryProviderMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes and persists 2-hour rolling performance and cost metrics for delivery providers.
 *
 * <p>Latency is measured as the duration between DRIVER_ASSIGNED and DELIVERED events (in milliseconds).
 * Cost is the sum of delivery_ledger charges for deliveries handled by the provider in that window.
 *
 * <p>Idempotency: if a row already exists for {@code (provider_code, window_start)}, insertion is skipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryMetricsAggregationService {

    private final JdbcTemplate jdbc;
    private final DeliveryProviderMetricsRepository metricsRepository;

    /**
     * Aggregates metrics for a 2-hour window starting at {@code windowStart}.
     *
     * @param windowStart start of 2-hour window (aligned to 2-hour mark)
     * @return number of provider metric records written
     */
    @Transactional
    public int aggregateWindow(Instant windowStart) {
        Instant windowEnd = windowStart.plus(2, ChronoUnit.HOURS);
        log.info("Aggregating delivery provider metrics between {} and {}", windowStart, windowEnd);

        List<String> providers = fetchActiveProviders(windowStart, windowEnd);
        if (providers.isEmpty()) {
            log.debug("No active providers found in window {} to {}", windowStart, windowEnd);
            return 0;
        }

        int written = 0;
        for (String providerCode : providers) {
            if (metricsRepository.findByProviderCodeAndWindowStart(providerCode, windowStart).isPresent()) {
                log.debug("Metrics already present for provider {} at window {} — skipping", providerCode, windowStart);
                continue;
            }

            List<Long> latencies = fetchLatenciesMs(providerCode, windowStart, windowEnd);
            BigDecimal totalCost = fetchTotalCostInr(providerCode, windowStart, windowEnd);
            long orderCount = fetchOrderCount(providerCode, windowStart, windowEnd);

            double avgLatency = latencies.isEmpty() ? 0.0 : latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
            double p95Latency = calculateP95(latencies);

            DeliveryProviderMetrics entity = new DeliveryProviderMetrics(
                    providerCode,
                    windowStart,
                    avgLatency,
                    p95Latency,
                    totalCost != null ? totalCost : BigDecimal.ZERO,
                    orderCount
            );

            metricsRepository.save(entity);
            written++;

            log.info("Persisted 2h metrics for provider {}: count={} avgLatencyMs={} p95LatencyMs={} totalCostInr={}",
                    providerCode, orderCount, avgLatency, p95Latency, totalCost);
        }

        return written;
    }

    private List<String> fetchActiveProviders(Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT DISTINCT de.provider_code
                FROM delivery_event de
                WHERE de.provider_code IS NOT NULL
                  AND de.disposition = 'APPLIED'
                  AND de.occurred_at >= ? AND de.occurred_at < ?
                """;
        return jdbc.queryForList(sql, String.class, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }

    private List<Long> fetchLatenciesMs(String providerCode, Instant windowStart, Instant windowEnd) {
        // Calculate difference in milliseconds between DRIVER_ASSIGNED and DELIVERED for deliveries completed in this window
        String sql = """
                SELECT TIMESTAMPDIFF(MICROSECOND, da.occurred_at, dd.occurred_at) / 1000 AS latency_ms
                FROM delivery_event dd
                JOIN delivery_event da
                  ON da.delivery_id = dd.delivery_id
                 AND da.status = 'DRIVER_ASSIGNED'
                 AND da.disposition = 'APPLIED'
                 AND da.provider_code = ?
                WHERE dd.status = 'DELIVERED'
                  AND dd.disposition = 'APPLIED'
                  AND dd.provider_code = ?
                  AND dd.occurred_at >= ? AND dd.occurred_at < ?
                """;

        return jdbc.query(sql,
                (rs, rowNum) -> rs.getLong("latency_ms"),
                providerCode, providerCode, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }

    private BigDecimal fetchTotalCostInr(String providerCode, Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT COALESCE(SUM(amount), 0)
                FROM delivery_ledger
                WHERE provider_code = ?
                  AND created_at >= ? AND created_at < ?
                """;
        return jdbc.queryForObject(sql, BigDecimal.class,
                providerCode, Timestamp.from(windowStart), Timestamp.from(windowEnd));
    }

    private long fetchOrderCount(String providerCode, Instant windowStart, Instant windowEnd) {
        String sql = """
                SELECT COUNT(DISTINCT delivery_id)
                FROM delivery_event
                WHERE provider_code = ?
                  AND status = 'DRIVER_ASSIGNED'
                  AND disposition = 'APPLIED'
                  AND occurred_at >= ? AND occurred_at < ?
                """;
        Long count = jdbc.queryForObject(sql, Long.class,
                providerCode, Timestamp.from(windowStart), Timestamp.from(windowEnd));
        return count != null ? count : 0L;
    }

    public static double calculateP95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index).doubleValue();
    }
}
