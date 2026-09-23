package com.costonomy.mp.delivery.web.dto;

import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class DeliveryDtos {

    private DeliveryDtos() {
    }

    public record RequestDeliveryRequest(
            /** SUPPLIER_OWN or COSTONOMY. Omit to use the supplier's configuration. */
            String mode,
            /** Minutes the restaurant needs it within, if they have a deadline. */
            Integer requiredEtaMinutes) {
    }

    public record CancelDeliveryRequest(
            @NotBlank(message = "Give a reason") @Size(max = 500) String reason) {
    }

    public record ReassignDeliveryRequest(
            @Size(max = 500) String reason) {
    }

    /**
     * What a restaurant sees. Doc 04 §14, doc 06 §8, §10, §23A.21.
     *
     * <p><b>No provider identity, no quotes, no bidding.</b> Doc 06 §4 and §10: the
     * fee is one number and who is carrying it is Mandi's business. There is
     * deliberately no `providerCode` field here — adding one would leak our supply
     * chain to everyone who places an order.
     *
     * @param driverName    null until a driver exists. Never a placeholder.
     * @param locationStale true when the newest fix is older than the configured
     *                      freshness threshold — doc 06 §8 requires the app to show
     *                      a stale state rather than an old position as if it were
     *                      current
     * @param trackable     whether a position can exist at all. False for supplier
     *                      own delivery, which has no tracking by design (doc 06 §2)
     */
    public record DeliveryResponse(
            Long id,
            Long supplierOrderId,
            String orderNumber,
            DeliveryMode mode,
            DeliveryStatus status,
            BigDecimal fee,
            String currency,
            String pickupAddress,
            String dropAddress,
            String driverName,
            String driverPhone,
            String driverVehicle,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            boolean trackable,
            String trackingUrl,
            LocationResponse location,
            boolean locationStale,
            Integer locationAgeSeconds,
            String failureCode,
            String failureReason,
            Instant requestedAt,
            Instant pickedUpAt,
            Instant deliveredAt,
            BigDecimal weightKg,
            BigDecimal volumeCbm,
            String vehicleType,
            List<EventResponse> timeline) {
    }

    public record LocationResponse(
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal bearing,
            /** The provider's timestamp for the fix, not when we stored it. */
            Instant recordedAt) {
    }

    /**
     * One entry in the timeline.
     *
     * <p>Only events that were applied. A duplicate or an out-of-order event is
     * kept in the database for diagnosis and left out of here — a restaurant does
     * not need to see a courier's retries.
     */
    public record EventResponse(
            Long id,
            String eventType,
            DeliveryStatus status,
            String description,
            Instant occurredAt) {
    }

    /** Ops-only. Doc 06 §11: simulation must not be reachable by normal users. */
    public record SimulateEventRequest(
            @NotBlank(message = "Choose a status") String status,
            @Size(max = 500) String description,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer etaMinutes) {
    }

    // ── Supplier delivery policy ─────────────────────────────────────────

    public record DeliveryPolicyResponse(
            Long supplierStoreId,
            boolean ownDeliveryEnabled,
            boolean costonomyDeliveryEnabled,
            BigDecimal ownDeliveryFee,
            /** Null means no minimum. */
            BigDecimal ownDeliveryMinOrderValue,
            /** Null means no limit beyond the platform's own serviceability. */
            BigDecimal maxDeliveryRadiusKm) {
    }

    public record UpdateDeliveryPolicyRequest(
            Boolean ownDeliveryEnabled,
            Boolean costonomyDeliveryEnabled,
            @DecimalMin(value = "0.0", message = "A delivery fee can't be negative")
            BigDecimal ownDeliveryFee,
            @DecimalMin(value = "0.0", message = "A minimum order value can't be negative")
            BigDecimal ownDeliveryMinOrderValue,
            @DecimalMin(value = "0.1", message = "A delivery radius must be at least 0.1 km")
            @DecimalMax(value = "500.0", message = "A delivery radius of more than 500 km is not a radius")
            BigDecimal maxDeliveryRadiusKm) {
    }

    public record DeliveryLedgerResponse(
            Long id,
            Long deliveryId,
            String providerCode,
            String providerDeliveryId,
            String entryType,
            BigDecimal amount,
            String currency,
            String description,
            Instant createdAt) {
    }

    /**
     * Per-provider reliability snapshot for one calendar day. Doc 06 §4 (future).
     *
     * <p>Rates are expressed as percentages (0–100) rounded to one decimal place
     * so the API consumer doesn't need to do maths.
     */
    public record ProviderStatsResponse(
            String providerCode,
            java.time.LocalDate windowDate,
            int totalBookings,
            int driverCancellations,
            int pickupFailures,
            int deliveryFailures,
            int etaOverruns,
            int completedDeliveries,
            /** Cancellation rate as a percentage, e.g. 12.5 means 12.5%. */
            double cancellationRatePct,
            /** ETA breach rate as a percentage. */
            double etaBreachRatePct,
            /** Combined failure rate as a percentage. */
            double overallFailureRatePct,
            /** Average actual minutes from DRIVER_ASSIGNED to DELIVERED. */
            Integer avgActualEtaMinutes,
            /** Average quoted ETA minutes at booking time. */
            Integer avgQuotedEtaMinutes,
            /** Average price deviation in INR (positive = provider charged more than quoted). */
            BigDecimal avgPriceDeviationInr) {
    }

    /**
     * Aggregated latency and cost metrics for a delivery provider over a 2-hour window.
     */
    public record ProviderMetricsResponse(
            String providerCode,
            Instant windowStart,
            double avgLatencyMs,
            double p95LatencyMs,
            BigDecimal totalCostInr,
            long orderCount) {
    }

    /**
     * Operational delivery summary for admin listing and missed-ETA monitoring,
     * including joined supplier and delivery provider information.
     */
    public record AdminDeliverySummaryResponse(
            Long id,
            Long supplierOrderId,
            String orderNumber,
            Long outletId,
            String outletName,
            Long supplierStoreId,
            String supplierStoreName,
            String supplierOrgName,
            String mode,
            DeliveryStatus status,
            String providerCode,
            String providerName,
            String providerDeliveryId,
            BigDecimal fee,
            String currency,
            String vehicleType,
            BigDecimal weightKg,
            String driverName,
            String driverPhone,
            String driverVehicle,
            Integer etaMinutes,
            Instant estimatedArrivalAt,
            Integer minutesOverdue,
            Instant requestedAt,
            Instant bookedAt,
            Instant assignedAt,
            Instant pickedUpAt,
            Instant deliveredAt,
            Instant cancelledAt,
            String failureCode,
            String failureReason
    ) {}

    /**
     * Paginated response wrapper for delivery listings.
     */
    public record PagedResponse<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {}
}

