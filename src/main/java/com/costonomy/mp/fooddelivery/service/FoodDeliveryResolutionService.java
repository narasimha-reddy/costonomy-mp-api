package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.service.DeliveryRecoveryService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * An operator's way out for a food delivery that cannot finish by itself, or whose flag nobody else can clear (D-204
 * audit, gaps 7 and 14). The counterpart of {@code DeliveryResolutionService} for supplier deliveries.
 *
 * <ul>
 *   <li>{@link Outcome#CANCEL_AND_REFUND}: before pickup, cancel (the courier is stood down and the hold returned);
 *       after a failure at hand-over, close as returned and give the restaurant its fee back, which makes the courier's
 *       cost Costonomy's. The one exit where the documented rule (a failure after pickup is the restaurant's cost) is
 *       overridden, and only by a person.</li>
 *   <li>{@link Outcome#MARK_DELIVERED}: the food did arrive. Only where a courier had it.</li>
 *   <li>{@link Outcome#CLEAR_FLAG}: a person has looked, and nothing more needs doing.</li>
 * </ul>
 *
 * <p>Permission DELIVERY_OPERATE at platform scope, a reason always, an audit row always, idempotent. The refund and the
 * courier calls it triggers are made outside any database transaction, by the services that own them.
 */
@Service
@Slf4j
public class FoodDeliveryResolutionService {

    public enum Outcome { CANCEL_AND_REFUND, MARK_DELIVERED, CLEAR_FLAG }

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryBookingService booking;
    private final FoodDeliveryTimeline timeline;
    private final FoodDeliveryQueryService query;
    private final DeliveryRecoveryService recovery;
    private final AccessControlService accessControl;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public FoodDeliveryResolutionService(FoodDeliveryRepository deliveries, FoodDeliveryBookingService booking,
                                         FoodDeliveryTimeline timeline, FoodDeliveryQueryService query,
                                         DeliveryRecoveryService recovery, AccessControlService accessControl,
                                         AuditService audit, PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.booking = booking;
        this.timeline = timeline;
        this.query = query;
        this.recovery = recovery;
        this.accessControl = accessControl;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    public com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos.FoodDeliveryResponse resolve(
            Long actorId, Long id, Outcome outcome, String reason) {
        accessControl.require(actorId, Permissions.DELIVERY_OPERATE, ScopeType.PLATFORM, null);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Say why.");
        }
        String why = reason.trim();
        var before = deliveries.findById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
        String from = before.getStatus().name();

        FoodDelivery after = switch (outcome) {
            case CANCEL_AND_REFUND -> cancelAndRefund(before, why);
            case MARK_DELIVERED -> markDelivered(id, why);
            case CLEAR_FLAG -> clearFlag(id, why);
        };

        audit.record(actorId, null, "FOOD_DELIVERY_" + outcome.name() + "_BY_OPERATIONS", "FOOD_DELIVERY", id, from,
                after.getStatus().name(), why, "ADMIN");
        return query.toResponse(after);
    }

    private FoodDelivery cancelAndRefund(FoodDelivery d, String why) {
        if (d.getStatus() == FoodDeliveryStatus.CANCELLED || d.getStatus() == FoodDeliveryStatus.RETURNED) {
            clearIfFlagged(d.getId(), why);
            return deliveries.findById(d.getId()).orElseThrow();
        }
        if (d.getStatus() == FoodDeliveryStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "This delivery was delivered.");
        }
        if (d.getStatus().isCancellable()) {
            var cancelled = booking.cancel(d.getId(), "Cancelled by operations: " + why);
            clearIfFlagged(d.getId(), why);
            return deliveries.findById(cancelled.getId()).orElseThrow();
        }
        if (d.getStatus() == FoodDeliveryStatus.FAILED && d.getPickedUpAt() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "No courier took this delivery and its fee was already returned. Clear the flag if it still shows.");
        }
        if (d.getStatus() != FoodDeliveryStatus.FAILED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The food is with a courier. Wait for the courier's outcome, or mark it delivered.");
        }
        return booking.returnAndRefund(d.getId(), "Cancelled by operations: " + why);
    }

    private FoodDelivery markDelivered(Long id, String why) {
        boolean newlyDelivered = Boolean.TRUE.equals(tx.execute(status -> {
            var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
            if (d.getStatus() == FoodDeliveryStatus.DELIVERED) {
                return false;
            }
            boolean courierHadIt = d.getPickedUpAt() != null || d.getStatus().isInFlight();
            if (!courierHadIt || !d.getStatus().canTransitionTo(FoodDeliveryStatus.DELIVERED)) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "No courier had the food, so it cannot be marked delivered from " + d.getStatus() + ".");
            }
            Instant now = Instant.now();
            d.moveTo(FoodDeliveryStatus.DELIVERED);
            d.setDeliveredAt(now);
            d.setFailureCode(null);
            d.setFailureReason(null);
            d.setReconciliationRequired(false);
            d.setReconciliationReason(null);
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
            deliveries.save(d);
            timeline.record(d, FoodDeliveryStatus.DELIVERED, "Marked delivered by operations: " + why);
            return true;
        }));
        if (newlyDelivered) {
            try {
                booking.settleFee(id);       // idempotent; returns the unused part of the hold if pickup was never reported
            } catch (Exception ex) {
                log.error("Fee settlement after marking food delivery {} delivered failed: {}", id, ex.getMessage(), ex);
                recovery.markReconciliationRequired("FOOD", id, "Fee settlement failed after delivery: " + ex.getMessage());
            }
        }
        return deliveries.findById(id).orElseThrow();
    }

    private FoodDelivery clearFlag(Long id, String why) {
        tx.executeWithoutResult(status -> {
            var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
            if (!d.isReconciliationRequired()) {
                return;
            }
            d.setReconciliationRequired(false);
            d.setReconciliationReason(null);
            deliveries.save(d);
            timeline.record(d, "ReconciliationResolved", d.getStatus(), "Cleared by operations: " + why);
        });
        return deliveries.findById(id).orElseThrow();
    }

    private void clearIfFlagged(Long id, String why) {
        clearFlag(id, why);
    }
}
