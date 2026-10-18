package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.delivery.domain.DeliverySelection.Candidate;
import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order in which to ask couriers for one delivery (D-197 addendum). Pure and static, like
 * {@link DeliverySelection}, so the rule can be tested without a database or a courier.
 *
 * <p>First attempt: cheapest that meets the ETA, as before, except that a courier whose breaker is open is skipped.
 * After a failure: the couriers priced within {@code costFactor} of the cheapest remaining are ranked by reliability,
 * the rest follow by price. The restaurant pays for reliability only up to that bound, and never above {@code maxAmount}.
 *
 * <p>This returns an order to try, not a winner, because booking is a loop: the next one is asked if this one refuses.
 */
public final class ReliabilityRanking {

    private ReliabilityRanking() {
    }

    /**
     * @param tried       couriers already asked for this delivery; never asked again
     * @param reliability by provider code; a courier missing from the map is treated as unknown and never skipped
     * @param requiredEta the delivery's deadline in minutes, or null; couriers meeting it come first
     * @param maxAmount   the most the restaurant will pay, or null for no cap
     */
    public static List<Candidate> order(List<Candidate> candidates, Set<String> tried,
                                        Map<String, ProviderReliability> reliability,
                                        Integer requiredEta, BigDecimal maxAmount, ReliabilitySettings settings) {

        var pool = candidates.stream()
                .filter(c -> c.amount() != null && c.etaMinutes() != null)
                .filter(c -> !tried.contains(c.providerCode()))
                .filter(c -> maxAmount == null || c.amount().compareTo(maxAmount) <= 0)
                .toList();
        if (pool.isEmpty()) {
            return List.of();
        }

        boolean afterFailure = !tried.isEmpty();
        if (!settings.isEnabled()) {
            return pool.stream().sorted(byPrice()).toList();
        }

        // A broken courier is skipped. If every courier is broken a late delivery beats none, so they are all kept and
        // ranked by how bad they are.
        var usable = pool.stream().filter(c -> stateOf(c, reliability) != BreakerState.OPEN).toList();
        if (usable.isEmpty()) {
            usable = pool;
        }

        BigDecimal cheapest = usable.stream().map(Candidate::amount).min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal ceiling = cheapest.multiply(BigDecimal.valueOf(settings.getCostFactor()));

        Comparator<Candidate> order = Comparator
                .comparing((Candidate c) -> meetsEta(c, requiredEta) ? 0 : 1)
                // A courier we are only probing goes last, so a failed probe costs the least.
                .thenComparing(c -> stateOf(c, reliability) == BreakerState.HALF_OPEN ? 1 : 0);
        if (afterFailure) {
            order = order
                    .thenComparing(c -> c.amount().compareTo(ceiling) <= 0 ? 0 : 1)
                    // Inside the bound the most reliable courier goes first; outside it, cheapest does.
                    .thenComparing(c -> c.amount().compareTo(ceiling) <= 0 ? -scoreOf(c, reliability) : 0.0);
        }
        return usable.stream().sorted(order.thenComparing(byPrice())).toList();
    }

    private static Comparator<Candidate> byPrice() {
        return Comparator.comparing(Candidate::amount)
                .thenComparing(Candidate::etaMinutes)
                .thenComparing(Candidate::priority);
    }

    private static boolean meetsEta(Candidate c, Integer requiredEta) {
        return requiredEta == null || c.etaMinutes() <= requiredEta;
    }

    private static BreakerState stateOf(Candidate c, Map<String, ProviderReliability> reliability) {
        var r = reliability.get(c.providerCode());
        return r == null ? BreakerState.CLOSED : r.breaker();
    }

    private static double scoreOf(Candidate c, Map<String, ProviderReliability> reliability) {
        var r = reliability.get(c.providerCode());
        return r == null ? 0.0 : r.score();
    }
}
