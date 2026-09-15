package com.costonomy.mp.notification.provider;

import com.costonomy.mp.notification.domain.NotificationChannel;

/**
 * One way of getting a message to a person. Doc 08 §4.
 *
 * <p>One implementation per channel, selected by {@link #channel()} — the same
 * registry shape as {@code OrderFundingPort} and {@code DeliveryProvider}, and for
 * the same reason: adding email or WhatsApp (both named in doc 08 §4 as future
 * channels) should be adding a class, not editing a switch.
 *
 * <p>No provider type crosses this boundary. A push provider's error codes and an
 * SMS gateway's response envelope are the adapter's business.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** The provider's name, recorded on the delivery for reconciliation. */
    String providerName();

    /**
     * Send it.
     *
     * @param destination a push token or a phone number
     * @return the provider's id for the message, where they give one
     * @throws NotificationSendException if it did not go
     */
    String send(String destination, String title, String body);

    /** A send that failed. {@code retryable} decides whether the sweep tries again. */
    class NotificationSendException extends RuntimeException {

        private final boolean retryable;

        public NotificationSendException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
