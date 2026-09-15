package com.costonomy.mp.delivery.provider;

/**
 * A provider could not be dealt with.
 *
 * <p>Nearly always recoverable by asking someone else — doc 06 §7's whole failure
 * section is "try another provider". {@code retryable} distinguishes "this
 * provider is having a bad minute" from "this provider will never take this job",
 * which decides whether a later sweep should try them again for the same delivery.
 */
public class DeliveryProviderException extends RuntimeException {

    private final String providerCode;
    private final boolean retryable;

    public DeliveryProviderException(String providerCode, String message, boolean retryable) {
        super(message);
        this.providerCode = providerCode;
        this.retryable = retryable;
    }

    public DeliveryProviderException(String providerCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
        this.retryable = true;
    }

    public String providerCode() {
        return providerCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
