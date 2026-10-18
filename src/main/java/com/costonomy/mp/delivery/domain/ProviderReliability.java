package com.costonomy.mp.delivery.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * How reliable one courier has been lately, and whether it is currently safe to ask (D-197 addendum).
 *
 * <p>Built from what actually happened to our requests, including the failures the nightly stats cannot see: a courier
 * that did not answer a quote, refused a booking, or accepted one and never sent a rider. Those happen before
 * {@code DRIVER_ASSIGNED}, which is where the nightly stats start counting.
 *
 * @param score    0 to 1, higher is better; a weighted blend of five smoothed rates
 * @param breaker  whether the courier should be asked at all right now
 * @param fulfilment of the attempts that have had time to resolve, the share that ended with a rider who stayed;
 *                  1 when there are none yet
 * @param judged   how many attempts that share is out of
 */
public record ProviderReliability(String providerCode, double score, BreakerState breaker,
                                  double fulfilment, int judged) {

    public enum BreakerState {
        /** Asked as usual. */
        CLOSED,
        /** Failing, but it has been quiet long enough to try once more. Ranked last, so a failure costs least. */
        HALF_OPEN,
        /** Failing and tried recently. Skipped unless every courier is in this state. */
        OPEN
    }

    /**
     * What was observed, as plain counts. Recent counts come from a short window of attempts and quotes; the long-term
     * counts are summed from the nightly stats.
     */
    public record Observations(
            int attempts, int refused, int settled, int assigned, int stoodDown, Instant lastAttemptAt,
            int quotesAsked, int quotesFailed,
            int bookings, int driverCancellations, int pickupFailures, int deliveryFailures,
            int completed, int etaOverruns) {

        public static Observations none() {
            return new Observations(0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** A courier we know nothing about: assumed decent, never skipped. */
    public static ProviderReliability unknown(String providerCode, ReliabilitySettings s) {
        return new ProviderReliability(providerCode, s.getPriorSuccess(), BreakerState.CLOSED, 1.0, 0);
    }

    public static ProviderReliability assess(String providerCode, Observations o, ReliabilitySettings s, Instant now) {
        // Of the bookings that have had time to resolve (or were refused outright), how many ended with a rider who
        // stayed. A rider who was assigned and then cancelled does not count as fulfilled.
        int fulfilled = Math.max(0, o.assigned() - o.stoodDown());
        int judged = o.refused() + o.settled();
        double fulfilment = judged == 0 ? 1.0 : Math.min(1.0, fulfilled / (double) judged);

        double acceptance = smooth(o.attempts() - o.refused(), o.attempts(), s);
        double assignment = smooth(fulfilled, o.settled(), s);
        double quote = smooth(o.quotesAsked() - o.quotesFailed(), o.quotesAsked(), s);
        double postAssignment = smooth(
                o.bookings() - o.driverCancellations() - o.pickupFailures(), o.bookings(), s);
        double delivery = (smooth(o.completed(), o.completed() + o.deliveryFailures(), s)
                + smooth(o.completed() - o.etaOverruns(), o.completed(), s)) / 2.0;

        double weights = s.getAssignmentWeight() + s.getAcceptanceWeight() + s.getQuoteWeight()
                + s.getPostAssignmentWeight() + s.getDeliveryWeight();
        double score = weights <= 0 ? s.getPriorSuccess()
                : (s.getAssignmentWeight() * assignment + s.getAcceptanceWeight() * acceptance
                + s.getQuoteWeight() * quote + s.getPostAssignmentWeight() * postAssignment
                + s.getDeliveryWeight() * delivery) / weights;

        return new ProviderReliability(providerCode, score, breaker(o, judged, fulfilment, s, now), fulfilment, judged);
    }

    /**
     * Stateless: the state is read off recent attempts each time, so there is nothing to store and nothing to reset.
     * Open needs evidence (a minimum number of resolved attempts), and it lets one probe through after a quiet spell:
     * if that fails the breaker is open again for another spell, if it works the rate recovers.
     */
    private static BreakerState breaker(Observations o, int judged, double fulfilment, ReliabilitySettings s, Instant now) {
        if (!s.isEnabled() || judged < s.getBreakerMinSamples() || fulfilment >= s.getBreakerThreshold()) {
            return BreakerState.CLOSED;
        }
        Duration quiet = o.lastAttemptAt() == null ? Duration.ZERO : Duration.between(o.lastAttemptAt(), now);
        return quiet.compareTo(s.getProbeAfter()) >= 0 ? BreakerState.HALF_OPEN : BreakerState.OPEN;
    }

    /** {@code (successes + prior*k) / (total + k)}: pulled toward the prior until there is evidence. */
    static double smooth(int successes, int total, ReliabilitySettings s) {
        double k = s.getPriorWeight();
        return (Math.max(0, successes) + s.getPriorSuccess() * k) / (Math.max(0, total) + k);
    }
}
