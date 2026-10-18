package com.costonomy.mp.delivery.domain;

import com.costonomy.mp.delivery.domain.ProviderReliability.BreakerState;
import com.costonomy.mp.delivery.domain.ProviderReliability.Observations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** What makes a courier look reliable, and when the circuit breaker opens, probes and closes again. */
class ProviderReliabilityTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private final ReliabilitySettings settings = new ReliabilitySettings();

    /** Recent attempts only; everything else empty. */
    private static Observations recent(int attempts, int refused, int settled, int assigned, int stoodDown,
                                       Instant last) {
        return new Observations(attempts, refused, settled, assigned, stoodDown, last, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private ProviderReliability assess(Observations o) {
        return ProviderReliability.assess("PIDGE", o, settings, NOW);
    }

    @Test
    @DisplayName("a courier we know nothing about is assumed decent and is never skipped")
    void unknownCourier() {
        var r = assess(Observations.none());

        assertThat(r.breaker()).isEqualTo(BreakerState.CLOSED);
        assertThat(r.score()).isCloseTo(settings.getPriorSuccess(), org.assertj.core.data.Offset.offset(0.0001));
        assertThat(r.judged()).isZero();
        assertThat(r.fulfilment()).isEqualTo(1.0);
        assertThat(ProviderReliability.unknown("X", settings).score()).isEqualTo(settings.getPriorSuccess());
    }

    @Test
    @DisplayName("a courier that always delivers scores above one that often fails")
    void goodBeatsBad() {
        var good = assess(recent(20, 0, 20, 20, 0, NOW.minusSeconds(60)));
        var bad = assess(recent(20, 8, 12, 4, 2, NOW.minusSeconds(60)));

        assertThat(good.score()).isGreaterThan(0.9);
        assertThat(bad.score()).isLessThan(good.score() - 0.2);
    }

    @Test
    @DisplayName("two failures in two attempts do not write a courier off: the score is pulled toward the prior")
    void smoothing() {
        var r = assess(recent(2, 2, 0, 0, 0, NOW.minusSeconds(60)));

        assertThat(r.score()).isGreaterThan(0.4).isLessThan(settings.getPriorSuccess());
        assertThat(r.breaker()).as("too few attempts to open").isEqualTo(BreakerState.CLOSED);
    }

    @Test
    @DisplayName("a rider who was assigned and then cancelled does not count as fulfilled")
    void standDownIsNotFulfilment() {
        var kept = assess(recent(10, 0, 10, 10, 0, NOW.minusSeconds(60)));
        var cancelled = assess(recent(10, 0, 10, 10, 6, NOW.minusSeconds(60)));

        assertThat(kept.fulfilment()).isEqualTo(1.0);
        assertThat(cancelled.fulfilment()).isEqualTo(0.4);
        assertThat(cancelled.score()).isLessThan(kept.score());
    }

    @Test
    @DisplayName("refused bookings and bookings with no rider both lower fulfilment")
    void refusalAndNoRider() {
        // 10 attempts: 3 refused outright, 7 booked and settled, of which 4 got a rider.
        var r = assess(recent(10, 3, 7, 4, 0, NOW.minusSeconds(60)));

        assertThat(r.judged()).isEqualTo(10);
        assertThat(r.fulfilment()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("the breaker opens only with enough evidence, and only below the threshold")
    void breakerNeedsEvidence() {
        assertThat(assess(recent(4, 4, 0, 0, 0, NOW.minusSeconds(30))).breaker())
                .as("4 failures out of 4 is below the 5 needed").isEqualTo(BreakerState.CLOSED);
        assertThat(assess(recent(5, 5, 0, 0, 0, NOW.minusSeconds(30))).breaker())
                .as("5 out of 5").isEqualTo(BreakerState.OPEN);
        assertThat(assess(recent(10, 5, 5, 5, 0, NOW.minusSeconds(30))).breaker())
                .as("exactly at the threshold is still fine").isEqualTo(BreakerState.CLOSED);
        assertThat(assess(recent(10, 6, 4, 4, 0, NOW.minusSeconds(30))).breaker())
                .as("4 of 10").isEqualTo(BreakerState.OPEN);
    }

    @Test
    @DisplayName("an open breaker lets one probe through after a quiet spell, and goes back to open if it was just tried")
    void breakerProbes() {
        var failing = recent(10, 10, 0, 0, 0, null);

        assertThat(assess(withLast(failing, NOW.minus(Duration.ofMinutes(2)))).breaker()).isEqualTo(BreakerState.OPEN);
        assertThat(assess(withLast(failing, NOW.minus(Duration.ofMinutes(10)))).breaker()).isEqualTo(BreakerState.HALF_OPEN);
        assertThat(assess(withLast(failing, NOW.minus(Duration.ofHours(1)))).breaker()).isEqualTo(BreakerState.HALF_OPEN);
    }

    @Test
    @DisplayName("the breaker closes again once the courier recovers")
    void breakerRecovers() {
        // The failures have aged out of the window: only recent successes remain.
        assertThat(assess(recent(6, 0, 6, 6, 0, NOW.minusSeconds(90))).breaker()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    @DisplayName("switched off, no courier is ever skipped")
    void disabled() {
        settings.setEnabled(false);
        assertThat(assess(recent(10, 10, 0, 0, 0, NOW.minusSeconds(30))).breaker()).isEqualTo(BreakerState.CLOSED);
    }

    @Test
    @DisplayName("long-term stats and quote failures also count, but less than a courier that stops sending riders")
    void otherSignals() {
        var quotesFailing = new Observations(0, 0, 0, 0, 0, null, 20, 10, 0, 0, 0, 0, 0, 0);
        var cancelsALot = new Observations(0, 0, 0, 0, 0, null, 0, 0, 50, 20, 5, 0, 0, 0);
        var noRiders = recent(20, 0, 20, 4, 0, NOW.minusSeconds(60));

        double base = assess(Observations.none()).score();
        assertThat(assess(quotesFailing).score()).isLessThan(base);
        assertThat(assess(cancelsALot).score()).isLessThan(base);
        assertThat(assess(noRiders).score()).as("the heaviest weight is on sending riders")
                .isLessThan(assess(quotesFailing).score());
    }

    @Test
    @DisplayName("with every weight at zero the score falls back to the prior instead of dividing by zero")
    void zeroWeights() {
        settings.setAssignmentWeight(0);
        settings.setAcceptanceWeight(0);
        settings.setQuoteWeight(0);
        settings.setPostAssignmentWeight(0);
        settings.setDeliveryWeight(0);

        assertThat(assess(recent(10, 5, 5, 0, 0, NOW)).score()).isEqualTo(settings.getPriorSuccess());
    }

    private static Observations withLast(Observations o, Instant last) {
        return new Observations(o.attempts(), o.refused(), o.settled(), o.assigned(), o.stoodDown(), last,
                o.quotesAsked(), o.quotesFailed(), o.bookings(), o.driverCancellations(), o.pickupFailures(),
                o.deliveryFailures(), o.completed(), o.etaOverruns());
    }
}
