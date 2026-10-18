package com.costonomy.mp.delivery.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * The knobs of courier reliability (D-197 addendum). A plain class so the scoring and ranking in {@code domain}
 * need no Spring; {@code ReliabilityProperties} binds it from {@code costonomy.mp.delivery.reliability.*}.
 *
 * <p>The defaults are a starting point, not a measurement. Weights lean on the failures a restaurant actually feels:
 * a courier that accepts a booking and never sends a rider costs the whole assignment window.
 */
@Getter
@Setter
public class ReliabilitySettings {

    /** Off: couriers are ranked by price alone and no courier is ever skipped, as before. */
    private boolean enabled = true;

    /** How far back recent attempts and quotes are read. Short, so an outage shows within the hour. */
    private Duration window = Duration.ofHours(2);

    /** At most this many of a courier's newest attempts in the window count. */
    private int maxSamples = 30;

    /**
     * How old a booked attempt must be before "no rider yet" counts against the courier. Past the assignment
     * deadline (3 minutes for a bike) plus slack, so a rider still on the way is not held against them.
     */
    private Duration settleAfter = Duration.ofMinutes(5);

    /** Days of the nightly stats that make up the long-term view. */
    private int statsDays = 7;

    /**
     * What an unknown courier is assumed to be worth, and how many observations that assumption is worth. A new
     * courier is neither trusted fully nor written off on two orders: {@code (successes + prior*k) / (total + k)}.
     */
    private double priorSuccess = 0.85;
    private double priorWeight = 5;

    /** Weights of the five components of the score. They need not sum to one. */
    private double assignmentWeight = 0.40;
    private double acceptanceWeight = 0.20;
    private double quoteWeight = 0.10;
    private double postAssignmentWeight = 0.15;
    private double deliveryWeight = 0.15;

    /** The circuit breaker opens when recent fulfilment drops below this, with at least {@link #breakerMinSamples}. */
    private double breakerThreshold = 0.5;
    private int breakerMinSamples = 5;

    /** An open breaker lets one attempt through after this long without one, to find out whether it recovered. */
    private Duration probeAfter = Duration.ofMinutes(10);

    /**
     * After a failure, couriers priced within this multiple of the cheapest remaining quote are ranked by
     * reliability; dearer ones come after, by price. Bounds what the restaurant pays for reliability.
     */
    private double costFactor = 1.5;
}
