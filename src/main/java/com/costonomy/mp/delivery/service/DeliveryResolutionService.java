package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * The exits of a delivery that cannot finish by itself (D-204): no courier will take it and the supplier will not carry it,
 * a courier lost the goods, a delivery failed after pickup, or a provider never reported that it arrived. Each needs a
 * decision that is neither the restaurant's nor the supplier's, and a financially correct end:
 *
 * <ul>
 *   <li><b>CANCEL_AND_REFUND</b>: the delivery and its order end together, in one transaction, and the restaurant's
 *       money goes back through the order's durable refund. The courier is stood down (retried durably if the provider is
 *       down). If the courier had the goods, what it cost is still owed to it and stays on the courier payable as a
 *       post-pickup failure; the platform bears it, since neither customer agreed to pay for a delivery that failed.</li>
 *   <li><b>MARK_DELIVERED</b>: the goods did arrive (the customer says so, the supplier's proof shows it). The delivery
 *       and the order move to delivered as a courier's event would have moved them.</li>
 * </ul>
 * Both are audited, need a reason, and are safe to repeat.
 */
@Service
@Slf4j
public class DeliveryResolutionService {

    public enum Outcome { CANCEL_AND_REFUND, MARK_DELIVERED, ASSOCIATE_CONSIGNMENT, RESUME_BOOKING }

    private record Done(DeliveryService.CancelPlan plan) {
    }

    private final DeliveryRepository deliveries;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryService deliveryService;
    private final DeliveryTimeline timeline;
    private final DeliveryOrderBridge orderBridge;
    private final AuditService auditService;
    private final OrderCancellationPort orders;
    private final DeliveryDirectory directory;
    private final AccessControlService accessControl;
    private final SupplierCourierPayableSync payableSync;
    private final TransactionTemplate tx;

    public DeliveryResolutionService(DeliveryRepository deliveries, DeliveryProviderAttemptRepository attempts,
                                     DeliveryService deliveryService, DeliveryTimeline timeline,
                                     DeliveryOrderBridge orderBridge, AuditService auditService,
                                     OrderCancellationPort orders, DeliveryDirectory directory, AccessControlService accessControl,
                                     SupplierCourierPayableSync payableSync, PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.deliveryService = deliveryService;
        this.timeline = timeline;
        this.orderBridge = orderBridge;
        this.auditService = auditService;
        this.orders = orders;
        this.directory = directory;
        this.accessControl = accessControl;
        this.payableSync = payableSync;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public DeliveryDtos.DeliveryResponse resolve(Long actorId, Long deliveryId, Outcome outcome, String reason) {
        return resolve(actorId, deliveryId, outcome, reason, null);
    }

    public DeliveryDtos.DeliveryResponse resolve(Long actorId, Long deliveryId, Outcome outcome, String reason, String providerDeliveryId) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say why this delivery is being closed or reconciled.");
        }
        Done done = tx.execute(s -> switch (outcome) {
            case CANCEL_AND_REFUND -> cancelAndRefund(actorId, deliveryId, reason);
            case MARK_DELIVERED -> markDelivered(actorId, deliveryId, reason);
            case ASSOCIATE_CONSIGNMENT -> associateConsignment(actorId, deliveryId, providerDeliveryId, reason);
            case RESUME_BOOKING -> resumeBooking(actorId, deliveryId, reason);
        });
        // The provider is told outside the transaction; a failure is retried by the recovery task it schedules.
        if (done != null && done.plan() != null) {
            deliveryService.cancelAtProvider(done.plan());
        }
        if (outcome == Outcome.RESUME_BOOKING) {
            try {
                deliveryService.reassign(actorId, deliveryId, reason);
            } catch (Exception ex) {
                log.warn("Reassigning delivery {} after reconciliation failed: {}", deliveryId, ex.getMessage());
            }
        }
        payableSync.syncSoon(deliveryId);
        return tx.execute(s -> deliveryService.toResponse(
                deliveries.findById(deliveryId).orElseThrow(), null));
    }

    /**
     * A pickup order the restaurant never collected (D-204 audit, gap 17). A pickup order has no delivery row, so
     * {@link #resolve} cannot reach it, and the order cannot be cancelled by either side once it is ready. No timeout
     * ends it automatically (an automatic cancel could discard packed, perishable goods; that is a product decision),
     * so this is the operator's exit: the order is cancelled and its money returned, on a reason, audited, idempotent.
     */
    public void closeUncollectedPickup(Long actorId, Long supplierOrderId, String reason) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say why this order is being closed.");
        }
        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        if ("CANCELLED".equals(order.status())) {
            return;
        }
        if (!"PICKUP".equals(order.deliveryMode())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order is not a pickup order. Use the delivery's resolve instead.");
        }
        if (!"READY_FOR_PICKUP".equals(order.status())) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a pickup order waiting to be collected can be closed here; this one is " + order.status() + ".");
        }
        if (deliveries.findBySupplierOrderId(supplierOrderId).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order has a delivery. Use the delivery's resolve instead.");
        }
        tx.executeWithoutResult(s -> {
            orders.cancelForOperations(actorId, supplierOrderId, reason);
            auditService.record(actorId, null, "PICKUP_ORDER_CLOSED_BY_OPERATIONS", "SUPPLIER_ORDER", supplierOrderId,
                    "READY_FOR_PICKUP", "CANCELLED", "Uncollected pickup cancelled and refunded: " + reason, "ADMIN");
        });
    }

    private Done associateConsignment(Long actorId, Long deliveryId, String providerDeliveryId, String reason) {
        if (providerDeliveryId == null || providerDeliveryId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Provide the discovered provider consignment ID.");
        }
        var d = deliveries.lockById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        if (d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery is already %s; cannot associate consignment.".formatted(d.getStatus()));
        }
        if (providerDeliveryId.equals(d.getProviderDeliveryId()) && !d.isReconciliationRequired()) {
            return new Done(null);
        }
        var from = d.getStatus();
        d.setProviderDeliveryId(providerDeliveryId);
        d.setReconciliationRequired(false);
        d.setReconciliationReason(null);
        d.setFailureCode(null);
        d.setFailureReason(null);
        if (d.getStatus() == DeliveryStatus.PROVIDER_SELECTED || d.getStatus() == DeliveryStatus.PROVIDER_UNAVAILABLE) {
            d.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            if (d.getAssignedAt() == null) {
                d.setAssignedAt(Instant.now());
            }
        }
        deliveries.save(d);

        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(deliveryId).ifPresent(a -> {
            a.setOutcome("BOOKED");
            a.setProviderDeliveryId(providerDeliveryId);
            a.setEndedAt(null);
            a.setEndedCause(null);
            a.setFailureCode(null);
            a.setFailureReason(null);
            attempts.save(a);
        });

        timeline.record(d, "ReconciliationResolved", d.getStatus(), "Discovered booking associated: " + providerDeliveryId + " · " + reason);
        auditService.record(actorId, null, "DELIVERY_RECONCILED", "DELIVERY", deliveryId,
                from.name(), d.getStatus().name(), "Consignment associated: " + providerDeliveryId + " (" + reason + ")", "ADMIN");
        return new Done(null);
    }

    private Done resumeBooking(Long actorId, Long deliveryId, String reason) {
        var d = deliveries.lockById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        if (!d.isReconciliationRequired()) {
            return new Done(null);
        }
        d.setReconciliationRequired(false);
        d.setReconciliationReason(null);
        d.setFailureCode(null);
        d.setFailureReason(null);
        deliveries.save(d);

        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(deliveryId).ifPresent(a -> {
            if ("FAILED".equals(a.getOutcome())) {
                a.setEndedCause("CONFIRMED_NOT_BOOKED");
                attempts.save(a);
            }
        });

        timeline.record(d, "ReconciliationResolved", d.getStatus(), "Booking confirmed non-existent; resuming search · " + reason);
        auditService.record(actorId, null, "DELIVERY_RECONCILED", "DELIVERY", deliveryId,
                d.getStatus().name(), d.getStatus().name(), "Resumed booking: " + reason, "ADMIN");
        return new Done(null);
    }

    private Done cancelAndRefund(Long actorId, Long deliveryId, String reason) {
        var d = deliveries.lockById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        if (d.getStatus() == DeliveryStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery was delivered; there is nothing to cancel.");
        }
        if (d.getStatus() == DeliveryStatus.CANCELLED) {
            // A repeat, or a first try that stopped before the order: finish the order.
            orders.cancelForOperations(actorId, d.getSupplierOrderId(), reason);
            return new Done(null);
        }
        if (attempts.existsByDeliveryIdAndOutcome(deliveryId, "PENDING")) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "A courier is being booked right now; wait for the outcome, then close it.");
        }
        var from = d.getStatus();
        boolean hadTheGoods = d.getPickedUpAt() != null || from.isInFlight() || from == DeliveryStatus.DELIVERY_FAILED;
        // The courier that had the goods ran, whatever came of them: its cost is still owed (D-204).
        var plan = deliveryService.releaseCurrentProviderInDb(d, "Cancelled by operations: " + reason,
                hadTheGoods ? "DELIVERY_FAILED" : "OPS_CANCEL");
        d.setStatus(DeliveryStatus.CANCELLED);
        d.setCancelledAt(Instant.now());
        d.setFailureReason("Cancelled by operations: " + reason);
        d.setReconciliationRequired(false);
        d.setReconciliationReason(null);
        deliveries.save(d);
        timeline.record(d, "DeliveryCancelledByOperations", DeliveryStatus.CANCELLED, reason);
        auditService.record(actorId, null, "DELIVERY_CLOSED_BY_OPERATIONS", "DELIVERY", deliveryId,
                from.name(), DeliveryStatus.CANCELLED.name(), "Cancelled and refunded: " + reason, "ADMIN");
        orders.cancelForOperations(actorId, d.getSupplierOrderId(), reason);
        return new Done(plan);
    }

    private Done markDelivered(Long actorId, Long deliveryId, String reason) {
        var d = deliveries.lockById(deliveryId).orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        if (d.getStatus() == DeliveryStatus.DELIVERED) {
            return new Done(null);
        }
        if (!d.getStatus().canTransitionTo(DeliveryStatus.DELIVERED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A delivery that is %s cannot be marked delivered. Only one the courier had the goods for can."
                            .formatted(d.getStatus()));
        }
        var from = d.getStatus();
        var now = Instant.now();
        if (d.getPickedUpAt() == null) {
            d.setPickedUpAt(now);
        }
        d.setStatus(DeliveryStatus.DELIVERED);
        d.setDeliveredAt(now);
        d.setReconciliationRequired(false);
        d.setReconciliationReason(null);
        deliveries.save(d);
        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(deliveryId).ifPresent(a -> {
            if ("BOOKED".equals(a.getOutcome())) {
                a.setOutcome("COMPLETED");
                a.setEndedCause("COMPLETED");
                a.setEndedAt(now);
                attempts.save(a);
            }
        });
        timeline.record(d, "DeliveryMarkedDeliveredByOperations", DeliveryStatus.DELIVERED, reason);
        auditService.record(actorId, null, "DELIVERY_CLOSED_BY_OPERATIONS", "DELIVERY", deliveryId,
                from.name(), DeliveryStatus.DELIVERED.name(), "Marked delivered: " + reason, "ADMIN");
        // The order moves as a courier's events would have moved it, and a funded share is charged as at pickup.
        orderBridge.onDeliveryStatus(d, DeliveryStatus.PICKED_UP);
        orderBridge.onDeliveryStatus(d, DeliveryStatus.DELIVERED);
        return new Done(null);
    }
}
