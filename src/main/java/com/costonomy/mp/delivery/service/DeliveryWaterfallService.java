package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    // Not transactional (D-134): the audit row is written in its own transaction before the reassignment, and a
    // reassignment that fails (no carrier qualifies, say) marks only its own transaction for rollback. Held in one
    // outer transaction, that failure surfaced as UnexpectedRollbackException and took the audit row with it.
    public boolean cascadeUnassigned(Long deliveryId) {
        var delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            return false;
        }

        // A cheap look to skip the obvious. It is not the decision: DeliveryService re-checks the status, the deadline
        // and the order under the row lock, so a driver assigned after this look is not released.
        if (delivery.getStatus() != DeliveryStatus.PROVIDER_SELECTED) {
            log.debug("Delivery {} is in status {}, skipping waterfall timeout cascade",
                    deliveryId, delivery.getStatus());
            return false;
        }
        if (delivery.getAssignmentDeadline() != null && delivery.getAssignmentDeadline().isAfter(Instant.now())) {
            log.debug("Delivery {} assignment deadline is still in the future", deliveryId);
            return false;
        }

        try {
            boolean moved = deliveryService.reassignIfAssignmentExpired(deliveryId,
                    "Unassigned driver timeout (waterfall cascade)");
            if (moved) {
                log.warn("Delivery {} timed out waiting for a driver with provider {}; booked another courier",
                        deliveryId, delivery.getProviderCode());
            }
            return moved;
        } catch (Exception ex) {
            log.error("Waterfall cascade failed for delivery {}: {}", deliveryId, ex.getMessage());
            return false;
        }
    }

    /**
     * Manually force carrier escalation (from the operations console). Not transactional, like the timeout cascade:
     * the audit rows are written in transactions of their own, so a failed reassignment cannot take its own audit row
     * down with it, and the failure is thrown to the operator rather than swallowed (D-204 audit, gap 9).
     *
     * @param actorId the operator; recorded on every audit row
     * @throws com.costonomy.mp.common.error.BusinessException if the delivery could not be given to another courier
     */
    public void forceEscalate(Long actorId, Long deliveryId, String reason) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new com.costonomy.mp.common.error.NotFoundException("Delivery", deliveryId));
        String before = delivery.getStatus().name();

        log.warn("Operator {} forcing carrier escalation of delivery {} with provider {}: {}", actorId, deliveryId,
                delivery.getProviderCode(), reason);
        try {
            deliveryService.reassign(null, deliveryId, reason);
        } catch (RuntimeException ex) {
            auditService.record(actorId, null, "DELIVERY_WATERFALL_MANUAL_FAILED", "DELIVERY", deliveryId, before, before,
                    reason + " · " + ex.getMessage(), "ADMIN");
            log.error("Manual waterfall cascade failed for delivery {}: {}", deliveryId, ex.getMessage());
            throw ex;
        }
        var after = deliveries.findById(deliveryId).map(d -> d.getStatus().name()).orElse(before);
        auditService.record(actorId, null, "DELIVERY_WATERFALL_MANUAL", "DELIVERY", deliveryId, before, after,
                reason, "ADMIN");
    }
}
