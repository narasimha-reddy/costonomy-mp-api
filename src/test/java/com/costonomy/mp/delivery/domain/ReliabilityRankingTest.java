package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.delivery.domain.DeliverySelection.Candidate;
import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The order couriers are asked in: cheapest first, then most reliable after a failure, with broken ones skipped. */
class ReliabilityRankingTest {

    private final ReliabilitySettings settings = new ReliabilitySettings();

    private static Candidate quote(String code, String amount, int eta, int priority) {
        return new Candidate(code, new BigDecimal(amount), eta, priority);
    }

    private static ProviderReliability rel(String code, double score, BreakerState state) {
        return new ProviderReliability(code, score, state, 1.0, 10);
    }

    private List<String> order(List<Candidate> candidates, Set<String> tried, Map<String, ProviderReliability> r,
                               Integer eta, BigDecimal max) {
        return ReliabilityRanking.order(candidates, tried, r, eta, max, settings).stream()
                .map(Candidate::providerCode).toList();
    }

    private final List<Candidate> three = List.of(
            quote("CHEAP", "40", 30, 10), quote("MID", "50", 30, 20), quote("DEAR", "90", 30, 30));

    @Test
    @DisplayName("first attempt: cheapest first, with reliability not used")
    void firstAttemptIsCheapest() {
        var r = Map.of("CHEAP", rel("CHEAP", 0.5, BreakerState.CLOSED), "MID", rel("MID", 0.99, BreakerState.CLOSED));

        assertThat(order(three, Set.of(), r, null, null)).containsExactly("CHEAP", "MID", "DEAR");
    }

    @Test
    @DisplayName("after a failure, the most reliable courier within the cost bound goes first")
    void afterFailureReliabilityFirst() {
        // CHEAP failed already. MID (50) and DEAR (90): the bound is 1.5 x the cheapest remaining = 75, so MID is in
        // and DEAR is out, whatever DEAR's score.
        var r = Map.of("MID", rel("MID", 0.70, BreakerState.CLOSED), "DEAR", rel("DEAR", 0.99, BreakerState.CLOSED));

        assertThat(order(three, Set.of("CHEAP"), r, null, null)).containsExactly("MID", "DEAR");
    }

    @Test
    @DisplayName("inside the bound reliability beats price; outside it price decides")
    void boundIsRespected() {
        var cands = List.of(quote("A", "40", 30, 1), quote("B", "55", 30, 2), quote("C", "59", 30, 3),
                quote("D", "61", 30, 4), quote("E", "200", 30, 5));
        var r = Map.of(
                "B", rel("B", 0.60, BreakerState.CLOSED), "C", rel("C", 0.95, BreakerState.CLOSED),
                "D", rel("D", 0.99, BreakerState.CLOSED), "E", rel("E", 0.99, BreakerState.CLOSED));

        // A failed; cheapest remaining is B at 55 -> bound 82.5. B, C, D inside (by score: C 0.95, D 0.99?...).
        assertThat(order(cands, Set.of("A"), r, null, null)).containsExactly("D", "C", "B", "E");
    }

    @Test
    @DisplayName("a courier whose breaker is open is skipped, even if it is the cheapest")
    void openIsSkipped() {
        var r = Map.of("CHEAP", rel("CHEAP", 0.3, BreakerState.OPEN));

        assertThat(order(three, Set.of(), r, null, null)).containsExactly("MID", "DEAR");
    }

    @Test
    @DisplayName("if every courier is broken they are all still tried, so a late delivery beats none")
    void allOpenStillTried() {
        var r = Map.of("CHEAP", rel("CHEAP", 0.3, BreakerState.OPEN), "MID", rel("MID", 0.4, BreakerState.OPEN),
                "DEAR", rel("DEAR", 0.2, BreakerState.OPEN));

        assertThat(order(three, Set.of(), r, null, null)).containsExactly("CHEAP", "MID", "DEAR");
        assertThat(order(three, Set.of("CHEAP"), r, null, null)).containsExactly("MID", "DEAR");
    }

    @Test
    @DisplayName("a courier being probed is asked last, so a failed probe costs the least")
    void probeGoesLast() {
        var r = Map.of("CHEAP", rel("CHEAP", 0.4, BreakerState.HALF_OPEN));

        assertThat(order(three, Set.of(), r, null, null)).containsExactly("MID", "DEAR", "CHEAP");
    }

    @Test
    @DisplayName("couriers already tried are never asked again")
    void triedAreExcluded() {
        assertThat(order(three, Set.of("CHEAP", "MID"), Map.of(), null, null)).containsExactly("DEAR");
        assertThat(order(three, Set.of("CHEAP", "MID", "DEAR"), Map.of(), null, null)).isEmpty();
    }

    @Test
    @DisplayName("nothing dearer than the fee the restaurant agreed to is ever asked")
    void hardCapIsRespected() {
        assertThat(order(three, Set.of(), Map.of(), null, new BigDecimal("50"))).containsExactly("CHEAP", "MID");
        assertThat(order(three, Set.of(), Map.of(), null, new BigDecimal("10"))).isEmpty();
    }

    @Test
    @DisplayName("couriers that meet the deadline come before those that do not")
    void etaTierFirst() {
        var cands = List.of(quote("SLOW_CHEAP", "30", 60, 1), quote("FAST_DEAR", "80", 20, 2));

        assertThat(order(cands, Set.of(), Map.of(), 30, null)).containsExactly("FAST_DEAR", "SLOW_CHEAP");
        assertThat(order(cands, Set.of(), Map.of(), null, null)).containsExactly("SLOW_CHEAP", "FAST_DEAR");
    }

    @Test
    @DisplayName("a courier we have no reliability for is treated as unknown: tried, not skipped")
    void unknownCourierIsKept() {
        assertThat(order(three, Set.of("CHEAP"), Map.of(), null, null)).containsExactly("MID", "DEAR");
    }

    @Test
    @DisplayName("quotes with no price or no ETA are not candidates")
    void incompleteQuotesAreDropped() {
        var cands = List.of(new Candidate("NOPRICE", null, 30, 1), new Candidate("NOETA", BigDecimal.TEN, null, 2),
                quote("OK", "40", 30, 3));

        assertThat(order(cands, Set.of(), Map.of(), null, null)).containsExactly("OK");
    }

    @Test
    @DisplayName("switched off, it is price order and nobody is skipped")
    void disabled() {
        settings.setEnabled(false);
        var r = Map.of("CHEAP", rel("CHEAP", 0.1, BreakerState.OPEN), "MID", rel("MID", 0.99, BreakerState.CLOSED));

        assertThat(order(three, Set.of(), r, null, null)).containsExactly("CHEAP", "MID", "DEAR");
        assertThat(order(three, Set.of("CHEAP"), r, null, null)).as("no reliability ranking either")
                .containsExactly("MID", "DEAR");
    }

    @Test
    @DisplayName("ties on price go to the faster courier, then to configured priority")
    void deterministicTies() {
        var cands = List.of(quote("B", "50", 40, 1), quote("A", "50", 30, 2), quote("C", "50", 30, 1));

        assertThat(order(cands, Set.of(), Map.of(), null, null)).containsExactly("C", "A", "B");
    }
}
