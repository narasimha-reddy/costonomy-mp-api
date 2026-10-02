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
    private final ProviderFailureKind kind;
    private final String description;

    public PaymentProviderException(String message, boolean retryable, String providerCode) {
        this(message, retryable, providerCode, null, null);
    }

    /**
     * @param kind        what the answer says about whether money moved (D-110), or null to derive
     *                    it from the code and {@code retryable}
     * @param description the provider's own wording, cut to 120 characters and stripped of
     *                    anything but printable text, for a person reading a review list; null if none
     */
    public PaymentProviderException(String message, boolean retryable, String providerCode,
                                    ProviderFailureKind kind, String description) {
        super(message);
        this.retryable = retryable;
        this.providerCode = providerCode;
        this.kind = kind;
        this.description = description;
    }

    private PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
        // We could not ask, so we do not know whether the provider acted. Always
        // retryable — with the same idempotency key, which is what makes it safe.
        this.retryable = true;
        this.providerCode = null;
        this.kind = ProviderFailureKind.AMBIGUOUS;
        this.description = null;
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

    /**
     * What this failure says about whether money moved (D-110). Explicit where the adapter read
     * the provider's answer; otherwise derived: a 429 is throttling, a 401 or 403 is
     * configuration, a not-found is an unknown payment, anything else the provider refused is
     * "other", and anything retryable is ambiguous. Ambiguity is the default for a failure that
     * says nothing, never a definite refusal.
     */
    public ProviderFailureKind kind() {
        if (kind != null) {
            return kind;
        }
        if (isRateLimited()) {
            return ProviderFailureKind.THROTTLED;
        }
        if (isCredentialsRefused()) {
            return ProviderFailureKind.CONFIG;
        }
        if (retryable) {
            return ProviderFailureKind.AMBIGUOUS;
        }
        if (isNotFound()) {
            return ProviderFailureKind.PAYMENT_UNKNOWN;
        }
        return ProviderFailureKind.REJECTED_OTHER;
    }

    /** The provider's own short wording of the refusal, safe to store and show to operations; may be null. */
    public String description() {
        return description;
    }
}
