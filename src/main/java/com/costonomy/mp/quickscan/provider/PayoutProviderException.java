package com.costonomy.mp.quickscan.provider;

/**
 * The payout provider could not be reached, or refused an operation.
 *
 * <p>{@code retryable} separates "the provider said no" from "we could not ask"
 * — the same distinction {@code PaymentProviderException} draws, and for the
 * same reason: a refused payout must not be retried in a loop, and a timeout
 * must be, with the same idempotency key so a retry cannot pay twice.
 */
public class PayoutProviderException extends RuntimeException {

    private final boolean retryable;
    private final String code;

    public PayoutProviderException(String message, boolean retryable, String code) {
        super(message);
        this.retryable = retryable;
        this.code = code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public String code() {
        return code;
    }
}
