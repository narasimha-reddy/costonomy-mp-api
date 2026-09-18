package com.costonomy.mp.intent.domain;

import com.costonomy.mp.common.config.AppConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The clocks an intent runs on, and where they come from.
 *
 * <p>Three windows, all configurable and none hard-coded in a constant a mobile
 * build could disagree with (§20):
 *
 * <ul>
 *   <li><b>Order creation</b> — how long the restaurant has after the supplier
 *       accepts. Bounded, because the supplier has committed stock to it.</li>
 *   <li><b>Response</b> — how long the supplier has to answer at all.</li>
 * </ul>
 *
 * <p><b>There is no separate "acceptance validity".</b> An acceptance stands for
 * exactly as long as an order can be created from it, so
 * {@code intent_acceptance.expires_at} is set to the intent's
 * {@code order_creation_deadline} rather than computed from a second setting. Two
 * clocks that must agree are two clocks that will eventually disagree, and the
 * disagreement would read as "your offer is still valid" on one screen and
 * "this request expired" on the other.
 *
 * <p><b>The order-creation window is snapshotted onto the intent at acceptance,
 * never re-read.</b> Reading it at order time would make the deadline a function
 * of today's configuration: raise the setting and yesterday's expired intents
 * come back to life; lower it and a restaurant loses a window it was told it had.
 * Neither is defensible to the person holding the phone, so
 * {@link #orderCreationDeadline} is computed once and stored.
 */
@Component
@RequiredArgsConstructor
public class IntentPolicy {

    /**
     * Thirty minutes.
     *
     * <p>Two pressures set this. The supplier has committed stock and should not
     * hold it all day, which argues for a short window. But the restaurant is not
     * waiting at the phone — an acceptance can land hours after the request, and
     * the window starts when the supplier answers, not when the restaurant looks.
     * A five-minute window would expire most requests before anyone read the
     * notification, and the restaurant would learn that accepted requests
     * routinely evaporate.
     */
    static final int DEFAULT_ORDER_CREATION_WINDOW_SECONDS = 30 * 60;

    /** A working day to answer a request. */
    static final int DEFAULT_RESPONSE_WINDOW_SECONDS = 24 * 60 * 60;

    /** Floor and ceiling, so a mistyped configuration cannot make a window absurd. */
    static final int MIN_ORDER_CREATION_WINDOW_SECONDS = 60;
    static final int MAX_ORDER_CREATION_WINDOW_SECONDS = 24 * 60 * 60;

    private final AppConfigService config;

    /**
     * The window to freeze onto an intent being accepted right now.
     *
     * <p>Clamped rather than trusted: a value of zero would expire every intent
     * the instant it was accepted, and the failure would look like a bug in the
     * countdown rather than a typo in a settings table.
     */
    public int orderCreationWindowSeconds() {
        int configured = config.getInt("intent.orderCreationWindowSeconds",
                DEFAULT_ORDER_CREATION_WINDOW_SECONDS);
        // min/max rather than Math.clamp: that arrived in Java 21 and this
        // builds on 17.
        return Math.min(MAX_ORDER_CREATION_WINDOW_SECONDS,
                Math.max(MIN_ORDER_CREATION_WINDOW_SECONDS, configured));
    }

    public Duration responseWindow() {
        return Duration.ofSeconds(Math.max(60,
                config.getInt("intent.responseWindowSeconds", DEFAULT_RESPONSE_WINDOW_SECONDS)));
    }

    /**
     * The deadline to store, from the window as it stands at this moment.
     *
     * <p>Takes the accepting instant rather than reading the clock itself, so the
     * deadline, the {@code accepted_at} it is measured from, and the acceptance
     * row all agree to the microsecond.
     */
    public Instant orderCreationDeadline(Instant acceptedAt, int windowSeconds) {
        return acceptedAt.plusSeconds(windowSeconds);
    }
}
