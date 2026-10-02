package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.provider.PaymentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * How long a provider holds an authorisation before it returns it, and what we do
 * about it (D-109).
 *
 * <p>One number, {@code costonomy.mp.razorpay.manual-expiry-minutes}, drives both
 * the expiry sent to Razorpay when an order's payment is created and every check
 * we make against it here. They used to be two separate constants — five days on
 * the order, five days less six hours in the guard — and Razorpay's documents
 * disagree on whether its longest hold is three days or five. If it is three, a
 * supplier could mark an order ready on day four against money Razorpay had
 * already given back, and the goods would leave unpaid. With one property the
 * two cannot drift, and setting it is the single decision the owner has to make
 * once Razorpay confirms the limit.
 *
 * <p>The setting is read once, when an order's payment is created, and stored on the
 * payment ({@code hold_minutes}) with what Razorpay was told. Every check after that
 * uses the payment's own figure ({@link #holdLimit(Payment)}), because Razorpay fixes an
 * order's expiry at creation and a changed setting does not reach orders already made.
 * Only a payment from before it was stored falls back to the setting.
 *
 * <p>Read here whichever provider runs, so the mock exercises the same guard.
 */
@Component
public class PaymentHoldPolicy {

    private final Duration holdLimit;

    public PaymentHoldPolicy(
            @Value("${costonomy.mp.razorpay.manual-expiry-minutes:4320}") int holdMinutes) {
        if (holdMinutes < PaymentProvider.MIN_HOLD_MINUTES || holdMinutes > PaymentProvider.MAX_HOLD_MINUTES) {
            throw new IllegalStateException("costonomy.mp.razorpay.manual-expiry-minutes must be between "
                    + PaymentProvider.MIN_HOLD_MINUTES + " and " + PaymentProvider.MAX_HOLD_MINUTES
                    + " (Razorpay's limits), not " + holdMinutes);
        }
        this.holdLimit = Duration.ofMinutes(holdMinutes);
    }

    /** How long the provider holds an authorisation, as the setting reads now. */
    public Duration holdLimit() {
        return holdLimit;
    }

    /**
     * How long the provider holds <em>this payment's</em> authorisation: the expiry it
     * was created with, which Razorpay fixed when the order was made and will not
     * change, or the current setting for a payment made before it was stored (F3).
     *
     * <p>Reading the setting instead is the failure that setting was meant to remove:
     * raise it to five days and every order made under three would be usable for 114
     * hours against a hold Razorpay returns at 72.
     */
    public Duration holdLimit(Payment payment) {
        Integer stored = payment.getHoldMinutes();
        return stored == null || stored <= 0 ? holdLimit : Duration.ofMinutes(stored);
    }

    /**
     * The margin kept before a limit: six hours, or a quarter of a very short
     * hold, so a test hold of twelve minutes still has a usable window.
     */
    private static Duration margin(Duration limit) {
        Duration sixHours = Duration.ofHours(6);
        Duration quarter = limit.dividedBy(4);
        return quarter.compareTo(sixHours) < 0 ? quarter : sixHours;
    }

    /**
     * How long after authorisation goods may still be handed over: the limit less a
     * margin, so nothing leaves against a hold about to lapse (D-103). Three days
     * gives sixty-six hours.
     */
    public Duration usableFor() {
        return usableFor(holdLimit);
    }

    /** As {@link #usableFor()}, for one payment: against the hold it was created with. */
    public Duration usableFor(Payment payment) {
        return usableFor(holdLimit(payment));
    }

    private static Duration usableFor(Duration limit) {
        return limit.minus(margin(limit));
    }

    /** A held payment this old with the order not dispatched is logged for someone to act on. */
    public Duration warnAfter() {
        return warnAfter(holdLimit);
    }

    /** As {@link #warnAfter()}, for one payment. */
    public Duration warnAfter(Payment payment) {
        return warnAfter(holdLimit(payment));
    }

    private static Duration warnAfter(Duration limit) {
        Duration warn = limit.minusDays(1);
        Duration floor = usableFor(limit);
        // Never later than the point the supplier is refused, or the warning would
        // arrive after the refusal it is meant to precede; never negative.
        return warn.compareTo(floor) > 0 ? floor : warn.isNegative() ? Duration.ZERO : warn;
    }
}
