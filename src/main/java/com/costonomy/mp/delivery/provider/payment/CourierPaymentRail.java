package com.costonomy.mp.delivery.provider.payment;

import java.math.BigDecimal;

/**
 * How Costonomy pays a delivery provider (D-204). A port, like the other providers: nothing outside it knows whether the
 * money moves by an API, a bank transfer a person makes, or an invoice.
 *
 * <p>An automatic rail sends the payment itself and is asked again, by the same key, when an answer is lost. A manual
 * rail never sends anything: a person pays outside the system and records it ({@link #automatic()} is false).
 */
public interface CourierPaymentRail {

    String code();

    /** True if the system itself can pay through this rail; false if a person pays and records it. */
    boolean automatic();

    /**
     * Pay. Must be safe to repeat under one {@code idempotencyKey}: asking twice moves the money once. Returning
     * {@link Outcome#AMBIGUOUS} (or throwing) means the answer is not known, and the payment is then asked about, never
     * sent again under a new key.
     */
    PayResult pay(PayRequest request);

    /** What became of a payment sent under this key. Used after an ambiguous answer. */
    LookupResult lookup(String idempotencyKey);

    record PayRequest(Long payableId, String providerCode, String providerDeliveryId, BigDecimal amount,
                      String currency, String idempotencyKey, String narrative) {
    }

    enum Outcome { PAID, FAILED, AMBIGUOUS }

    record PayResult(Outcome outcome, String reference, String error) {
        public static PayResult paid(String reference) {
            return new PayResult(Outcome.PAID, reference, null);
        }

        public static PayResult failed(String error) {
            return new PayResult(Outcome.FAILED, null, error);
        }

        public static PayResult ambiguous(String error) {
            return new PayResult(Outcome.AMBIGUOUS, null, error);
        }
    }

    enum Found { PAID, NOT_FOUND, UNKNOWN }

    record LookupResult(Found found, String reference) {
        public static LookupResult paid(String reference) {
            return new LookupResult(Found.PAID, reference);
        }

        public static LookupResult notFound() {
            return new LookupResult(Found.NOT_FOUND, null);
        }

        public static LookupResult unknown() {
            return new LookupResult(Found.UNKNOWN, null);
        }
    }
}
