package com.costonomy.mp.quickscan.provider;

import java.math.BigDecimal;

/**
 * Sends money to a UPI handle. D-106.
 *
 * <p>A port, so no provider DTO reaches the domain — the same shape as
 * {@link com.costonomy.mp.payment.provider.PaymentProvider}, but the other
 * direction: money leaves the platform rather than arriving at it.
 *
 * <p><b>Every send carries an idempotency key.</b> A network failure after the
 * provider acted is indistinguishable from one before, so a retry without a key
 * risks paying the same shop twice.
 *
 * <p>{@code MOCK} is the only adapter today. A RazorpayX adapter is the natural
 * next one — once a test account exists to build it against.
 */
public interface PayoutProvider {

    /** {@code MOCK} or, later, {@code RAZORPAYX}. Not persisted; there is one provider live at a time. */
    String name();

    /** Send money to a UPI handle. */
    ProviderPayout createPayout(String vpa, String payeeName, BigDecimal amount,
                                String reference, String idempotencyKey);

    /** Ask the provider what a payout it accepted is doing now. */
    ProviderPayout fetchPayout(String providerPayoutId);

    /** The provider's view of a payout. Authoritative over ours. */
    record ProviderPayout(
            String providerPayoutId,
            PayoutStatus status,
            String failureCode,
            String failureReason) {
    }

    enum PayoutStatus {
        PENDING,
        PROCESSED,
        FAILED,
        REVERSED
    }
}
