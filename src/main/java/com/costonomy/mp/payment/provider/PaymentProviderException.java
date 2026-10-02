package com.costonomy.mp.payment.provider;

/**
 * The provider could not be reached, or refused an operation.
 *
 * <p>{@code retryable} separates "the provider said no" from "we could not ask".
 * A declined card must not be retried in a loop; a timeout must be. Getting this
 * backwards either hammers a provider with a request they have already refused,
 * or abandons money in an unknown state.
 */
public class PaymentProviderException extends RuntimeException {

    private final boolean retryable;
    private final String providerCode;

    public PaymentProviderException(String message, boolean retryable, String providerCode) {
        super(message);
        this.retryable = retryable;
        this.providerCode = providerCode;
    }

    private PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
        // We could not ask, so we do not know whether the provider acted. Always
        // retryable — with the same idempotency key, which is what makes it safe.
        this.retryable = true;
        this.providerCode = null;
    }

    /**
     * The provider could not be reached.
     *
     * <p>A named factory rather than a third constructor: {@code (String, boolean,
     * null)} is ambiguous between the code and cause forms, and the compiler
     * cannot tell you which one you meant.
     */
    public static PaymentProviderException unreachable(String message, Throwable cause) {
        return new PaymentProviderException(message, cause);
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * The provider answered that it does not know what was asked about (HTTP 404; the
     * mock's NOT_FOUND; and Razorpay's HTTP 400 "The id provided does not exist", which
     * the Razorpay provider reports as NOT_FOUND, because that is how it really says it). The one refusal of a lookup that says something about the
     * payment itself. Every other answer — a rate limit, refused credentials, an
     * outage, a malformed request — says something about the call, and a payment's fate
     * must never be decided by it.
     */
    public boolean isNotFound() {
        return "404".equals(providerCode) || "NOT_FOUND".equals(providerCode);
    }

    /** The provider is limiting our calls (HTTP 429). Nothing is wrong with what we asked; ask again later. */
    public boolean isRateLimited() {
        return "429".equals(providerCode) || "RATE_LIMITED".equals(providerCode);
    }

    /** The provider does not accept our credentials (HTTP 401 or 403): every call will fail until the keys are fixed. */
    public boolean isCredentialsRefused() {
        return "401".equals(providerCode) || "403".equals(providerCode);
    }

    public String providerCode() {
        return providerCode;
    }
}
