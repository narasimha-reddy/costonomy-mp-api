package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Event-driven waterfall cascading service.
 *
 * <p>When a driver is not assigned within the configured timeout window (3–5 minutes),
 * or when the provider signals driver unavailability, this service stands down the current
 * provider and automatically cascades to the next best quote in the waterfall.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryWaterfallService {

    private final DeliveryRepository deliveries;
    private final DeliveryService deliveryService;
    private final AuditService auditService;
    private final org.springframework.scheduling.TaskScheduler taskScheduler;

    /**
     * One-shot scheduled timer listener for newly booked deliveries.
     * Fires only once per consignment attempt when deadline expires.
     */
    @org.springframework.context.event.EventListener
    public void onDeliveryBooked(com.costonomy.mp.delivery.domain.DeliveryBookedEvent event) {
        if (taskScheduler != null && event.assignmentDeadline() != null) {
            log.info("Scheduling one-shot assignment deadline timer for delivery {} at {}",
                    event.deliveryId(), event.assignmentDeadline());
            taskScheduler.schedule(() -> cascadeUnassigned(event.deliveryId()), event.assignmentDeadline());
        }
    }

    /**
     * Trigger waterfall cascade for an unassigned delivery whose deadline expired.
     */
    @Transactional
    public boolean cascadeUnassigned(Long deliveryId) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return false;
        }

        // Only cascade if still in PROVIDER_SELECTED and deadline has actually elapsed
        if (delivery.getStatus() != DeliveryStatus.PROVIDER_SELECTED) {
            log.debug("Delivery {} is in status {}, skipping waterfall timeout cascade",
                    deliveryId, delivery.getStatus());
            return false;
        }

        if (delivery.getAssignmentDeadline() != null && delivery.getAssignmentDeadline().isAfter(Instant.now())) {
            log.debug("Delivery {} assignment deadline is still in the future", deliveryId);
            return false;
        }

        log.warn("Delivery {} timed out in PROVIDER_SELECTED (> 3 mins) with provider {}; triggering waterfall cascade",
                deliveryId, delivery.getProviderCode());

        auditService.record(null, null, "DELIVERY_WATERFALL_TIMEOUT", "DELIVERY",
                deliveryId, delivery.getStatus().name(), DeliveryStatus.PROVIDER_SELECTED.name(),
                "Driver unassigned timeout expired for provider " + delivery.getProviderCode(), "SYSTEM");

        try {
            deliveryService.reassign(null, deliveryId, "Unassigned driver timeout (waterfall cascade)");
            return true;
        } catch (Exception ex) {
            log.error("Waterfall cascade failed for delivery {}: {}", deliveryId, ex.getMessage());
            return false;
        }
    }

    /**
     * Manually force carrier escalation (e.g. from operations console).
     */
    @Transactional
    public boolean forceEscalate(Long deliveryId, String reason) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return false;
        }

        log.warn("Force escalating delivery {} with provider {}: {}", deliveryId, delivery.getProviderCode(), reason);

        auditService.record(null, null, "DELIVERY_WATERFALL_MANUAL", "DELIVERY",
                deliveryId, delivery.getStatus().name(), DeliveryStatus.PROVIDER_SELECTED.name(),
                reason, "ADMIN");

        try {
            deliveryService.reassign(null, deliveryId, reason);
            return true;
        } catch (Exception ex) {
            log.error("Manual waterfall cascade failed for delivery {}: {}", deliveryId, ex.getMessage());
            return false;
        }
    }
}

