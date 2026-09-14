package com.costonomy.mp.discovery.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * An offer with its Best Value score and the reasons behind it. Doc 07 §8.
 *
 * @param componentScores each component's normalised [0,1] contribution, for
 *                        debugging and for operations to inspect why something
 *                        ranked where it did. Components with no data are absent
 *                        rather than zero.
 * @param explanations    only codes the calculated data supports (doc 07 §5)
 */
public record ScoredOffer(
        Long offerId,
        Long supplierSkuId,
        Long supplierStoreId,
        BigDecimal score,
        Map<String, BigDecimal> componentScores,
        List<ExplanationCode> explanations,
        /** Item value plus GST for the requested quantity. Delivery is added in Phase 11. */
        BigDecimal effectiveTotal,
        Integer etaMinutes,
        BigDecimal distanceKm,
        /** How much of the requested quantity this supplier can cover. */
        BigDecimal coverableQuantity,
        boolean coversFullQuantity) {
}
