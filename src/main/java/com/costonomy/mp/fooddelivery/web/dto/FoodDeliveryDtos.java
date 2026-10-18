package com.costonomy.mp.fooddelivery.web.dto;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FoodDeliveryDtos {

    private FoodDeliveryDtos() {}

    public record CreateFoodDeliveryRequest(
            String externalOrderRef,
            @NotBlank String customerName,
            @NotBlank String customerPhone,
            @NotBlank String dropAddress,
            String dropLandmark,
            @NotNull BigDecimal dropLatitude,
            @NotNull BigDecimal dropLongitude,
            @NotBlank String dropCity,
            @NotBlank String dropState,
            @NotBlank String dropPincode,
            String dropInstructions,
            @NotNull @Positive BigDecimal orderValue,
            @NotNull @Positive BigDecimal weightKg,
            Integer maxDeliveryMinutes,
            BigDecimal maxFee
    ) {}

    public record CancelFoodDeliveryRequest(
            String reason
    ) {}

    public record FoodDeliveryResponse(
            Long id,
            Long restaurantId,
            Long outletId,
            String externalOrderRef,
            FoodDeliveryStatus status,
            String pickupAddress,
            BigDecimal pickupLatitude,
            BigDecimal pickupLongitude,
            String pickupContactName,
            String pickupContactPhone,
            String customerName,
            String customerPhone,
            String dropAddress,
            String dropLandmark,
            BigDecimal dropLatitude,
            BigDecimal dropLongitude,
            String dropCity,
            String dropState,
            String dropPincode,
            String dropInstructions,
            BigDecimal orderValue,
            BigDecimal weightKg,
            String vehicleType,
            BigDecimal fee,
            BigDecimal maxFee,
            BigDecimal feeRefundedAmount,
            String currency,
            String driverName,
            String driverPhone,
            String driverVehicle,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            String trackingToken,
            String trackingUrl,
            int attemptCount,
            Instant assignmentDeadline,
            String failureCode,
            String failureReason,
            boolean reconciliationRequired,
            String reconciliationReason,
            Instant requestedAt,
            Instant bookedAt,
            Instant assignedAt,
            Instant pickedUpAt,
            Instant deliveredAt,
            Instant cancelledAt,
            Instant createdAt
    ) {}

    public record FoodDeliverySummaryResponse(
            Long id,
            Long outletId,
            String externalOrderRef,
            FoodDeliveryStatus status,
            String customerName,
            String dropAddress,
            BigDecimal fee,
            String currency,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            String trackingToken,
            Instant requestedAt,
            Instant createdAt
    ) {}

    public record PublicTrackingResponse(
            String trackingToken,
            String externalOrderRef,
            FoodDeliveryStatus status,
            String driverName,
            String driverVehicle,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            String pickupAddress,
            BigDecimal lastKnownLatitude,
            BigDecimal lastKnownLongitude,
            boolean locationStale,
            Instant lastLocationUpdateAt,
            Instant deliveredAt,
            Instant cancelledAt
    ) {}

    public record TimelineResponse(
            Long id,
            Long foodDeliveryId,
            String eventType,
            String description,
            FoodDeliveryStatus status,
            String disposition,
            Instant occurredAt
    ) {}

    public record QuoteInspection(
            Long id,
            String providerCode,
            String status,
            BigDecimal amount,
            Integer etaMinutes,
            BigDecimal distanceKm,
            String failureReason,
            boolean selected,
            Instant createdAt
    ) {}

    public record AttemptInspection(
            Long id,
            int attemptNumber,
            String attemptType,
            String providerCode,
            String outcome,
            BigDecimal quotedAmount,
            Integer rankPosition,
            String selectionReason,
            String failureCode,
            String failureReason,
            String endedCause,
            Instant startedAt,
            Instant endedAt
    ) {}

    public record RecoveryTaskInspection(
            Long id,
            String taskType,
            String providerCode,
            String providerDeliveryId,
            String idempotencyRef,
            BigDecimal amount,
            String status,
            int attempts,
            int maxAttempts,
            Instant nextRetryAt,
            String lastError,
            Instant createdAt
    ) {}

    public record FoodDeliveryInspectionResponse(
            FoodDeliveryResponse delivery,
            List<QuoteInspection> quotes,
            List<AttemptInspection> attempts,
            List<TimelineResponse> timeline,
            List<RecoveryTaskInspection> recoveryTasks,
            String nextAction,
            String nextActionReason
    ) {}

    public record SupplierDeliveryInspectionResponse(
            com.costonomy.mp.delivery.web.dto.DeliveryDtos.DeliveryResponse delivery,
            List<QuoteInspection> quotes,
            List<AttemptInspection> attempts,
            List<com.costonomy.mp.delivery.web.dto.DeliveryDtos.EventResponse> timeline,
            List<RecoveryTaskInspection> recoveryTasks,
            boolean reconciliationRequired,
            String reconciliationReason,
            String nextAction,
            String nextActionReason
    ) {}

    public record OperationalAlertsResponse(
            long stuckSupplierDeliveriesCount,
            long stuckFoodDeliveriesCount,
            long reconciliationRequiredCount,
            long pendingRecoveryTasksCount,
            long exhaustedRecoveryTasksCount,
            long failedRefundTasksCount,
            long failedCancellationTasksCount,
            List<StuckDeliveryAlert> stuckDeliveries
    ) {}

    public record StuckDeliveryAlert(
            String flow,
            Long deliveryId,
            Long outletId,
            String status,
            String providerCode,
            boolean reconciliationRequired,
            String reconciliationReason,
            String reason
    ) {}
}
