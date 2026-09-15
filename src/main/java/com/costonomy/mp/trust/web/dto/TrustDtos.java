package com.costonomy.mp.trust.web.dto;

import com.costonomy.mp.trust.domain.DisputeStatus;
import com.costonomy.mp.trust.domain.RatingModerationStatus;
import com.costonomy.mp.trust.domain.ReceivingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TrustDtos {

    private TrustDtos() {
    }

    // ── Receiving ────────────────────────────────────────────────────────

    public record ReceiveRequest(
            @NotEmpty(message = "Check in every line") @Valid List<ReceiveItemRequest> items,
            @Size(max = 1000) String notes) {
    }

    /**
     * One line, checked in.
     *
     * <p>All three quantities are required and must add up to what was accepted.
     * Defaulting the missing ones would let a tap-through record a perfect delivery
     * that nobody actually counted — which is precisely the blind "Complete" button
     * §23A.22 forbids.
     */
    public record ReceiveItemRequest(
            @NotNull(message = "Which line?") Long supplierOrderItemId,
            @NotNull @DecimalMin(value = "0.00") BigDecimal receivedQuantity,
            @NotNull @DecimalMin(value = "0.00") BigDecimal damagedQuantity,
            @NotNull @DecimalMin(value = "0.00") BigDecimal missingQuantity,
            @Size(max = 500) String note) {
    }

    public record ReceivingResponse(
            Long id,
            Long supplierOrderId,
            String orderNumber,
            ReceivingStatus status,
            boolean hasDiscrepancy,
            BigDecimal totalAcceptedQuantity,
            BigDecimal totalReceivedQuantity,
            BigDecimal totalDamagedQuantity,
            BigDecimal totalMissingQuantity,
            String notes,
            Instant receivedAt,
            List<ReceivingItemResponse> items) {
    }

    public record ReceivingItemResponse(
            Long id,
            Long supplierOrderItemId,
            String productName,
            BigDecimal requestedQuantity,
            BigDecimal acceptedQuantity,
            BigDecimal receivedQuantity,
            BigDecimal damagedQuantity,
            BigDecimal missingQuantity,
            String unit,
            String note) {
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    public record CreateDisputeRequest(
            @NotBlank(message = "Choose a category") String category,
            @NotBlank(message = "Describe what went wrong") @Size(max = 2000) String description,
            @DecimalMin(value = "0.00") BigDecimal claimedAmount,
            @Valid List<DisputeItemRequest> items,
            @Valid List<EvidenceRequest> evidence) {
    }

    public record DisputeItemRequest(
            @NotNull Long supplierOrderItemId,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal disputedQuantity,
            @Size(max = 500) String reason) {
    }

    public record EvidenceRequest(
            @NotBlank String evidenceType,
            @NotBlank @Size(max = 1000) String reference,
            @Size(max = 500) String caption) {
    }

    public record RespondToDisputeRequest(
            @NotBlank(message = "Write a response") @Size(max = 2000) String message,
            /** Optional: a supplier may answer without closing anything. */
            String resolutionType,
            @Size(max = 2000) String resolution,
            @Valid List<EvidenceRequest> evidence) {
    }

    public record ResolveDisputeRequest(
            @NotBlank(message = "Say how it was resolved") String resolutionType,
            @Size(max = 2000) String resolution,
            @Size(max = 2000) String message) {
    }

    public record DisputeMessageRequest(
            @NotBlank @Size(max = 2000) String message,
            @Valid List<EvidenceRequest> evidence) {
    }

    /**
     * @param supplierOrderStatus carried deliberately. §23A.26: the app must state
     *                            that a dispute does not change the order's status,
     *                            and it can only do that if it is told what that
     *                            status still is.
     */
    public record DisputeResponse(
            Long id,
            String disputeNumber,
            Long supplierOrderId,
            String orderNumber,
            String supplierOrderStatus,
            Long outletId,
            Long supplierStoreId,
            String category,
            DisputeStatus status,
            String description,
            BigDecimal claimedAmount,
            String resolution,
            String resolutionType,
            Instant respondedAt,
            Instant resolvedAt,
            Instant createdAt,
            List<DisputeItemResponse> items,
            List<DisputeMessageResponse> messages,
            List<EvidenceResponse> evidence) {
    }

    public record DisputeItemResponse(
            Long id,
            Long supplierOrderItemId,
            String productName,
            BigDecimal disputedQuantity,
            String reason) {
    }

    public record DisputeMessageResponse(
            Long id,
            String authorSide,
            String message,
            Instant createdAt) {
    }

    public record EvidenceResponse(
            Long id,
            String evidenceType,
            String reference,
            String caption,
            Instant createdAt) {
    }

    // ── Ratings ──────────────────────────────────────────────────────────

    public record CreateRatingRequest(
            @NotNull(message = "Give an overall rating")
            @Min(value = 1, message = "Rate between 1 and 5")
            @Max(value = 5, message = "Rate between 1 and 5") Integer overall,
            @Min(1) @Max(5) Integer productQuality,
            @Min(1) @Max(5) Integer quantityAccuracy,
            @Min(1) @Max(5) Integer packaging,
            @Min(1) @Max(5) Integer delivery,
            @Size(max = 2000) String comment) {
    }

    public record ModerateRatingRequest(
            @NotBlank(message = "Say why") @Size(max = 500) String reason,
            /** true hides it, false restores it. */
            @NotNull Boolean hide) {
    }

    public record RatingResponse(
            Long id,
            Long supplierOrderId,
            Long supplierStoreId,
            Integer overall,
            Integer productQuality,
            Integer quantityAccuracy,
            Integer packaging,
            Integer delivery,
            String comment,
            RatingModerationStatus moderationStatus,
            Instant createdAt) {
    }

    /**
     * A store's public rating.
     *
     * <p>Every figure is computed from {@code PUBLISHED} ratings only, so hiding
     * one removes it from the average and from ranking — which is what makes
     * moderation mean anything.
     *
     * @param averageOverall null when nobody has rated this store. Doc 07 §4: an
     *                       absent signal is absent, never a default of three.
     */
    public record RatingSummaryResponse(
            Long supplierStoreId,
            int ratingCount,
            BigDecimal averageOverall,
            BigDecimal averageProductQuality,
            BigDecimal averageQuantityAccuracy,
            BigDecimal averagePackaging,
            BigDecimal averageDelivery,
            List<RatingResponse> recent) {
    }
}
