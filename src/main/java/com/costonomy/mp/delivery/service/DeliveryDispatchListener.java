package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Automatically dispatches delivery when an order is packed and ready at the
 * warehouse or supplier kitchen. Doc 06 §6.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryDispatchListener {

    private final DeliveryService deliveryService;

    @Value("${costonomy.mp.delivery.auto-dispatch.enabled:true}")
    private boolean autoDispatchEnabled;

    @EventListener
    public void onDomainEvent(OutboxPublisher.DomainEventEnvelope envelope) {
        if (!autoDispatchEnabled) {
            return;
        }

        if ("SupplierOrderReady".equals(envelope.eventType()) && "SUPPLIER_ORDER".equals(envelope.aggregateType())) {
            Long orderId = envelope.aggregateId();
            log.info("Received SupplierOrderReady event for order {}. Triggering automated delivery dispatch.", orderId);
            try {
                deliveryService.autoDispatch(orderId);
            } catch (Exception ex) {
                log.error("Failed to auto-dispatch delivery for order {}", orderId, ex);
            }
        }
    }
}
