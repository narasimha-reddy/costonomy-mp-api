package com.costonomy.mp.discovery.service;

import com.costonomy.mp.discovery.domain.SupplierPerformance;

import java.util.List;
import java.util.Map;

/**
 * Supplies supplier performance signals to ranking.
 *
 * <p>A port, so ranking does not depend on how the numbers are produced. The
 * implementation today is {@link NoHistoryPerformanceProvider}: there are no
 * orders in the system yet, so there is no history, and it says so rather than
 * inventing figures.
 *
 * <p>Phase 8 (supplier acceptance) and Phase 12 (receiving and ratings) produce
 * the data these signals are computed from. At that point an order-derived
 * implementation replaces the current one and nothing in the scorer changes —
 * which is the point of the port, and also the reason the scorer already handles
 * absent signals properly rather than acquiring that behaviour later.
 */
public interface SupplierPerformanceProvider {

    /**
     * Performance for each store.
     *
     * <p>Must return an entry for every requested id — {@link SupplierPerformance#unknown}
     * where there is no history. A missing key would force callers to invent a
     * default, which is the failure this whole design avoids.
     */
    Map<Long, SupplierPerformance> forStores(List<Long> supplierStoreIds);
}
