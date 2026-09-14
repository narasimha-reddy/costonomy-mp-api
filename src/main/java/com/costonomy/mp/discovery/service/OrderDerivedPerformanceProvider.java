package com.costonomy.mp.discovery.service;

import com.costonomy.mp.discovery.domain.SupplierPerformance;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Supplier performance computed from real order history. Doc 07 §4, §8.
 *
 * <p>Replaces {@code NoHistoryPerformanceProvider} now that orders exist. Nothing
 * in {@code BestValueScorer} changes — it already handles absent signals by
 * redistributing their weight (D-014), which is why this could be added without
 * touching ranking.
 *
 * <p><b>Only the signals that genuinely exist are returned.</b> Acceptance and
 * cancellation can be computed from supplier orders today. Fill rate needs
 * delivered quantities (Phase 12, receiving) and on-time needs delivery
 * timestamps (Phase 11); both stay {@link Optional#empty()} rather than being
 * approximated from what is to hand. Doc 07 §4 forbids fabricating metrics, and an
 * approximation of a reliability figure is a fabrication with a plausible face.
 *
 * <p>Computed on demand rather than materialised. At current volumes a grouped
 * count over an indexed column is cheap, and a materialised table would be one
 * more thing that can be stale when a restaurant is looking at a ranking. Revisit
 * when the query, not the theory, becomes slow.
 */
@Service
@RequiredArgsConstructor
public class OrderDerivedPerformanceProvider implements SupplierPerformanceProvider {

    private final JdbcTemplate jdbc;

    @Override
    public Map<Long, SupplierPerformance> forStores(List<Long> supplierStoreIds) {
        if (supplierStoreIds.isEmpty()) {
            return Map.of();
        }

        // Every requested store gets an entry, unknown where there is no history —
        // a missing key would force the caller to invent a default, which is the
        // failure this design exists to prevent.
        Map<Long, SupplierPerformance> performance = new HashMap<>();
        supplierStoreIds.forEach(id -> performance.put(id, SupplierPerformance.unknown(id)));

        String placeholders = String.join(",", Collections.nCopies(supplierStoreIds.size(), "?"));

        jdbc.query("""
                select supplier_store_id,
                       count(*)                                                      as answered,
                       sum(status in ('CONFIRMED','PARTIALLY_ACCEPTED'))             as accepted,
                       sum(status = 'CANCELLED')                                     as cancelled,
                       sum(status in ('CONFIRMED','PARTIALLY_ACCEPTED','PREPARING',
                                      'READY_FOR_PICKUP','OUT_FOR_DELIVERY',
                                      'DELIVERED','COMPLETED'))                      as committed
                  from supplier_order
                 where supplier_store_id in (%s)
                   -- Orders still awaiting an answer are not evidence of anything.
                   -- Counting them would make a supplier's acceptance rate fall
                   -- simply because an order arrived a second ago.
                   and status <> 'PENDING_ACCEPTANCE'
                   and status <> 'DRAFT'
                 group by supplier_store_id
                """.formatted(placeholders),
                rs -> {
                    long storeId = rs.getLong(1);
                    int answered = rs.getInt(2);
                    int accepted = rs.getInt(3);
                    int cancelled = rs.getInt(4);
                    int committed = rs.getInt(5);

                    performance.put(storeId, new SupplierPerformance(
                            storeId,
                            // What the trust threshold is measured against. Orders
                            // the supplier actually responded to — an ignored order
                            // is a data point about them, but not a completed one.
                            answered,
                            // Needs delivered quantities (Phase 12).
                            Optional.empty(),
                            // Needs delivery timestamps (Phase 11).
                            Optional.empty(),
                            ratio(accepted, answered),
                            // Cancellations as a share of what they committed to.
                            // Against all orders, a supplier who rejects often would
                            // look *more* reliable, because rejections would dilute
                            // the denominator.
                            ratio(cancelled, committed),
                            // Needs ratings (Phase 12).
                            Optional.empty()));
                },
                supplierStoreIds.toArray());

        return performance;
    }

    private static Optional<BigDecimal> ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            // No denominator is no signal — not a zero rate. Returning zero would
            // report a supplier with no cancellable orders as perfectly reliable.
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP));
    }
}
