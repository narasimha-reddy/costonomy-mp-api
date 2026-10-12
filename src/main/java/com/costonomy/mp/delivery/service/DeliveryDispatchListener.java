package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Automatically dispatches delivery when an order is packed and ready at the
 * warehouse or supplier kitchen. Doc 06 §6.
 *
 * <p>Only a {@link BusinessException} is swallowed. Any other failure is rethrown so the outbox retries the event
 * (and shows why); see {@link DeliveryService#autoDispatch} for why that cannot book twice.
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
            } catch (BusinessException ex) {
                // A permanent refusal (for example no delivery option configured): a retry would get the same answer.
                log.warn("Auto-dispatch for order {} refused: {}", orderId, ex.getMessage());
            }
            // Anything else propagates: the outbox retries the event and records the real cause in last_error.
            // autoDispatch runs in its own transaction, so a retry cannot book a second courier.
        }
    }
}
