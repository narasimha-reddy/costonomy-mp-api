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
 * <p><b>Only the signals that genuinely exist are returned.</b> All five now do:
 * acceptance and cancellation from supplier orders, fill rate from received
 * quantities, on-time from delivery timestamps against the promised arrival, and
 * rating from published ratings. Each is still computed from its own denominator
 * and each is still {@link Optional#empty()} where that denominator is zero —
 * doc 07 §4 forbids fabricating a metric, and a store with no deliveries has no
 * fill rate rather than a perfect one.
 *
 * <p>The three that were empty until Phase 13 became real the moment the data
 * existed, with no change to {@code BestValueScorer} — which is what D-014's
 * weight redistribution was for.
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

        var fillRates = fillRates(supplierStoreIds, placeholders);
        var onTimeRates = onTimeRates(supplierStoreIds, placeholders);
        var averageRatings = averageRatings(supplierStoreIds, placeholders);
        var ratingCounts = ratingCounts(supplierStoreIds, placeholders);

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
                            fillRates.getOrDefault(storeId, Optional.empty()),
                            onTimeRates.getOrDefault(storeId, Optional.empty()),
                            ratio(accepted, answered),
                            // Cancellations as a share of what they committed to.
                            // Against all orders, a supplier who rejects often would
                            // look *more* reliable, because rejections would dilute
                            // the denominator.
                            ratio(cancelled, committed),
                            averageRatings.getOrDefault(storeId, Optional.empty()),
                            ratingCounts.getOrDefault(storeId, 0)));
                },
                supplierStoreIds.toArray());

        return performance;
    }

    /**
     * Delivered ÷ accepted, across every received order.
     *
     * <p>Received quantity rather than accepted-minus-missing: damaged stock
     * arrived but is not usable, and counting it as filled would let a supplier
     * with a packing problem look indistinguishable from one without.
     *
     * <p>Only lines that were actually received count. A line with no
     * {@code fulfilled_quantity} is one nobody has checked in yet, and including
     * it as a zero would make a supplier's fill rate fall while their delivery is
     * still on the road.
     */
    private Map<Long, Optional<BigDecimal>> fillRates(List<Long> storeIds, String placeholders) {
        Map<Long, Optional<BigDecimal>> rates = new HashMap<>();
        jdbc.query("""
                select o.supplier_store_id,
                       sum(i.accepted_quantity)  as accepted,
                       sum(i.fulfilled_quantity) as fulfilled
                  from supplier_order_item i
                  join supplier_order o on o.id = i.supplier_order_id
                 where o.supplier_store_id in (%s)
                   and i.fulfilled_quantity is not null
                   and i.accepted_quantity is not null
                 group by o.supplier_store_id
                """.formatted(placeholders),
                rs -> {
                    rates.put(rs.getLong(1),
                            share(rs.getBigDecimal(3), rs.getBigDecimal(2)));
                },
                storeIds.toArray());
        return rates;
    }

    /**
     * Deliveries that arrived by the time the provider promised.
     *
     * <p>Measured against {@code estimated_arrival_at} — the courier's own
     * estimate at booking — because that is the number the restaurant was shown.
     * Measuring against a figure we computed ourselves would grade a supplier on a
     * promise nobody made to anybody.
     *
     * <p>Own-delivery consignments are excluded: doc 06 §2 says Costonomy claims
     * no operational responsibility there and measures no provider SLA, and a
     * supplier who delivers in their own van should not be scored on a courier
     * metric that does not apply to them.
     */
    private Map<Long, Optional<BigDecimal>> onTimeRates(List<Long> storeIds, String placeholders) {
        Map<Long, Optional<BigDecimal>> rates = new HashMap<>();
        jdbc.query("""
                select supplier_store_id,
                       count(*)                                        as measured,
                       sum(delivered_at <= estimated_arrival_at)       as on_time
                  from delivery
                 where supplier_store_id in (%s)
                   and mode = 'COSTONOMY'
                   and status = 'DELIVERED'
                   and delivered_at is not null
                   and estimated_arrival_at is not null
                 group by supplier_store_id
                """.formatted(placeholders),
                rs -> {
                    rates.put(rs.getLong(1), ratio(rs.getInt(3), rs.getInt(2)));
                },
                storeIds.toArray());
        return rates;
    }

    /**
     * Mean overall rating, from published ratings only.
     *
     * <p>A hidden rating leaves the average, which is what makes moderation mean
     * something — and a store nobody has rated has no rating rather than a
     * middling one.
     */
    private Map<Long, Optional<BigDecimal>> averageRatings(List<Long> storeIds,
                                                           String placeholders) {
        Map<Long, Optional<BigDecimal>> averages = new HashMap<>();
        jdbc.query("""
                select supplier_store_id, avg(overall_rating)
                  from rating
                 where supplier_store_id in (%s)
                   and moderation_status = 'PUBLISHED'
                 group by supplier_store_id
                """.formatted(placeholders),
                rs -> {
                    var average = rs.getBigDecimal(2);
                    averages.put(rs.getLong(1), average == null ? Optional.empty()
                            : Optional.of(average.setScale(2, RoundingMode.HALF_UP)));
                },
                storeIds.toArray());
        return averages;
    }

    /** How many published ratings each average was computed from. */
    private Map<Long, Integer> ratingCounts(List<Long> storeIds, String placeholders) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbc.query("""
                select supplier_store_id, count(*)
                  from rating
                 where supplier_store_id in (%s)
                   and moderation_status = 'PUBLISHED'
                 group by supplier_store_id
                """.formatted(placeholders),
                rs -> {
                    counts.put(rs.getLong(1), rs.getInt(2));
                },
                storeIds.toArray());
        return counts;
    }

    /** A ratio of two decimal quantities, empty when there is nothing to divide by. */
    private static Optional<BigDecimal> share(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(numerator.divide(denominator, 4, RoundingMode.HALF_UP)
                // A supplier who over-delivers has filled the order, not more than
                // filled it. Above 1.0 the number stops meaning "share filled".
                .min(BigDecimal.ONE));
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
