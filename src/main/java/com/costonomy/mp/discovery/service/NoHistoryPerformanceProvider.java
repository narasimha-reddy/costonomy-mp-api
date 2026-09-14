package com.costonomy.mp.discovery.service;

import com.costonomy.mp.discovery.domain.SupplierPerformance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports no performance history, because there is none yet.
 *
 * <p>The system has no completed orders until Phase 8, so every supplier is
 * genuinely unrated. This says exactly that. Doc 07 §4 and Engineering PRD §10
 * forbid fabricating metrics, and a provider returning plausible-looking defaults
 * would be the most comfortable way to violate that — every supplier would rank
 * as reliable, and "Reliable supplier" would appear on screen supported by
 * nothing.
 *
 * <p>The effect on ranking is correct rather than degraded: with no trust signals
 * available, {@code BestValueScorer} redistributes their weight and ranks on
 * price, ETA and availability. That is the "deterministic baseline ranking" the
 * specs ask for.
 *
 * <p>Registered by {@code DiscoveryConfig} as a fallback bean, so the
 * order-derived implementation can be added in Phase 8 and take over without
 * touching this class.
 */
public class NoHistoryPerformanceProvider implements SupplierPerformanceProvider {

    @Override
    public Map<Long, SupplierPerformance> forStores(List<Long> supplierStoreIds) {
        Map<Long, SupplierPerformance> performance = new HashMap<>();
        supplierStoreIds.forEach(id -> performance.put(id, SupplierPerformance.unknown(id)));
        return performance;
    }
}
