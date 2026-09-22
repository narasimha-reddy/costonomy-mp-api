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
}
