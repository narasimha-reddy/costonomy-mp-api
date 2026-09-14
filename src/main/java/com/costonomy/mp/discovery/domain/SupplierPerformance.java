package com.costonomy.mp.discovery.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * What a supplier store's order history says about it. Doc 07 §4, §8.
 *
 * <p>Every field is {@link Optional} on purpose. Doc 07 §4 and Engineering PRD
 * §10 both say the same thing: <em>"If historical data is insufficient, use
 * deterministic baseline ranking and never fabricate metrics."</em>
 *
 * <p>Absent is not zero and not one. A supplier with no completed orders has no
 * fill rate — treating that as 0% would make every new supplier unrankable, and
 * treating it as 100% would hand them unearned trust ahead of a supplier that
 * earned it. {@code BestValueScorer} redistributes the weight of an absent signal
 * instead of substituting a value, and {@code RecommendationExplainer} will not
 * emit an explanation a missing signal cannot support (doc 07 §5).
 *
 * @param completedOrders how many completed orders this is computed from. The
 *                        scorer ignores signals below the configured minimum,
 *                        because three deliveries is not a reliability record.
 */
public record SupplierPerformance(
        Long supplierStoreId,
        int completedOrders,
        /** Delivered ÷ accepted quantity. */
        Optional<BigDecimal> fillRate,
        /** Deliveries arriving by the promised time ÷ total. */
        Optional<BigDecimal> onTimeRate,
        /** Orders accepted ÷ orders received. */
        Optional<BigDecimal> acceptanceRate,
        /** Orders cancelled by the supplier after acceptance ÷ accepted. */
        Optional<BigDecimal> cancellationRate,
        /** Mean overall rating, 1–5. */
        Optional<BigDecimal> averageRating) {

    /** A store we know nothing about. Every signal absent. */
    public static SupplierPerformance unknown(Long supplierStoreId) {
        return new SupplierPerformance(supplierStoreId, 0,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * Whether there is enough history for these signals to mean anything.
     *
     * <p>Doc 07 §6's cold-start question. Below the threshold the signals are
     * ignored entirely rather than weighted down — a 100% fill rate over two
     * orders is noise, and ranking on noise is worse than ranking on price alone.
     */
    public boolean hasMeaningfulHistory(int minimumOrders) {
        return completedOrders >= minimumOrders;
    }
}
