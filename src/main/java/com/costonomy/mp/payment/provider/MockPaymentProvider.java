package com.costonomy.mp.payment.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development and test payment provider. Doc 10 §4.
 *
 * <p>Mandatory rather than convenient: doc 10 §6–7 require local development and
 * CI to run with no external provider, and doc 10 §4 names the scenarios this has
 * to be able to produce — authorisation success and failure, capture success and
 * failure, delayed, duplicate and out-of-order webhooks, refund success and
 * failure.
 *
 * <p>Those are driven by the <b>amount</b>, so a test asks for a failure by
 * ordering one. No hidden switches, and the same code path as a real payment:
 *
 * <ul>
 *   <li>amount ending {@code .13} — authorisation is declined</li>
 *   <li>amount ending {@code .17} — authorisation succeeds, capture fails</li>
 *   <li>amount ending {@code .19} — refund fails</li>
 *   <li>anything else — everything succeeds</li>
 * </ul>
 *
 * <p>State is held in memory and is per-process, which is right for a mock: a test
 * that wants a payment in a particular state creates it, rather than depending on
 * what an earlier test left behind.
 *
 * <p>Signature verification accepts a fixed test signature and rejects everything
 * else — deliberately still a check, so a webhook test that forgets the header
 * fails here exactly as it would against Razorpay.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.payment", havingValue = "MOCK", matchIfMissing = true)
@Slf4j
public class MockPaymentProvider implements PaymentProvider {

    /** The signature a test sends. Not a secret, and not accepted by any real provider. */
    public static final String TEST_SIGNATURE = "mock-signature-v1";

    private static final String DECLINE_SUFFIX = "13";
    private static final String CAPTURE_FAILURE_SUFFIX = "17";
    private static final String REFUND_FAILURE_SUFFIX = "19";

    private final Map<String, ProviderPayment> payments = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> intents = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "MOCK";
    }

    @Override
    public String createAuthorizationPublicKey() {
        return "mock_key";
    }

    @Override
    public AuthorizationIntent createAuthorization(AuthorizationRequest request) {
        String orderId = "mock_order_" + UUID.randomUUID().toString().replace("-", "");
        intents.put(orderId, request.amount());
        return new AuthorizationIntent(orderId, request.amount(), request.currency(), "mock_key");
    }

    /**
     * Simulate the customer completing checkout.
     *
     * <p>Test-facing, and only reachable because this class is a mock — there is
     * no equivalent on {@link PaymentProvider}, so nothing in the domain can call
     * it. Doc 19's protected simulation endpoints follow the same principle.
     */
    public ProviderPayment completeCheckout(String providerOrderId) {
        BigDecimal amount = intents.get(providerOrderId);
        if (amount == null) {
            throw new PaymentProviderException("Unknown mock order " + providerOrderId, false, null);
        }

        String paymentId = "mock_pay_" + UUID.randomUUID().toString().replace("-", "");

        if (endsWith(amount, DECLINE_SUFFIX)) {
            var declined = new ProviderPayment(paymentId, ProviderPaymentStatus.FAILED,
                    BigDecimal.ZERO, BigDecimal.ZERO, "CARD_DECLINED",
                    "The card was declined by the issuing bank.");
            payments.put(paymentId, declined);
            return declined;
        }

        var authorized = new ProviderPayment(paymentId, ProviderPaymentStatus.AUTHORIZED,
                amount, BigDecimal.ZERO, null, null);
        payments.put(paymentId, authorized);
        return authorized;
    }

    @Override
    public ProviderPayment fetchPayment(String providerPaymentId) {
        var payment = payments.get(providerPaymentId);
        if (payment == null) {
            throw new PaymentProviderException(
                    "Unknown mock payment " + providerPaymentId, false, "NOT_FOUND");
        }
        return payment;
    }

    @Override
    public ProviderPayment capture(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        var payment = fetchPayment(providerPaymentId);

        // Capturing an already-captured payment returns the same result rather
        // than taking money again — exactly what a real provider does with a
        // repeated idempotency key, and what our retry logic depends on.
        if (payment.status() == ProviderPaymentStatus.CAPTURED) {
            return payment;
        }
        if (payment.status() != ProviderPaymentStatus.AUTHORIZED) {
            throw new PaymentProviderException(
                    "Cannot capture a payment in state " + payment.status(), false, "INVALID_STATE");
        }
        if (amount.compareTo(payment.authorizedAmount()) > 0) {
            throw new PaymentProviderException(
                    "Capture exceeds the authorised amount", false, "AMOUNT_EXCEEDS_AUTHORIZATION");
        }

        if (endsWith(payment.authorizedAmount(), CAPTURE_FAILURE_SUFFIX)) {
            throw new PaymentProviderException(
                    "Capture failed at the gateway", true, "GATEWAY_ERROR");
        }

        var captured = new ProviderPayment(providerPaymentId, ProviderPaymentStatus.CAPTURED,
                payment.authorizedAmount(), amount, null, null);
        payments.put(providerPaymentId, captured);
        return captured;
    }

    @Override
    public ProviderPayment release(String providerPaymentId, String idempotencyKey) {
        var payment = fetchPayment(providerPaymentId);
        if (payment.status() == ProviderPaymentStatus.RELEASED) {
            return payment;
        }
        var released = new ProviderPayment(providerPaymentId, ProviderPaymentStatus.RELEASED,
                payment.authorizedAmount(), BigDecimal.ZERO, null, null);
        payments.put(providerPaymentId, released);
        return released;
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey) {
        var payment = fetchPayment(providerPaymentId);
        if (payment.status() != ProviderPaymentStatus.CAPTURED) {
            throw new PaymentProviderException(
                    "Only a captured payment can be refunded", false, "INVALID_STATE");
        }
        if (endsWith(payment.capturedAmount(), REFUND_FAILURE_SUFFIX)) {
            return new ProviderRefund(null, ProviderRefundStatus.FAILED, amount,
                    "REFUND_DECLINED", "The refund was declined by the gateway.");
        }
        return new ProviderRefund("mock_rfnd_" + UUID.randomUUID().toString().replace("-", ""),
                ProviderRefundStatus.COMPLETED, amount, null, null);
    }

    @Override
    public boolean verifySignature(String rawBody, String signatureHeader) {
        // Still a real check. A webhook test that forgets the header fails here
        // exactly as it would against Razorpay, which is the point of a mock that
        // is equivalent rather than permissive.
        return TEST_SIGNATURE.equals(signatureHeader);
    }

    /** Whether the amount's paise match a scenario trigger. */
    private static boolean endsWith(BigDecimal amount, String suffix) {
        if (amount == null) {
            return false;
        }
        String paise = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        return paise.endsWith("." + suffix);
    }
}
