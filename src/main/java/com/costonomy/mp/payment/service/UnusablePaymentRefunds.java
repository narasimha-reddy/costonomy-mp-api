package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.provider.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Puts money back to where it came from when a gateway payment arrives for something that can no longer use it: a
 * delivery cancelled or expired before the restaurant paid, a fee the settlement had already taken from a supplier's
 * payout, or a second payment on a checkout (D-203, D-197).
 *
 * <p>Never called inside a database transaction (D-099). It does not throw: a refund that cannot be made now returns
 * {@code null}, and the caller leaves the checkout in a state its sweep looks at again. Safe to repeat, because it
 * asks the provider how much of the payment has already gone back and sends only the rest, under a key that is the
 * same every time for one payment.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UnusablePaymentRefunds {

    private final PaymentProvider provider;

    /**
     * Refund what is left of a captured payment.
     *
     * @param keyPrefix names what the refund is for; with the payment id it is the provider idempotency key
     * @return the provider's refund id (or a marker if it was already fully refunded); null if it could not be done now
     */
    public String refundRest(String providerPaymentId, BigDecimal capturedAmount, String keyPrefix) {
        try {
            var facts = provider.inspect(providerPaymentId);
            BigDecimal already = facts.amountRefunded() == null ? BigDecimal.ZERO : facts.amountRefunded();
            BigDecimal rest = capturedAmount.subtract(already);
            if (rest.signum() <= 0) {
                return "already-refunded";
            }
            var refund = provider.refund(providerPaymentId, rest, keyPrefix + providerPaymentId,
                    PaymentProvider.RefundOptions.none());
            log.info("Refunded unusable gateway payment {} ({}), refund {}", providerPaymentId, rest.toPlainString(),
                    refund.providerRefundId());
            return refund.providerRefundId();
        } catch (RuntimeException ex) {
            log.error("Could not refund unusable gateway payment {} now; the sweep will try again: {}",
                    providerPaymentId, ex.getMessage());
            return null;
        }
    }
}
