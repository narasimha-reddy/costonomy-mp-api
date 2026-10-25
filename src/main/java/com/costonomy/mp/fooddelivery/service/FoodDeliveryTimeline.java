package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryEvent;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * What happened to a food delivery, in order. Append-only. Records what we did; the provider's own events are stored by
 * whatever ingests them. Nothing is published to notifications or realtime yet (D-197): that comes with the customer and
 * restaurant notifications.
 */
@Service
@RequiredArgsConstructor
public class FoodDeliveryTimeline {

    private final FoodDeliveryEventRepository events;
    private final FoodCourierPayableSync courierPayables;

    public FoodDeliveryEvent record(FoodDelivery delivery, FoodDeliveryStatus status, String description) {
        return record(delivery, status.eventName(), status, description);
    }

    public FoodDeliveryEvent record(FoodDelivery delivery, String eventType, FoodDeliveryStatus status, String description) {
        var event = new FoodDeliveryEvent();
        event.setFoodDeliveryId(delivery.getId());
        event.setEventType(eventType);
        event.setStatus(status);
        event.setProviderCode(delivery.getProviderCode());
        event.setDescription(description);
        event.setOccurredAt(Instant.now());
        var saved = events.save(event);
        // Whatever the status, what Costonomy owes the courier may have changed (D-204). After commit, in its own transaction.
        courierPayables.syncSoon(delivery.getId());
        return saved;
    }

    /**
     * What was refunded to the restaurant changed after the status row was written (a wallet refund made after commit):
     * bring the courier payable's funding split up to date, after commit and in its own transaction.
     */
    public void refundRecorded(FoodDelivery delivery) {
        courierPayables.syncSoon(delivery.getId());
    }
}
