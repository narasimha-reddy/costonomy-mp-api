package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryEvent;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Appends to a delivery's timeline and publishes the matching domain event.
 *
 * <p>Separated from the services that cause the movements so there is one place
 * that decides what a timeline entry looks like — doc 06 §9's event list is what
 * the realtime channel and the notifications both read, and three services each
 * writing their own version of "driver assigned" is how those drift apart.
 */
@Service
@RequiredArgsConstructor
public class DeliveryTimeline {

    private final DeliveryEventRepository events;
    private final OutboxService outbox;

    /** Record something we did, rather than something a provider told us. */
    @Transactional
    public DeliveryEvent record(Delivery delivery, String eventType, DeliveryStatus status,
                                String description) {
        var event = new DeliveryEvent();
        event.setDeliveryId(delivery.getId());
        event.setEventType(eventType);
        event.setStatus(status);
        event.setProviderCode(delivery.getProviderCode());
        event.setDescription(description);
        event.setOccurredAt(Instant.now());
        events.save(event);

        publish(delivery, eventType, description);
        return event;
    }

    /**
     * Publish a delivery event for notifications and the realtime channel.
     *
     * <p>Carries no provider identity, no quote and no bid. Doc 06 §10: what
     * reaches a restaurant is the state of their delivery, not who is carrying it
     * for how much.
     */
    public void publish(Delivery delivery, String eventType, String description) {
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("deliveryId", delivery.getId());
        payload.put("supplierOrderId", delivery.getSupplierOrderId());
        payload.put("outletId", delivery.getOutletId());
        payload.put("status", delivery.getStatus().name());
        if (delivery.getEtaMinutes() != null) {
            payload.put("etaMinutes", delivery.getEtaMinutes());
        }
        if (description != null) {
            payload.put("description", description);
        }
        outbox.publish(eventType, "DELIVERY", delivery.getId(), Map.copyOf(payload), null);
    }
}
