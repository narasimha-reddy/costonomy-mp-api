package com.costonomy.mp.discovery.domain;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Best Value weight set. Doc 07 §4.
 *
 * <p>Loaded from {@code app_config} and versioned there, so a recommendation
 * shown last month can be reconstructed with the weights that applied then.
 *
 * <p><b>Commission is not a component and must never become one.</b> Guardrail 9
 * and doc 07 §4 make it neither a positive nor a negative factor. A weight named
 * after it would be the quiet way to break that, so {@link #componentNames()} is
 * a closed set and a test asserts what is in it.
 */
public record RankingWeights(
        BigDecimal price,
        BigDecimal eta,
        BigDecimal availability,
        BigDecimal fillRate,
        BigDecimal onTime,
        BigDecimal rating,
        BigDecimal reliability) {

    /** The defaults the code was written against, used when config is unreachable. */
    public static RankingWeights defaults() {
        return new RankingWeights(
                new BigDecimal("0.40"),
                new BigDecimal("0.20"),
                new BigDecimal("0.15"),
                new BigDecimal("0.10"),
                new BigDecimal("0.08"),
                new BigDecimal("0.04"),
                new BigDecimal("0.03"));
    }

    /**
     * Components in a fixed order.
     *
     * <p>Every ranking input, and nothing else. If a component is added here it
     * must also be something a restaurant would accept as a reason for a
     * recommendation.
     */
    public Map<String, BigDecimal> componentNames() {
        Map<String, BigDecimal> components = new LinkedHashMap<>();
        components.put("price", price);
        components.put("eta", eta);
        components.put("availability", availability);
        components.put("fillRate", fillRate);
        components.put("onTime", onTime);
        components.put("rating", rating);
        components.put("reliability", reliability);
        return components;
    }
}
