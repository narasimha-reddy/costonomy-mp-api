package com.costonomy.mp.notification.domain;

/**
 * Doc 08 §6: {@code CREATED → QUEUED → SENT → DELIVERED}, with {@code FAILED} and
 * bounded retry.
 *
 * <p><b>SENT and DELIVERED are different facts</b>, and the gap between them is
 * where most push notifications go missing. SENT is "the provider accepted it";
 * DELIVERED is "the device acknowledged it", which only some providers report.
 * Treating acceptance as delivery would make every dashboard show perfect
 * delivery regardless of what reached a phone.
 */
public enum NotificationDeliveryStatus {

    CREATED,
    QUEUED,
    /** Handed to the provider, who accepted it. */
    SENT,
    /** The device acknowledged it, where the provider tells us. */
    DELIVERED,
    FAILED;

    public boolean isTerminal() {
        return this == DELIVERED;
    }

    /** Whether the dispatcher should pick this up. FAILED is retried until capped. */
    public boolean isPending() {
        return this == CREATED || this == QUEUED || this == FAILED;
    }
}
