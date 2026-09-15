package com.costonomy.mp.notification.domain;

/** Doc 08 §4. Email and WhatsApp are named there as future channels. */
public enum NotificationChannel {

    /**
     * The inbox. Always written, never suppressed by a preference — muting a
     * category should stop a phone buzzing, not erase the record that something
     * happened.
     */
    IN_APP,
    PUSH,
    /** Doc 08 §4: "SMS for selected critical events", and only those. */
    SMS;

    /** Whether this channel leaves the building and can therefore fail. */
    public boolean isOutbound() {
        return this != IN_APP;
    }
}
