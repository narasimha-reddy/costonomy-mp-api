package com.costonomy.mp.delivery.domain;

import java.time.Instant;

/**
 * Event published when a delivery consignment is booked with a provider.
 * Triggers one-shot assignment deadline tracking for waterfall escalation.
 */
public record DeliveryBookedEvent(
        Long deliveryId,
        Instant assignmentDeadline) {
}
