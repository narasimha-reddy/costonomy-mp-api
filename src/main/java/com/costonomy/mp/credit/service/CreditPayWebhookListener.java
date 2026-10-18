package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptRepository;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.service.ProviderPaymentListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Claims the Razorpay webhooks of credit payment orders (D-199). The webhook service offers it every payment it has no
 * order payment for; if the order is a credit payment attempt's, the payment is fetched from Razorpay (the payload is
 * never trusted) and handed to the one place a capture is applied.
 *
 * <p>Not gated by the online-pay flag: money already in flight must still land when the flag is off. Not transactional
 * and not behind a ShedLock: the settlement serialises on the attempt row, and a provider error propagates so the event
 * is stored FAILED and can be replayed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreditPayWebhookListener implements ProviderPaymentListener {

    private final CreditPaymentAttemptRepository attempts;
    private final PaymentProvider provider;
    private final CreditPaySettlement settlement;

    @Override
    public boolean onProviderPayment(String providerOrderId, String providerPaymentId, String eventType) {
        if (providerOrderId == null || providerPaymentId == null) {
            return false;
        }
        var attempt = attempts.findByRazorpayOrderId(providerOrderId).orElse(null);
        if (attempt == null) {
            return false;
        }
        try (var trace = TraceScope.of("credit_payment_attempt", attempt.getId(), "outlet", attempt.getOutletId(),
                "rzp_order", providerOrderId, "rzp_payment", providerPaymentId)) {
            // A retryable provider error is thrown on purpose: the event is FAILED and replayable.
            var payment = provider.fetchPayment(providerPaymentId);
            var outcome = settlement.settle(attempt.getId(), payment, "WEBHOOK");
            log.info("Credit payment attempt {} webhook {} for payment {} → {}",
                    attempt.getId(), eventType, providerPaymentId, outcome);
            return true;
        }
    }
}
