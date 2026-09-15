package com.costonomy.mp.delivery.domain;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which courier gets the job. Doc 06 §4.
 *
 * <p><b>Lowest cost among those meeting the required ETA and serviceability.</b>
 * That is the whole rule for now; doc 06 §4 says reliability scoring comes later,
 * and inventing it early would mean ranking couriers on a number we have not
 * measured — the same mistake doc 07 §4 forbids for suppliers.
 *
 * <p>Pure and static, so the rule can be tested without a database or a provider.
 * Selection is the part of delivery most likely to be quietly wrong: with one
 * candidate every strategy agrees, so the bug only appears once there are two.
 *
 * <p><b>Commission plays no part here either.</b> Guardrail 9 is about organic
 * product ranking, but the principle is the same — the restaurant pays this fee,
 * and choosing a dearer courier because it earns more would be charging them for
 * our margin.
 */
public final class DeliverySelection {

    private DeliverySelection() {
    }

    /**
     * A quote, reduced to what selection needs.
     *
     * @param priority tie-break only, from {@code delivery_provider.priority}
     */
    public record Candidate(
            String providerCode,
            BigDecimal amount,
            Integer etaMinutes,
            int priority) {
    }

    /**
     * Pick a courier.
     *
     * @param requiredEtaMinutes the deadline the order needs, or null for none
     * @return the winner, or empty if nobody qualified
     */
    public static Optional<Candidate> select(List<Candidate> candidates,
                                             Integer requiredEtaMinutes) {

        var qualifying = candidates.stream()
                .filter(candidate -> candidate.amount() != null && candidate.etaMinutes() != null)
                .filter(candidate -> meetsEta(candidate, requiredEtaMinutes))
                .toList();

        // Nobody within the deadline. Rather than fail the delivery, fall back to
        // everyone who quoted at all and take the fastest: the goods still need to
        // move, and a late delivery is better than none — but the fallback orders
        // by time rather than price, because lateness is now the problem.
        if (qualifying.isEmpty()) {
            return candidates.stream()
                    .filter(candidate -> candidate.amount() != null && candidate.etaMinutes() != null)
                    .min(Comparator.comparing(Candidate::etaMinutes)
                            .thenComparing(Candidate::amount)
                            .thenComparing(Candidate::priority));
        }

        return qualifying.stream()
                .min(Comparator.comparing(Candidate::amount)
                        // A dead heat on price goes to the faster courier, and then
                        // to configured priority, so selection is deterministic and
                        // a test can assert it rather than accept either answer.
                        .thenComparing(Candidate::etaMinutes)
                        .thenComparing(Candidate::priority));
    }

    private static boolean meetsEta(Candidate candidate, Integer requiredEtaMinutes) {
        return requiredEtaMinutes == null || candidate.etaMinutes() <= requiredEtaMinutes;
    }
}
