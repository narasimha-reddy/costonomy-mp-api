package com.costonomy.mp.payment.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
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
    /** Intents asked to capture on payment (a wallet top-up, D-107) rather than hold. */
    private final java.util.Set<String> autoCaptured = ConcurrentHashMap.newKeySet();
    /** Intent → the payment that completed it, for {@link #findPaymentForOrder}. */
    private final Map<String, String> paymentByOrder = new ConcurrentHashMap<>();
    private final Map<String, ProviderRefund> refunds = new ConcurrentHashMap<>();
    /** Every refund the mock holds, by payment, as {@link #listRefunds} lists it: ours (with the receipt we sent) and foreign ones. */
    private final Map<String, java.util.List<ProviderRefundEntry>> entriesByPayment = new ConcurrentHashMap<>();
    /** Idempotency key → the refund it made, so a resend returns it as Razorpay does. */
    private final Map<String, ProviderRefund> refundsByKey = new ConcurrentHashMap<>();
    /** Payment → what has been refunded of it, for {@link #inspect}. */
    private final Map<String, BigDecimal> refundedByPayment = new ConcurrentHashMap<>();
    /** Payment → when it was made, for {@link #inspect}. */
    private final Map<String, java.time.Instant> createdAt = new ConcurrentHashMap<>();
    /** Payments the provider returned to the payer unused, as Razorpay does at expiry. */
    private final java.util.Set<String> expired = ConcurrentHashMap.newKeySet();

    /** Refund amount ending in this: accepted as PENDING, completed when fetched. */
    private static final String REFUND_PENDING_SUFFIX = "23";

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
        if (request.autoCapture()) {
            autoCaptured.add(orderId);
        }
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
        return completeCheckout(providerOrderId, "card");
    }

    /**
     * As {@link #completeCheckout(String)}, paid with the given method: {@code card},
     * {@code upi}, {@code netbanking}, {@code wallet}, {@code emi}. What decides
     * whether cancelling the order drops a hold or returns debited money (D-109),
     * so the tests can pay each way.
     */
    public ProviderPayment completeCheckout(String providerOrderId, String method) {
        BigDecimal amount = intents.get(providerOrderId);
        if (amount == null) {
            throw new PaymentProviderException("Unknown mock order " + providerOrderId, false, null);
        }

        String paymentId = "mock_pay_" + UUID.randomUUID().toString().replace("-", "");
        createdAt.put(paymentId, java.time.Instant.now());
        String detail = "card".equals(method) ? "1111" : null;

        if (endsWith(amount, DECLINE_SUFFIX)) {
            var declined = new ProviderPayment(paymentId, providerOrderId, ProviderPaymentStatus.FAILED,
                    BigDecimal.ZERO, BigDecimal.ZERO, "CARD_DECLINED",
                    "The card was declined by the issuing bank.", method, detail);
            payments.put(paymentId, declined);
            return declined;
        }

        // An auto-capture intent goes straight to CAPTURED, as Razorpay does with
        // capture = automatic: there is no held state for a top-up to sit in.
        boolean captured = autoCaptured.contains(providerOrderId);
        var authorized = new ProviderPayment(paymentId, providerOrderId,
                captured ? ProviderPaymentStatus.CAPTURED : ProviderPaymentStatus.AUTHORIZED,
                amount, captured ? amount : BigDecimal.ZERO, null, null, method, detail);
        payments.put(paymentId, authorized);
        paymentByOrder.put(providerOrderId, paymentId);
        return authorized;
    }

    /**
     * Simulate the provider returning an uncaptured authorisation to the payer
     * because its hold ran out — Razorpay's auto-refund at expiry (D-109). Test-facing.
     * It is then RELEASED as the port normalises it, and cannot be captured.
     */
    public ProviderPayment expire(String providerPaymentId) {
        var payment = fetchPayment(providerPaymentId);
        if (payment.status() != ProviderPaymentStatus.AUTHORIZED) {
            throw new PaymentProviderException(
                    "Only an authorised payment can expire, not " + payment.status(), false, "INVALID_STATE");
        }
        var released = new ProviderPayment(providerPaymentId, payment.providerOrderId(),
                ProviderPaymentStatus.RELEASED, payment.authorizedAmount(), BigDecimal.ZERO,
                null, null, payment.method(), payment.methodDetail());
        payments.put(providerPaymentId, released);
        expired.add(providerPaymentId);
        return released;
    }

    /** How many refunds this mock has made in all, for asserting that money went out once. */
    public int refundCount() {
        return refunds.size();
    }

    /** What the mock has refunded of one payment so far. */
    public BigDecimal refundedOf(String providerPaymentId) {
        return refundedByPayment.getOrDefault(providerPaymentId, BigDecimal.ZERO);
    }

    /**
     * Simulate one declined attempt on an order, whatever its amount — the first
     * try in a checkout the customer then retries. Test-facing, like
     * {@link #completeCheckout}; Razorpay allows several attempts per order.
     */
    public ProviderPayment declineAttempt(String providerOrderId) {
        if (!intents.containsKey(providerOrderId)) {
            throw new PaymentProviderException("Unknown mock order " + providerOrderId, false, null);
        }
        String paymentId = "mock_pay_" + UUID.randomUUID().toString().replace("-", "");
        var declined = new ProviderPayment(paymentId, providerOrderId, ProviderPaymentStatus.FAILED,
                BigDecimal.ZERO, BigDecimal.ZERO, "BAD_REQUEST_ERROR", "Payment was declined by the bank.",
                "card", "1111");
        payments.put(paymentId, declined);
        return declined;
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
    public Optional<ProviderPayment> findPaymentForOrder(String providerOrderId) {
        // Declines are never recorded here, matching the port's contract.
        return Optional.ofNullable(paymentByOrder.get(providerOrderId)).map(payments::get);
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

        var captured = new ProviderPayment(providerPaymentId, payment.providerOrderId(),
                ProviderPaymentStatus.CAPTURED,
                payment.authorizedAmount(), amount, null, null,
                payment.method(), payment.methodDetail());
        payments.put(providerPaymentId, captured);
        return captured;
    }

    @Override
    public ProviderPayment release(String providerPaymentId, String idempotencyKey) {
        var payment = fetchPayment(providerPaymentId);
        if (payment.status() == ProviderPaymentStatus.RELEASED) {
            return payment;
        }
        var released = new ProviderPayment(providerPaymentId, payment.providerOrderId(),
                ProviderPaymentStatus.RELEASED,
                payment.authorizedAmount(), BigDecimal.ZERO, null, null,
                payment.method(), payment.methodDetail());
        payments.put(providerPaymentId, released);
        return released;
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey,
                                 RefundOptions options) {
        // A resend of the same key is the same refund, as at Razorpay: what makes a
        // lost answer safe to ask again. Checked first, before anything about the
        // payment, so a replay is answered even where the payment has since changed.
        var replay = refundsByKey.get(idempotencyKey);
        if (replay != null) {
            return replay;
        }
        var payment = fetchPayment(providerPaymentId);
        if (payment.status() != ProviderPaymentStatus.CAPTURED) {
            throw new PaymentProviderException(
                    "Only a captured payment can be refunded", false, "INVALID_STATE",
                    ProviderFailureKind.NOT_CAPTURED, "The payment status should be captured");
        }
        // As Razorpay refuses them (D-110): a payment already refunded in full, and more than is left.
        BigDecimal left = payment.authorizedAmount().subtract(refundedOf(providerPaymentId));
        if (left.signum() <= 0) {
            throw new PaymentProviderException("The payment has been fully refunded already", false,
                    "400", ProviderFailureKind.ALREADY_REFUNDED, "The payment has been fully refunded already");
        }
        if (amount.compareTo(left) > 0) {
            throw new PaymentProviderException("The refund amount is greater than the refundable amount", false,
                    "400", ProviderFailureKind.OVER_REFUND, "The refund amount is greater than the refundable amount");
        }
        if (endsWith(payment.capturedAmount(), REFUND_FAILURE_SUFFIX)) {
            return new ProviderRefund(null, ProviderRefundStatus.FAILED, amount,
                    "REFUND_DECLINED", "The refund was declined by the gateway.");
        }
        String refundId = "mock_rfnd_" + UUID.randomUUID().toString().replace("-", "");
        var refund = new ProviderRefund(refundId,
                endsWith(amount, REFUND_PENDING_SUFFIX) ? ProviderRefundStatus.PENDING
                        : ProviderRefundStatus.COMPLETED, amount, null, null);
        refunds.put(refundId, refund);
        refundsByKey.put(idempotencyKey, refund);
        refundedByPayment.merge(providerPaymentId, amount, BigDecimal::add);
        entriesByPayment.computeIfAbsent(providerPaymentId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(new ProviderRefundEntry(refundId, amount, refund.status(),
                        options == null ? null : options.receipt(),
                        options == null || options.notes() == null ? null : options.notes().get("mandi_refund_id"),
                        java.time.Instant.now()));
        return refund;
    }

    /**
     * A refund made at the provider by someone else, in its dashboard: on the list without our
     * receipt and counted in what has gone back. Test-facing (D-110).
     */
    public ProviderRefundEntry refundedExternally(String providerPaymentId, BigDecimal amount) {
        fetchPayment(providerPaymentId);
        String refundId = "mock_rfnd_foreign_" + UUID.randomUUID().toString().replace("-", "");
        var entry = new ProviderRefundEntry(refundId, amount, ProviderRefundStatus.COMPLETED, null, null,
                java.time.Instant.now());
        entriesByPayment.computeIfAbsent(providerPaymentId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(entry);
        refundedByPayment.merge(providerPaymentId, amount, BigDecimal::add);
        return entry;
    }

    @Override
    public java.util.List<ProviderRefundEntry> listRefunds(String providerPaymentId) {
        // As the provider does: a payment it does not know is refused, not listed as empty.
        fetchPayment(providerPaymentId);
        return java.util.List.copyOf(entriesByPayment.getOrDefault(providerPaymentId, java.util.List.of()));
    }

    @Override
    public ProviderPaymentFacts inspect(String providerPaymentId) {
        var payment = fetchPayment(providerPaymentId);
        BigDecimal refunded = refundedOf(providerPaymentId);
        boolean captured = payment.status() == ProviderPaymentStatus.CAPTURED;
        // As Razorpay reports it: a captured payment that has been refunded in full is
        // "refunded" and still captured; an authorisation the provider returned is
        // "refunded" and never was.
        ProviderPaymentStatus status = expired.contains(providerPaymentId)
                ? ProviderPaymentStatus.REFUNDED
                : captured && refunded.compareTo(payment.authorizedAmount()) >= 0
                        ? ProviderPaymentStatus.REFUNDED : payment.status();
        return new ProviderPaymentFacts(providerPaymentId, payment.providerOrderId(), status,
                captured, payment.authorizedAmount(), refunded,
                payment.method(), payment.methodDetail(),
                // A captured payment has a fee, GST included as Razorpay reports it: 2% plus 18% on that, to the paisa.
                captured ? payment.authorizedAmount().multiply(new BigDecimal("0.0236"))
                        .setScale(2, java.math.RoundingMode.HALF_UP) : null,
                createdAt.get(providerPaymentId));
    }

    @Override
    public ProviderRefund fetchRefund(String providerRefundId) {
        var refund = refunds.get(providerRefundId);
        if (refund == null) {
            throw new PaymentProviderException("Unknown mock refund " + providerRefundId, false, "NOT_FOUND");
        }
        // A pending refund finishes by the time anyone asks again.
        var settled = new ProviderRefund(refund.providerRefundId(), ProviderRefundStatus.COMPLETED,
                refund.amount(), null, null);
        refunds.put(providerRefundId, settled);
        entriesByPayment.values().forEach(list -> list.replaceAll(entry -> providerRefundId.equals(entry.providerRefundId())
                ? new ProviderRefundEntry(entry.providerRefundId(), entry.amount(), ProviderRefundStatus.COMPLETED,
                        entry.receipt(), entry.mandiRefundId(), entry.createdAt()) : entry));
        return settled;
    }

    @Override
    public boolean verifySignature(String rawBody, String signatureHeader) {
        // Still a real check. A webhook test that forgets the header fails here
        // exactly as it would against Razorpay, which is the point of a mock that
        // is equivalent rather than permissive.
        return TEST_SIGNATURE.equals(signatureHeader);
    }

    @Override
    public boolean verifyCheckoutSignature(String providerOrderId, String providerPaymentId, String signature) {
        // A real check for the same reason as verifySignature: a test that sends
        // the wrong signature must be refused as it would be by Razorpay.
        return TEST_SIGNATURE.equals(signature);
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
