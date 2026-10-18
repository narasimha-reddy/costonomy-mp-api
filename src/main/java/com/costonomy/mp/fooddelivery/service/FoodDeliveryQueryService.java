package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.service.DeliveryRecoveryService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.*;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Read-only query service for food deliveries: restaurant outlet views,
 * unauthenticated public tracking, and operator inspection (D-201).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryQueryService {

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryQuoteRepository quotes;
    private final FoodDeliveryAttemptRepository attempts;
    private final FoodDeliveryEventRepository events;
    private final FoodDeliveryLocationRepository locations;
    private final AccessControlService accessControl;
    private final DeliveryRecoveryService recoveryService;

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.FoodDeliveryResponse get(Long actorId, Long outletId, Long id) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "FoodDelivery");
        var delivery = deliveries.findById(id)
                .filter(d -> d.getOutletId().equals(outletId))
                .orElseThrow(() -> new NotFoundException("FoodDelivery", id));
        return toResponse(delivery);
    }

    @Transactional(readOnly = true)
    public List<FoodDeliveryDtos.FoodDeliverySummaryResponse> list(
            Long actorId, Long outletId, String status, int page, int size) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "FoodDelivery");
        var pageReq = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));

        return deliveries.findByOutletIdOrderByCreatedAtDesc(outletId, pageReq).stream()
                .filter(d -> status == null || d.getStatus().name().equalsIgnoreCase(status))
                .map(this::toSummaryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.PublicTrackingResponse trackByToken(String trackingToken) {
        var delivery = deliveries.findByTrackingToken(trackingToken)
                .orElseThrow(() -> new NotFoundException("TrackingToken", trackingToken));

        if (delivery.getTrackingExpiresAt() != null && delivery.getTrackingExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("TrackingToken", trackingToken);
        }

        var latestLoc = locations.findFirstByFoodDeliveryIdOrderByRecordedAtDescIdDesc(delivery.getId());
        boolean stale = false;
        Instant locTime = null;
        java.math.BigDecimal lat = null;
        java.math.BigDecimal lng = null;

        if (latestLoc.isPresent()) {
            var l = latestLoc.get();
            lat = l.getLatitude();
            lng = l.getLongitude();
            locTime = l.getRecordedAt();
            stale = locTime != null && locTime.isBefore(Instant.now().minus(Duration.ofSeconds(60)));
        }

        return new FoodDeliveryDtos.PublicTrackingResponse(
                delivery.getTrackingToken(),
                delivery.getExternalOrderRef(),
                delivery.getStatus(),
                delivery.getDriverName(),
                delivery.getDriverVehicle(),
                delivery.getEtaMinutes(),
                delivery.getEstimatedArrivalAt(),
                delivery.getPickupAddress(),
                lat,
                lng,
                stale,
                locTime,
                delivery.getDeliveredAt(),
                delivery.getCancelledAt()
        );
    }

    @Transactional(readOnly = true)
    public List<FoodDeliveryDtos.TimelineResponse> timeline(Long actorId, Long outletId, Long id) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW, ScopeType.OUTLET, outletId, "FoodDelivery");
        if (!deliveries.existsById(id)) {
            throw new NotFoundException("FoodDelivery", id);
        }
        return events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(id).stream()
                .map(e -> new FoodDeliveryDtos.TimelineResponse(
                        e.getId(),
                        e.getFoodDeliveryId(),
                        e.getEventType(),
                        e.getDescription(),
                        e.getStatus(),
                        e.getDisposition(),
                        e.getOccurredAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public FoodDeliveryDtos.FoodDeliveryInspectionResponse inspect(Long actorId, Long id) {
        accessControl.require(actorId, Permissions.DELIVERY_INSPECT, ScopeType.PLATFORM, null);
        var delivery = deliveries.findById(id)
                .orElseThrow(() -> new NotFoundException("FoodDelivery", id));

        var qList = quotes.findByFoodDeliveryIdOrderByIdAsc(id).stream()
                .map(q -> new FoodDeliveryDtos.QuoteInspection(
                        q.getId(), q.getProviderCode(), q.getStatus(), q.getAmount(),
                        q.getEtaMinutes(), q.getDistanceKm(), q.getFailureReason(),
                        q.isSelected(), q.getCreatedAt()))
                .toList();

        var aList = attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(id).stream()
                .map(a -> new FoodDeliveryDtos.AttemptInspection(
                        a.getId(), a.getAttemptNumber(), a.getAttemptType(), a.getProviderCode(),
                        a.getOutcome(), a.getQuotedAmount(), a.getRankPosition(), a.getSelectionReason(),
                        a.getFailureCode(), a.getFailureReason(), a.getEndedCause(),
                        a.getStartedAt(), a.getEndedAt()))
                .toList();

        var tList = events.findByFoodDeliveryIdOrderByOccurredAtAscIdAsc(id).stream()
                .map(e -> new FoodDeliveryDtos.TimelineResponse(
                        e.getId(), e.getFoodDeliveryId(), e.getEventType(), e.getDescription(),
                        e.getStatus(), e.getDisposition(), e.getOccurredAt()))
                .toList();

        var rList = recoveryService.getTasksForDelivery("FOOD", id).stream()
                .map(r -> new FoodDeliveryDtos.RecoveryTaskInspection(
                        r.getId(), r.getTaskType(), r.getProviderCode(), r.getProviderDeliveryId(),
                        r.getIdempotencyRef(), r.getAmount(), r.getStatus(), r.getAttempts(),
                        r.getMaxAttempts(), r.getNextRetryAt(), r.getLastError(), r.getCreatedAt()))
                .toList();

        String nextAction;
        String nextReason;
        if (delivery.isReconciliationRequired()) {
            nextAction = "OPERATOR_RECONCILIATION";
            nextReason = delivery.getReconciliationReason() != null ? delivery.getReconciliationReason() : "Reconciliation required";
        } else if (delivery.getStatus() == FoodDeliveryStatus.REQUESTED || delivery.getStatus() == FoodDeliveryStatus.QUOTED) {
            nextAction = "BOOKING_LOOP";
            nextReason = "Booking courier in rank order";
        } else if (delivery.getStatus() == FoodDeliveryStatus.PROVIDER_SELECTED) {
            nextAction = "ASSIGNMENT_SWEEP";
            nextReason = "Waiting for driver assignment deadline at " + delivery.getAssignmentDeadline();
        } else if (delivery.getStatus() == FoodDeliveryStatus.PROVIDER_UNAVAILABLE) {
            nextAction = "RETRY_JOB";
            nextReason = "Scheduled retry within retry window";
        } else if (delivery.getStatus().isInFlight()) {
            nextAction = "IN_TRANSIT_POLL";
            nextReason = "Active delivery in progress; polling/webhooks active";
        } else {
            nextAction = "TERMINAL";
            nextReason = "Delivery reached terminal status " + delivery.getStatus();
        }

        return new FoodDeliveryDtos.FoodDeliveryInspectionResponse(
                toResponse(delivery), qList, aList, tList, rList, nextAction, nextReason
        );
    }

    public FoodDeliveryDtos.FoodDeliveryResponse toResponse(FoodDelivery d) {
        return new FoodDeliveryDtos.FoodDeliveryResponse(
                d.getId(), d.getRestaurantId(), d.getOutletId(), d.getExternalOrderRef(),
                d.getStatus(), d.getPickupAddress(), d.getPickupLatitude(), d.getPickupLongitude(),
                d.getPickupContactName(), d.getPickupContactPhone(),
                d.getCustomerName(), d.getCustomerPhone(),
                d.getDropAddress(), d.getDropLandmark(), d.getDropLatitude(), d.getDropLongitude(),
                d.getDropCity(), d.getDropState(), d.getDropPincode(), d.getDropInstructions(),
                d.getOrderValue(), d.getWeightKg(),
                d.getVehicleType() != null ? d.getVehicleType().name() : null,
                d.getFee(), d.getMaxFee(), d.getFeeRefundedAmount(), d.getCurrency(),
                d.getDriverName(), d.getDriverPhone(), d.getDriverVehicle(),
                d.getEtaMinutes(), d.getEstimatedArrivalAt(),
                d.getTrackingToken(), d.getTrackingUrl(),
                d.getAttemptCount(), d.getAssignmentDeadline(),
                d.getFailureCode(), d.getFailureReason(),
                d.isReconciliationRequired(), d.getReconciliationReason(),
                d.getRequestedAt(), d.getBookedAt(), d.getAssignedAt(),
                d.getPickedUpAt(), d.getDeliveredAt(), d.getCancelledAt(),
                d.getCreatedAt()
        );
    }

    private FoodDeliveryDtos.FoodDeliverySummaryResponse toSummaryResponse(FoodDelivery d) {
        return new FoodDeliveryDtos.FoodDeliverySummaryResponse(
                d.getId(), d.getOutletId(), d.getExternalOrderRef(), d.getStatus(),
                d.getCustomerName(), d.getDropAddress(), d.getFee(), d.getCurrency(),
                d.getEtaMinutes(), d.getEstimatedArrivalAt(), d.getTrackingToken(),
                d.getRequestedAt(), d.getCreatedAt()
        );
    }
}
