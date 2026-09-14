package com.costonomy.mp.discovery.service;

import com.costonomy.mp.discovery.domain.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * Best Value scoring. Doc 07 §4.
 *
 * <p>Each component is normalised to [0,1] where 1 is best, then combined by the
 * configured weights. Normalising <em>within the candidate set</em> rather than
 * against absolute thresholds is what makes "best value" mean best among what is
 * actually available: ₹410 for paneer is neither good nor bad in isolation, only
 * relative to the other offers on screen.
 *
 * <p><b>The normalisation is ratio-to-best, not min-max.</b> An offer scores
 * {@code cheapest ÷ itsPrice} — 10% dearer scores 0.9, twice the price scores 0.5.
 *
 * <p>Min-max ({@code 1 - (v-min)/(max-min)}) is the obvious choice and is wrong
 * here. It stretches whatever spread exists to fill [0,1], so with two candidates
 * the cheaper always scores 1 and the dearer always scores 0 — whether the gap is
 * ₹1 or ₹10,000. Price then dominates every comparison and a supplier ₹1 cheaper
 * outranks one that is materially more reliable. That is exactly the outcome
 * doc 07 §14's first scenario says must not happen, and it is how a "Best Value"
 * ranking quietly degenerates into a price sort.
 *
 * <p>Ratio-to-best keeps differences proportionate to their real size, so a 1%
 * price premium costs 0.01 of the price component and a genuinely better supplier
 * can earn it back.
 *
 * <p><b>The part that matters most: an absent signal redistributes its weight.</b>
 *
 * <p>A supplier with no completed orders has no fill rate. The two easy things to
 * do with that are both wrong. Substituting 0 marks every new supplier as having
 * failed every delivery they never made. Substituting 1 hands them the same
 * standing as a supplier that earned it over two hundred orders — and then
 * "Reliable supplier" appears on screen supported by nothing, which doc 07 §5
 * forbids outright.
 *
 * <p>So an absent component is dropped and its weight shared among the components
 * that do have data. A supplier with no history is ranked on price, ETA and
 * availability — neither penalised nor flattered. That is doc 07 §6's cold-start
 * policy, and Engineering PRD §10's "deterministic baseline ranking", falling out
 * of one rule rather than being a special case bolted on.
 *
 * <p>The scorer is pure: same inputs, same output, no clock and no database. It
 * is directly unit-tested against the scenarios doc 07 §14 lists, including the
 * one that matters — the cheapest offer not winning when reliability is
 * materially worse.
 */
@Component
@Slf4j
public class BestValueScorer {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** One candidate, with everything scoring needs and nothing it does not. */
    public record Candidate(
            Long offerId,
            Long supplierSkuId,
            Long supplierStoreId,
            /** Item value + GST for the requested quantity. */
            BigDecimal effectiveTotal,
            /** Null when distance is unknown — ETA is then treated as absent, not as slow. */
            Integer etaMinutes,
            BigDecimal distanceKm,
            BigDecimal coverableQuantity,
            BigDecimal requestedQuantity,
            SupplierPerformance performance) {

        public boolean coversFullQuantity() {
            return coverableQuantity == null
                    || requestedQuantity == null
                    || coverableQuantity.compareTo(requestedQuantity) >= 0;
        }
    }

    public List<ScoredOffer> score(List<Candidate> candidates, RankingWeights weights,
                                   ExplanationThresholds thresholds) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        var priceScale = Scale.of(candidates, BestValueScorer.Candidate::effectiveTotal);
        var etaScale = Scale.of(candidates, c -> c.etaMinutes() == null
                ? null : BigDecimal.valueOf(c.etaMinutes()));

        List<ScoredOffer> scored = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            scored.add(scoreOne(candidate, candidates, weights, thresholds, priceScale, etaScale));
        }

        // Highest score first. Ties break on effective total, then on offer id —
        // doc 07 §14 requires equal offers to order deterministically, so a
        // restaurant refreshing the screen does not see them shuffle.
        scored.sort(Comparator
                .comparing(ScoredOffer::score).reversed()
                .thenComparing(ScoredOffer::effectiveTotal,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ScoredOffer::offerId));

        return scored;
    }

    private ScoredOffer scoreOne(Candidate candidate, List<Candidate> all, RankingWeights weights,
                                 ExplanationThresholds thresholds, Scale priceScale, Scale etaScale) {

        Map<String, BigDecimal> components = new LinkedHashMap<>();
        Map<String, BigDecimal> applicableWeights = new LinkedHashMap<>();

        // ── Always available ─────────────────────────────────────────────
        // Lower is better, so the score is cheapest ÷ this price.
        components.put("price", priceScale.ratioToBest(candidate.effectiveTotal()));
        applicableWeights.put("price", weights.price());

        // ETA is absent, not slow, when we could not compute it — usually because
        // the outlet or store has no coordinates. Scoring it as worst would push
        // an otherwise excellent supplier down for a data-entry gap.
        if (candidate.etaMinutes() != null) {
            components.put("eta",
                    etaScale.ratioToBest(BigDecimal.valueOf(candidate.etaMinutes())));
            applicableWeights.put("eta", weights.eta());
        }

        components.put("availability", candidate.coversFullQuantity() ? ONE : coverage(candidate));
        applicableWeights.put("availability", weights.availability());

        // ── Performance, only where it exists and means something ────────
        var performance = candidate.performance();
        boolean trusted = performance != null
                && performance.hasMeaningfulHistory(thresholds.minOrdersForTrust());

        if (trusted) {
            performance.fillRate().ifPresent(value -> {
                components.put("fillRate", clamp(value));
                applicableWeights.put("fillRate", weights.fillRate());
            });
            performance.onTimeRate().ifPresent(value -> {
                components.put("onTime", clamp(value));
                applicableWeights.put("onTime", weights.onTime());
            });
            performance.averageRating().ifPresent(value -> {
                // 1–5 onto [0,1].
                components.put("rating",
                        clamp(value.subtract(ONE).divide(new BigDecimal("4"), MC)));
                applicableWeights.put("rating", weights.rating());
            });
            // Reliability combines what the supplier does wrong: rejecting late and
            // cancelling after accepting. Both are inverted — less is better.
            Optional<BigDecimal> reliability = reliabilityOf(performance);
            reliability.ifPresent(value -> {
                components.put("reliability", clamp(value));
                applicableWeights.put("reliability", weights.reliability());
            });
        }

        BigDecimal score = weightedAverage(components, applicableWeights);

        return new ScoredOffer(
                candidate.offerId(), candidate.supplierSkuId(), candidate.supplierStoreId(),
                score, Map.copyOf(components),
                explain(candidate, all, trusted, thresholds),
                candidate.effectiveTotal(), candidate.etaMinutes(), candidate.distanceKm(),
                candidate.coverableQuantity(), candidate.coversFullQuantity());
    }

    /**
     * Weighted average over the components that have data.
     *
     * <p>Dividing by the sum of the <em>applicable</em> weights is the
     * redistribution: a candidate scored on three of seven components is still
     * scored out of 1, so it is comparable with one scored on all seven. Without
     * this, missing data would silently cap a supplier's maximum possible score.
     */
    private BigDecimal weightedAverage(Map<String, BigDecimal> components,
                                       Map<String, BigDecimal> weights) {
        BigDecimal weighted = ZERO;
        BigDecimal totalWeight = ZERO;

        for (var entry : components.entrySet()) {
            BigDecimal weight = weights.get(entry.getKey());
            if (weight == null || weight.signum() <= 0) {
                continue;
            }
            weighted = weighted.add(entry.getValue().multiply(weight, MC), MC);
            totalWeight = totalWeight.add(weight, MC);
        }

        if (totalWeight.signum() <= 0) {
            // Every weight configured to zero. Nothing to rank on, so everything
            // ties and the deterministic tie-break decides.
            return ZERO;
        }
        return weighted.divide(totalWeight, MC).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Explanation codes the data actually supports. Doc 07 §5.
     *
     * <p>Note what is checked: being cheapest is a fact about this candidate set,
     * so {@code BEST_TOTAL_VALUE} is computed rather than assumed from rank.
     * The winning offer is often not the cheapest, and claiming "Best value" on an
     * offer that is neither cheapest nor measurably better would be the exact
     * dishonesty doc 07 §5 is written against.
     */
    private List<ExplanationCode> explain(Candidate candidate, List<Candidate> all,
                                          boolean trusted, ExplanationThresholds thresholds) {
        List<ExplanationCode> codes = new ArrayList<>();

        boolean cheapest = all.stream()
                .map(Candidate::effectiveTotal)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(min -> candidate.effectiveTotal() != null
                        && candidate.effectiveTotal().compareTo(min) == 0)
                .orElse(false);
        if (cheapest) {
            codes.add(ExplanationCode.BEST_TOTAL_VALUE);
        }

        if (candidate.etaMinutes() != null) {
            boolean fastest = all.stream()
                    .map(Candidate::etaMinutes)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .map(min -> candidate.etaMinutes().equals(min))
                    .orElse(false);
            if (fastest) {
                codes.add(ExplanationCode.FASTEST_AVAILABLE);
            }
        }

        // Only worth saying when others cannot — otherwise it is noise on every card.
        if (candidate.coversFullQuantity()
                && all.stream().anyMatch(other -> !other.coversFullQuantity())) {
            codes.add(ExplanationCode.FULL_QUANTITY_AVAILABLE);
        }

        if (trusted) {
            var performance = candidate.performance();
            performance.fillRate()
                    .filter(value -> value.compareTo(thresholds.fillRateThreshold()) >= 0)
                    .ifPresent(value -> codes.add(ExplanationCode.HIGH_FILL_RATE));
            performance.onTimeRate()
                    .filter(value -> value.compareTo(thresholds.onTimeThreshold()) >= 0)
                    .ifPresent(value -> codes.add(ExplanationCode.RELIABLE_SUPPLIER));
        } else {
            // Doc 07 §6: a new supplier is not penalised, but the restaurant is
            // told there is no track record rather than being shown an offer that
            // looks identical to an established supplier's.
            codes.add(ExplanationCode.NEW_SUPPLIER);
        }

        return List.copyOf(codes);
    }

    private static Optional<BigDecimal> reliabilityOf(SupplierPerformance performance) {
        var acceptance = performance.acceptanceRate();
        var cancellation = performance.cancellationRate();
        if (acceptance.isEmpty() && cancellation.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal accepted = acceptance.orElse(ONE);
        BigDecimal cancelled = cancellation.orElse(ZERO);
        return Optional.of(clamp(accepted.multiply(ONE.subtract(cancelled), MC)));
    }

    private static BigDecimal coverage(Candidate candidate) {
        if (candidate.coverableQuantity() == null || candidate.requestedQuantity() == null
                || candidate.requestedQuantity().signum() <= 0) {
            return ONE;
        }
        return clamp(candidate.coverableQuantity().divide(candidate.requestedQuantity(), MC));
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        if (value.compareTo(ZERO) < 0) {
            return ZERO;
        }
        return value.compareTo(ONE) > 0 ? ONE : value;
    }

    /**
     * The best (lowest) value of one dimension across the candidate set.
     *
     * <p>Only the minimum is needed: scores are ratios to it, not positions within
     * a range. See the class javadoc for why min-max was rejected.
     */
    private record Scale(BigDecimal best) {

        static Scale of(List<Candidate> candidates,
                        java.util.function.Function<Candidate, BigDecimal> extractor) {
            BigDecimal best = null;
            for (Candidate candidate : candidates) {
                BigDecimal value = extractor.apply(candidate);
                if (value == null) {
                    continue;
                }
                best = best == null || value.compareTo(best) < 0 ? value : best;
            }
            return new Scale(best);
        }

        /**
         * {@code best ÷ value}, for dimensions where lower is better.
         *
         * <p>1 for the best candidate, 0.9 for one 11% worse, 0.5 for one twice as
         * costly. Every candidate scores 1 when they are all equal, so an
         * undifferentiated dimension neither rewards nor penalises anyone.
         */
        BigDecimal ratioToBest(BigDecimal value) {
            if (value == null || best == null) {
                return ONE;
            }
            if (best.signum() <= 0) {
                // A zero or negative best makes a ratio meaningless. Degenerate —
                // a free offer, or a store at zero distance with no prep time — so
                // fall back to an exact-match comparison rather than dividing.
                return value.compareTo(best) == 0 ? ONE : ZERO;
            }
            if (value.signum() <= 0) {
                return ONE;
            }
            return clamp(best.divide(value, MC));
        }
    }

    /** Thresholds an explanation must clear before it may be shown. */
    public record ExplanationThresholds(
            BigDecimal fillRateThreshold,
            BigDecimal onTimeThreshold,
            int minOrdersForTrust) {

        public static ExplanationThresholds defaults() {
            return new ExplanationThresholds(
                    new BigDecimal("0.95"), new BigDecimal("0.90"), 20);
        }
    }
}
