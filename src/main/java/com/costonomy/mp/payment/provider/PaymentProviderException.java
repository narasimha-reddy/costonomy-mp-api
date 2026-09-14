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

    public String providerCode() {
        return providerCode;
    }
}
