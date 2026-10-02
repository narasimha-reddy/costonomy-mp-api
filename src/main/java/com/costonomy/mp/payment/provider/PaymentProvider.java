package com.costonomy.mp.payment.provider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

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
     * The payment that completed an intent, if the customer completed it.
     *
     * <p>The recovery path when we never learned the payment id at all: the
     * customer paid and the client died before calling confirm, and the webhook
     * was lost. All we hold then is the intent, so we ask by that.
     *
     * <p>Returns only a payment holding or having taken money. A declined attempt
     * is not an answer — the customer can try again against the same intent, and
     * treating their first decline as the outcome would abandon an order they
     * then paid for.
     */
    Optional<ProviderPayment> findPaymentForOrder(String providerOrderId);

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

    /**
     * Return money already captured.
     *
     * <p>{@code options} rides along so a refund can be found again by our own
     * reference if its answer is lost, and so a cancellation can ask for speed
     * (D-109). It never changes what is refunded.
     */
    ProviderRefund refund(String providerPaymentId, BigDecimal amount, String idempotencyKey,
                          RefundOptions options);

    /**
     * Read one payment in full, straight from the provider (D-109).
     *
     * <p>What a decision about money rests on when our record may be stale or
     * missing a fact: what method paid, whether the money was taken, how much has
     * gone back. One read, no side effect.
     */
    ProviderPaymentFacts inspect(String providerPaymentId);

    /**
     * Where a refund we sent now stands.
     *
     * <p>A provider can accept a refund and finish it later — Razorpay answers
     * {@code pending} — so the first answer is not always the last (D-101).
     */
    ProviderRefund fetchRefund(String providerRefundId);

    /**
     * Verify a webhook came from the provider. Doc 09 §5.
     *
     * <p>Takes the <b>raw body</b>, because the signature is over exact bytes and
     * a parse-and-reserialise round trip changes them.
     */
    boolean verifySignature(String rawBody, String signatureHeader);

    /**
     * What the provider was asked to hold.
     *
     * @param holdMinutes how long the provider is to hold the authorisation for capture
     *                    before it returns it, or null for the provider's own configured
     *                    default. Chosen by the caller, so that what is stored on the
     *                    payment is what was sent (D-109)
     */
    record AuthorizationRequest(
            String referenceId,
            BigDecimal amount,
            String currency,
            String description,
            String idempotencyKey,
            Integer holdMinutes) {

        /** With the provider's default hold. */
        public AuthorizationRequest(String referenceId, BigDecimal amount, String currency,
                                    String description, String idempotencyKey) {
            this(referenceId, amount, currency, description, idempotencyKey, null);
        }
    }

    /**
     * What the client needs to open the provider's checkout.
     *
     * @param holdMinutes the hold the provider was told to apply, in minutes, or null when
     *                    the provider applies none of its own
     */
    record AuthorizationIntent(
            String providerOrderId,
            BigDecimal amount,
            String currency,
            /** Publishable key or equivalent. Never a secret — doc 09 §4. */
            String publicKey,
            Integer holdMinutes) {

        /** An intent with no hold recorded. */
        public AuthorizationIntent(String providerOrderId, BigDecimal amount, String currency,
                                   String publicKey) {
            this(providerOrderId, amount, currency, publicKey, null);
        }
    }

    /** The provider's view of a payment. Authoritative over ours. */
    record ProviderPayment(
            String providerPaymentId,
            /**
             * The intent this payment completed. What ties a payment id a client
             * hands us to the payment it claims to complete — without it, any
             * authorised payment id would fund any order.
             */
            String providerOrderId,
            ProviderPaymentStatus status,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            String failureCode,
            String failureReason,
            /**
             * How the customer paid, normalised and lower-case: {@code card}, {@code upi},
             * {@code netbanking}, {@code wallet} or {@code emi}; null when the provider
             * did not say. Shown on the wallet history as where a top-up came from (D-108).
             */
            String method,
            /**
             * The one safe identifying detail of that method: a card's last four digits,
             * or a provider wallet's name. Null for UPI and netbanking. Never a full card
             * number, a UPI address or a bank account, which are not needed and not ours to keep.
             */
            String methodDetail) {

        /** A payment whose method is unknown: everything an order needs, nothing more. */
        public ProviderPayment(String providerPaymentId, String providerOrderId,
                               ProviderPaymentStatus status, BigDecimal authorizedAmount,
                               BigDecimal capturedAmount, String failureCode, String failureReason) {
            this(providerPaymentId, providerOrderId, status, authorizedAmount, capturedAmount,
                    failureCode, failureReason, null, null);
        }
    }

    /**
     * Everything the provider says about one payment, read fresh (D-109).
     *
     * <p>A separate record from {@link ProviderPayment}, which is what state
     * changes are applied from and stays as narrow as it was. This is for a
     * decision that has to be made on the provider's own answer — what a
     * cancelled order's payment actually is, and what it cost — and it carries
     * what that decision needs: whether the money was taken, how much has gone
     * back, and the fee.
     *
     * @param status         the provider's status, normalised. {@code REFUNDED} whether or
     *                       not the money was ever captured: {@code captured} says which
     * @param captured       whether the money was ever taken. False for a hold that was
     *                       returned unused
     * @param amount         what was authorised, in rupees
     * @param amountRefunded what has gone back to the payer, in rupees
     * @param fee            the gateway's fee in rupees, which already includes its tax;
     *                       null until captured
     * @param createdAt      when the provider created the payment
     */
    record ProviderPaymentFacts(
            String providerPaymentId,
            String providerOrderId,
            ProviderPaymentStatus status,
            boolean captured,
            BigDecimal amount,
            BigDecimal amountRefunded,
            String method,
            String methodDetail,
            BigDecimal fee,
            java.time.Instant createdAt) {
    }

    /**
     * What a refund carries beyond its amount (D-109): how to find it again at the
     * provider, and how fast to send it.
     *
     * @param receipt our reference, at most 40 characters: what a later check
     *                matches the provider's refund against
     * @param notes   ours ids, so the provider's dashboard reads without our database
     * @param speed   {@code normal} (the default) or {@code optimum}, which sends an
     *                instant refund where it can and costs Costonomy a per-refund fee
     */
    record RefundOptions(String receipt, Map<String, String> notes, String speed) {

        /** No receipt, notes or speed: the provider's defaults. */
        public static RefundOptions none() {
            return new RefundOptions(null, Map.of(), null);
        }
    }

    record ProviderRefund(
            String providerRefundId,
            ProviderRefundStatus status,
            BigDecimal amount,
            String failureCode,
            String failureReason) {
    }

    /** Longest and shortest hold, in minutes, a provider lets an authorisation last (Razorpay's documented range). */
    int MIN_HOLD_MINUTES = 12;
    int MAX_HOLD_MINUTES = 7200;

    /** Normalised across providers, so the domain never sees a provider's vocabulary. */
    enum ProviderPaymentStatus {
        CREATED,
        AUTHORIZED,
        CAPTURED,
        FAILED,
        /**
         * The hold was returned to the payer unused: an authorisation the provider
         * gave back itself when it was not captured in time. Not {@link #REFUNDED},
         * which is money that was taken and has been given back (D-109).
         */
        RELEASED,
        REFUNDED,
    }

    enum ProviderRefundStatus {
        PENDING,
        COMPLETED,
        FAILED,
    }
}
