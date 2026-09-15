package com.costonomy.mp.delivery.web.dto;

import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
            LocationResponse location,
            boolean locationStale,
            Integer locationAgeSeconds,
            String failureCode,
            String failureReason,
            Instant requestedAt,
            Instant pickedUpAt,
            Instant deliveredAt,
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
}
