package com.costonomy.mp.discovery;

import com.costonomy.mp.discovery.domain.ExplanationCode;
import com.costonomy.mp.discovery.domain.RankingWeights;
import com.costonomy.mp.discovery.domain.SupplierPerformance;
import com.costonomy.mp.discovery.service.BestValueScorer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Best Value scoring. Doc 07 §14 names most of these scenarios directly.
 *
 * <p>The scorer is pure, so these are ordinary unit tests with no database. The
 * filtering half of the pipeline — unavailable, offline, unserviceable — is
 * covered against real data in {@code RecommendationIT}.
 */
class BestValueScorerTest {

    private final BestValueScorer scorer = new BestValueScorer();
    private final RankingWeights weights = RankingWeights.defaults();
    private final BestValueScorer.ExplanationThresholds thresholds =
            BestValueScorer.ExplanationThresholds.defaults();

    /** A supplier with no history — the state every supplier is in at launch. */
    private static SupplierPerformance noHistory(long storeId) {
        return SupplierPerformance.unknown(storeId);
    }

    /** A supplier with a real track record. */
    private static SupplierPerformance established(
            long storeId, int orders, String fillRate, String onTime, String rating) {
        return new SupplierPerformance(storeId, orders,
                Optional.of(new BigDecimal(fillRate)),
                Optional.of(new BigDecimal(onTime)),
                Optional.of(new BigDecimal("0.98")),
                Optional.of(new BigDecimal("0.01")),
                Optional.of(new BigDecimal(rating)),
                // A rating average is only meaningful beside the count it came
                // from; an established supplier is rated as often as they trade.
                orders);
    }

    private static BestValueScorer.Candidate candidate(
            long id, String total, Integer eta, SupplierPerformance performance) {
        return new BestValueScorer.Candidate(
                id, id, id, new BigDecimal(total), eta,
                eta == null ? null : BigDecimal.valueOf(5), null, BigDecimal.TEN, performance);
    }

    @Nested
    @DisplayName("ranking")
    class Ranking {

        @Test
        @DisplayName("with nothing to separate suppliers, the cheaper one wins")
        void cheapestWinsAllElseEqual() {
            var ranked = scorer.score(List.of(
                    candidate(1, "10000", 60, noHistory(1)),
                    candidate(2, "9000", 60, noHistory(2))), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(2);
        }

        @Test
        @DisplayName("the cheapest offer loses when reliability is materially worse")
        void cheapestLosesToMateriallyBetterReliability() {
            // Doc 07 §14's first named scenario, and the reason Best Value is not
            // just "sort by price". The cheap supplier delivers 60% of what it
            // accepts and is late a third of the time; a restaurant that runs out
            // of paneer mid-service has not saved ₹500.
            var cheapAndUnreliable = new BestValueScorer.Candidate(
                    1L, 1L, 1L, new BigDecimal("9000"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN,
                    established(1, 200, "0.60", "0.65", "3.0"));

            var dearerAndExcellent = new BestValueScorer.Candidate(
                    2L, 2L, 2L, new BigDecimal("10000"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN,
                    established(2, 200, "0.99", "0.97", "4.8"));

            var ranked = scorer.score(List.of(cheapAndUnreliable, dearerAndExcellent),
                    weights, thresholds);

            assertThat(ranked.get(0).offerId())
                    .describedAs("the reliable supplier should be recommended")
                    .isEqualTo(2);

            // And the loser is still honestly labelled as the cheapest.
            var cheapest = ranked.stream().filter(o -> o.offerId() == 1L).findFirst().orElseThrow();
            assertThat(cheapest.explanations()).contains(ExplanationCode.BEST_TOTAL_VALUE);
        }

        @Test
        @DisplayName("a small price gap does not outweigh a large reliability gap")
        void priceDoesNotDominateReliability() {
            var barelyCheaper = new BestValueScorer.Candidate(
                    1L, 1L, 1L, new BigDecimal("9900"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN, established(1, 200, "0.55", "0.50", "2.5"));
            var reliable = new BestValueScorer.Candidate(
                    2L, 2L, 2L, new BigDecimal("10000"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN, established(2, 200, "1.00", "0.99", "5.0"));

            assertThat(scorer.score(List.of(barelyCheaper, reliable), weights, thresholds)
                    .get(0).offerId()).isEqualTo(2);
        }

        @Test
        @DisplayName("equal offers order deterministically")
        void tiesAreStable() {
            // Doc 07 §14. A restaurant refreshing the screen must not see the list
            // shuffle, or the recommendation stops looking like a judgement.
            var a = candidate(7, "10000", 60, noHistory(7));
            var b = candidate(3, "10000", 60, noHistory(3));

            var first = scorer.score(List.of(a, b), weights, thresholds);
            var second = scorer.score(List.of(b, a), weights, thresholds);

            assertThat(first.stream().map(o -> o.offerId()).toList())
                    .isEqualTo(second.stream().map(o -> o.offerId()).toList())
                    .containsExactly(3L, 7L);
        }

        @Test
        @DisplayName("a faster supplier wins at the same price")
        void fasterWinsAtSamePrice() {
            var ranked = scorer.score(List.of(
                    candidate(1, "10000", 240, noHistory(1)),
                    candidate(2, "10000", 45, noHistory(2))), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(2);
        }

        @Test
        @DisplayName("an offer that covers the full quantity beats one that cannot")
        void fullCoverageWins() {
            var partial = new BestValueScorer.Candidate(
                    1L, 1L, 1L, new BigDecimal("9000"), 60, BigDecimal.valueOf(5),
                    new BigDecimal("3"), BigDecimal.TEN, noHistory(1));
            var full = new BestValueScorer.Candidate(
                    2L, 2L, 2L, new BigDecimal("9500"), 60, BigDecimal.valueOf(5),
                    new BigDecimal("20"), BigDecimal.TEN, noHistory(2));

            var ranked = scorer.score(List.of(partial, full), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(2);
            assertThat(ranked.get(0).explanations())
                    .contains(ExplanationCode.FULL_QUANTITY_AVAILABLE);
        }
    }

    @Nested
    @DisplayName("cold start")
    class ColdStart {

        @Test
        @DisplayName("a supplier with no history is neither penalised nor flattered")
        void noHistoryIsNotPenalised() {
            // Doc 07 §6: do not penalise new suppliers indefinitely. With no trust
            // signals to weigh, price and speed decide — so the cheaper new
            // supplier wins, exactly as it would in a market of equals.
            var ranked = scorer.score(List.of(
                    candidate(1, "10000", 60, noHistory(1)),
                    candidate(2, "9000", 60, noHistory(2))), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(2);
            // Absent signals are absent from the breakdown, not zero.
            assertThat(ranked.get(0).componentScores())
                    .containsKeys("price", "eta", "availability")
                    .doesNotContainKeys("fillRate", "onTime", "rating", "reliability");
        }

        @Test
        @DisplayName("missing signals redistribute their weight rather than capping the score")
        void weightIsRedistributed() {
            // The property that makes cold start work. A supplier scored on three
            // of seven components is still scored out of 1, so a perfect new
            // supplier can reach the top — if weight were not redistributed, its
            // maximum would be capped at the sum of the components it has data for
            // and it could never outrank an established supplier.
            var ranked = scorer.score(List.of(candidate(1, "9000", 45, noHistory(1))),
                    weights, thresholds);

            assertThat(ranked.get(0).score())
                    .describedAs("best on every component it has data for")
                    .isEqualByComparingTo("1.000000");
        }

        @Test
        @DisplayName("thin history is ignored rather than trusted")
        void thinHistoryIsIgnored() {
            // Three deliveries at 100% is noise, not a reliability record, and
            // ranking on noise is worse than ranking on price alone.
            var threeOrders = established(1, 3, "1.00", "1.00", "5.0");
            var ranked = scorer.score(List.of(candidate(1, "10000", 60, threeOrders)),
                    weights, thresholds);

            assertThat(ranked.get(0).componentScores())
                    .doesNotContainKeys("fillRate", "onTime", "rating");
            assertThat(ranked.get(0).explanations()).contains(ExplanationCode.NEW_SUPPLIER);
        }
    }

    @Nested
    @DisplayName("explanations")
    class Explanations {

        @Test
        @DisplayName("a trust explanation is never shown without the data behind it")
        void noUnsupportedExplanations() {
            // Doc 07 §5. This is the assertion that stops "Reliable supplier"
            // appearing on an offer from a supplier who has never delivered.
            var ranked = scorer.score(List.of(candidate(1, "9000", 45, noHistory(1))),
                    weights, thresholds);

            assertThat(ranked.get(0).explanations())
                    .doesNotContain(
                            ExplanationCode.HIGH_FILL_RATE,
                            ExplanationCode.RELIABLE_SUPPLIER,
                            ExplanationCode.LOWER_HISTORICAL_COST);
        }

        @Test
        @DisplayName("trust explanations appear once the data supports them")
        void supportedExplanationsAppear() {
            var excellent = established(1, 500, "0.99", "0.96", "4.9");
            var ranked = scorer.score(List.of(candidate(1, "9000", 45, excellent)),
                    weights, thresholds);

            assertThat(ranked.get(0).explanations())
                    .contains(ExplanationCode.HIGH_FILL_RATE, ExplanationCode.RELIABLE_SUPPLIER)
                    .doesNotContain(ExplanationCode.NEW_SUPPLIER);
        }

        @Test
        @DisplayName("a supplier just below the threshold gets no trust badge")
        void thresholdsAreEnforced() {
            // 94% fill is good and is not "high". The threshold is configurable
            // precisely so this line can be moved deliberately rather than drifting.
            var nearMiss = established(1, 500, "0.94", "0.89", "4.5");
            var ranked = scorer.score(List.of(candidate(1, "9000", 45, nearMiss)),
                    weights, thresholds);

            assertThat(ranked.get(0).explanations())
                    .doesNotContain(ExplanationCode.HIGH_FILL_RATE, ExplanationCode.RELIABLE_SUPPLIER);
        }

        @Test
        @DisplayName("BEST_TOTAL_VALUE marks the cheapest offer, not the winner")
        void bestValueIsAFactNotARank() {
            // The recommended offer is often not the cheapest. Claiming "Best
            // value" on it anyway would be the exact dishonesty doc 07 §5 forbids.
            var cheapButBad = new BestValueScorer.Candidate(
                    1L, 1L, 1L, new BigDecimal("9000"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN, established(1, 200, "0.50", "0.50", "2.0"));
            var dearButGood = new BestValueScorer.Candidate(
                    2L, 2L, 2L, new BigDecimal("11000"), 60, BigDecimal.valueOf(5),
                    null, BigDecimal.TEN, established(2, 200, "1.00", "1.00", "5.0"));

            var ranked = scorer.score(List.of(cheapButBad, dearButGood), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(2);
            assertThat(ranked.get(0).explanations())
                    .describedAs("the winner is not the cheapest and must not claim to be")
                    .doesNotContain(ExplanationCode.BEST_TOTAL_VALUE);
        }

        @Test
        @DisplayName("FULL_QUANTITY_AVAILABLE is silent when everyone can cover it")
        void noNoiseExplanations() {
            var ranked = scorer.score(List.of(
                    candidate(1, "9000", 60, noHistory(1)),
                    candidate(2, "9500", 60, noHistory(2))), weights, thresholds);

            assertThat(ranked).allSatisfy(offer ->
                    assertThat(offer.explanations())
                            .doesNotContain(ExplanationCode.FULL_QUANTITY_AVAILABLE));
        }
    }

    @Nested
    @DisplayName("missing data")
    class MissingData {

        @Test
        @DisplayName("an unknown ETA is absent, not slow")
        void unknownEtaIsNotPenalised() {
            // Usually a store whose address has not been geocoded. Scoring it as
            // the slowest would bury an excellent supplier for a data-entry gap.
            var noEta = candidate(1, "9000", null, noHistory(1));
            var slow = candidate(2, "9000", 600, noHistory(2));

            var ranked = scorer.score(List.of(noEta, slow), weights, thresholds);

            assertThat(ranked.get(0).offerId()).isEqualTo(1);
            assertThat(ranked.get(0).componentScores()).doesNotContainKey("eta");
        }

        @Test
        @DisplayName("an empty candidate set returns nothing rather than failing")
        void emptySetIsSafe() {
            assertThat(scorer.score(List.of(), weights, thresholds)).isEmpty();
        }

        @Test
        @DisplayName("identical values leave everyone unpenalised")
        void identicalValuesDoNotSeparate() {
            var ranked = scorer.score(List.of(
                    candidate(1, "9000", 60, noHistory(1)),
                    candidate(2, "9000", 60, noHistory(2))), weights, thresholds);

            assertThat(ranked.get(0).score()).isEqualByComparingTo(ranked.get(1).score());
        }
    }

    @Nested
    @DisplayName("guardrails")
    class Guardrails {

        @Test
        @DisplayName("commission is not a ranking component")
        void noCommissionComponent() {
            // Guardrail 9 and doc 07 §4. A weight named after commission would be
            // the quiet way to break this, so the component set is asserted.
            assertThat(RankingWeights.defaults().componentNames().keySet())
                    .containsExactly("price", "eta", "availability",
                            "fillRate", "onTime", "rating", "reliability");
        }

        @Test
        @DisplayName("score components never leak a commission-derived value")
        void noCommissionInBreakdown() {
            var ranked = scorer.score(List.of(candidate(1, "9000", 45, noHistory(1))),
                    weights, thresholds);

            assertThat(ranked.get(0).componentScores().keySet())
                    .allSatisfy(key -> assertThat(key.toLowerCase())
                            .doesNotContain("commission", "fee", "margin", "takerate"));
        }
    }
}
