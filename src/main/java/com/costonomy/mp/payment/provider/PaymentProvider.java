package com.costonomy.mp.payment.provider;

import java.math.BigDecimal;

/**
 * Takes and returns money. Doc 02 §2, doc 21.
 *
 * <p>A port, so no Razorpay type reaches the domain. Razorpay in production, a
 * mock everywhere else — doc 10 §4 makes the mock mandatory, not a convenience.
 *
 * <p><b>Every method carries an idempotency key.</b> A network failure after the
 * provider acted is indistinguishable from one before, so a retry without a key
 * is a retry that may charge twice. Our own idempotency table prevents duplicate
 * <em>work</em>; only the provider's key prevents duplicate <em>money</em>.
 *
 * <p>Authorization is deliberately two steps — {@link #createAuthorization}
 * prepares an intent the client completes with the provider, and
 * {@link #fetchPayment} asks the provider what actually happened. Nothing here
 * accepts a client's word for it: doc 01 §14 and guardrail 3 make financial truth
 * the provider's, reconciled into ours.
 */
public interface PaymentProvider {

    /** {@code RAZORPAY} or {@code MOCK}. Persisted on the payment for tracing. */
    String name();

    /**
     * The publishable key a client needs to open the provider's checkout.
     *
     * <p>On the port rather than in configuration, deliberately: a config property
     * for "the key the client gets" is a property someone will eventually put a
     * secret in. Here the adapter decides, and the adapter is the only thing that
     * holds the secret (doc 09 §4).
     */
    String createAuthorizationPublicKey();

    /**
     * Create an intent for the client to complete.
     *
     * <p>Holds nothing yet — the money is authorised when the customer finishes
     * the provider's checkout, which we learn about from a webhook or by asking.
     */
    AuthorizationIntent createAuthorization(AuthorizationRequest request);

    /**
     * Ask the provider what state a payment is really in.
     *
     * <p>The recovery path for doc 46's "client timeout after successful payment":
     * the client vanished mid-checkout, so we ask rather than guess.
     */
    ProviderPayment fetchPayment(String providerPaymentId);

    /**
     * Take an authorised amount, up to what was authorised.
     *
     * <p>Doc 01 §14: only the accepted commercial value is captured, so this is
     * usually less than the authorisation after a partial acceptance.
     */
    ProviderPayment capture(String providerPaymentId, BigDecimal amount, String idempotencyKey);

    /**
     * Let an authorisation lapse without taking it.
     *
     * <p>Not a refund. The money was never taken, so nothing is returned — the
     * hold is simply dropped, which reaches the customer's account faster and
     * without appearing as a reversal.
     */
    ProviderPayment release(String providerPaymentId, String idempotencyKey);

    /** Return money already captured. */
    ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey);

    /**
     * Verify a webhook came from the provider. Doc 09 §5.
     *
     * <p>Takes the <b>raw body</b>, because the signature is over exact bytes and
     * a parse-and-reserialise round trip changes them.
     */
    boolean verifySignature(String rawBody, String signatureHeader);

    /** What the provider was asked to hold. */
    record AuthorizationRequest(
            String referenceId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey) {
    }

    /** What the client needs to open the provider's checkout. */
    record AuthorizationIntent(
            String providerOrderId,
            BigDecimal amount,
            String currency,
            /** Publishable key or equivalent. Never a secret — doc 09 §4. */
            String publicKey) {
    }

    /** The provider's view of a payment. Authoritative over ours. */
    record ProviderPayment(
            String providerPaymentId,
            ProviderPaymentStatus status,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            String failureCode,
            String failureReason) {
    }

    record ProviderRefund(
            String providerRefundId,
            ProviderRefundStatus status,
            BigDecimal amount,
            String failureCode,
            String failureReason) {
    }

    /** Normalised across providers, so the domain never sees a provider's vocabulary. */
    enum ProviderPaymentStatus {
        CREATED,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        RELEASED,
        REFUNDED,
    }

    enum ProviderRefundStatus {
        PENDING,
        COMPLETED,
        FAILED,
    }
}
