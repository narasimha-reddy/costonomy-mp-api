package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.service.DeliveryRecoveryService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryEvent;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryLocation;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryEventRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryLocationRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Ingests courier events for food deliveries, advances the status monotonically,
 * settles fees at pickup, and triggers rebooking when a rider cancels (D-197).
 */
@Service
@Slf4j
public class FoodDeliveryEventService {

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryEventRepository events;
    private final FoodDeliveryLocationRepository locations;
    private final FoodDeliveryTimeline timeline;
    private final FoodDeliveryBookingService bookingService;
    private final DeliveryRecoveryService recoveryService;
    private final ObjectMapper json;
    private final com.costonomy.mp.common.audit.AuditService audit;
    private final TransactionTemplate tx;

    public FoodDeliveryEventService(FoodDeliveryRepository deliveries,
                                   FoodDeliveryEventRepository events,
                                   FoodDeliveryLocationRepository locations,
                                   FoodDeliveryTimeline timeline,
                                   @Lazy FoodDeliveryBookingService bookingService,
                                   @Lazy DeliveryRecoveryService recoveryService,
                                   ObjectMapper json,
                                   com.costonomy.mp.common.audit.AuditService audit,
                                   PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.events = events;
        this.locations = locations;
        this.timeline = timeline;
        this.bookingService = bookingService;
        this.recoveryService = recoveryService;
        this.json = json;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record ApplyResult(
            String disposition,
            boolean needsRebook,
            String rebookCause,
            String rebookReason,
            String failedProviderDeliveryId,
            boolean needsSettleFee,
            Long deliveryId,
            boolean failedAfterPickup
    ) {}

    /**
     * Apply one provider event to the food delivery.
     * Deduplicates by providerEventId and protects against out-of-order state transitions.
     * State transition commits first before fee settlement or rebooking are executed outside the lock.
     *
     * @return disposition: APPLIED, DUPLICATE, OUT_OF_ORDER, or IGNORED
     */
    public String apply(FoodDelivery delivery, String providerCode, String providerDeliveryId,
                        DeliveryProvider.ProviderEvent event, JsonNode rawPayload) {
        if (event == null || event.status() == null || delivery == null) {
            return "IGNORED";
        }

        String eventId = event.providerEventId();
        if (eventId != null && events.existsByProviderCodeAndProviderEventId(providerCode, eventId)) {
            log.debug("Duplicate provider event {} from {} for food delivery {}", eventId, providerCode, delivery.getId());
            return "DUPLICATE";
        }

        ApplyResult stepResult;
        try {
            stepResult = tx.execute(status -> processEventInTransaction(
                    delivery, providerCode, providerDeliveryId, event, rawPayload));
        } catch (DataIntegrityViolationException dive) {
            log.debug("Duplicate provider event constraint violation for {}/{}: {}", providerCode, eventId, dive.getMessage());
            return "DUPLICATE";
        }

        if (stepResult == null) {
            return "IGNORED";
        }

        // Follow-ups run outside the database transaction and row locks
        if (stepResult.needsRebook()) {
            try {
                bookingService.rebook(stepResult.deliveryId(), stepResult.failedProviderDeliveryId(),
                        stepResult.rebookCause(), stepResult.rebookReason());
            } catch (Exception ex) {
                log.error("Rebooking after {} failed for food delivery {}: {}",
                        stepResult.rebookCause(), stepResult.deliveryId(), ex.getMessage(), ex);
                recoveryService.markReconciliationRequired("FOOD", stepResult.deliveryId(),
                        "Rebooking failed after " + stepResult.rebookCause() + ": " + ex.getMessage());
            }
        }

        if (stepResult.needsSettleFee()) {
            try {
                bookingService.settleFee(stepResult.deliveryId());
            } catch (Exception ex) {
                log.error("Fee settlement on pickup failed for food delivery {}: {}",
                        stepResult.deliveryId(), ex.getMessage(), ex);
                recoveryService.markReconciliationRequired("FOOD", stepResult.deliveryId(),
                        "Fee settlement failed on pickup: " + ex.getMessage());
            }
        }

        if (stepResult.failedAfterPickup()) {
            // D-204: the courier had the food, so the restaurant keeps paying the fee it agreed and nothing is refunded
            // here. The delivery is flagged for operations, who can close it (cancel with a refund, mark delivered, or
            // clear the flag with a note) once they know what happened to the food.
            log.warn("Food delivery {} failed after pickup; flagged for operations, fee kept, courier still owed",
                    stepResult.deliveryId());
        }

        return stepResult.disposition();
    }

    public String apply(FoodDelivery delivery, DeliveryProvider.ProviderEvent event,
                        String providerCode, JsonNode rawPayload) {
        return apply(delivery, providerCode, delivery != null ? delivery.getProviderDeliveryId() : null, event, rawPayload);
    }

    private ApplyResult processEventInTransaction(FoodDelivery delivery, String providerCode, String providerDeliveryId,
                                                  DeliveryProvider.ProviderEvent event, JsonNode rawPayload) {
        var d = deliveries.lockById(delivery.getId()).orElse(delivery);
        Instant now = Instant.now();
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : now;
        String eventId = event.providerEventId();
        var pStatus = event.status();
        String payloadStr = rawPayload != null ? rawPayload.toString() : null;

        // Verify active provider consignment: reject stale events from replaced consignments
        if (isStaleConsignment(d, providerCode, providerDeliveryId)) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "IGNORED",
                    "Stale event from replaced provider consignment: " + providerDeliveryId, occurredAt, payloadStr);
            log.info("Ignoring stale event {} from provider {} consignment {} for food delivery {} (active is {} / {})",
                    eventId, providerCode, providerDeliveryId, d.getId(), d.getProviderCode(), d.getProviderDeliveryId());
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId(), false);
        }

        d.setLastProviderUpdateAt(now);

        // Echo of already cancelled delivery
        if (d.getStatus() == FoodDeliveryStatus.CANCELLED) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "APPLIED",
                    event.description() != null ? event.description() : "Echo of cancellation", occurredAt, payloadStr);
            deliveries.save(d);
            return new ApplyResult("APPLIED", false, null, null, null, false, d.getId(), false);
        }

        // Rider cancellation, pickup failure, or pre-pickup failure/cancellation: trigger stand-down and rebook
        boolean isPrePickup = !d.getStatus().isInFlight() && d.getPickedUpAt() == null;
        if (isPrePickup && (pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                || pStatus == DeliveryProvider.ProviderDeliveryStatus.PICKUP_FAILED
                || pStatus == DeliveryProvider.ProviderDeliveryStatus.CANCELLED
                || pStatus == DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED)) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "APPLIED",
                    event.description() != null ? event.description() : pStatus.name(), occurredAt, payloadStr);
            deliveries.save(d);
            boolean cancellable = d.getStatus().isCancellable();
            String cause = pStatus == DeliveryProvider.ProviderDeliveryStatus.CANCELLED
                    ? "PROVIDER_CANCELLED"
                    : pStatus == DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED
                        ? "PICKUP_FAILED"
                        : pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                            ? "RIDER_CANCELLED" : "PICKUP_FAILED";
            return new ApplyResult("APPLIED", cancellable, cause, event.description(), d.getProviderDeliveryId(), false, d.getId(), false);
        }

        // Post-pickup provider cancellation: abnormal failure after courier had custody
        if (!isPrePickup && pStatus == DeliveryProvider.ProviderDeliveryStatus.CANCELLED) {
            d.moveTo(FoodDeliveryStatus.FAILED);
            d.setFailureCode("PROVIDER_CANCELLED_POST_PICKUP");
            d.setFailureReason(event.description() != null ? event.description() : "Provider cancelled after pickup");
            d.setReconciliationRequired(true);
            d.setReconciliationReason("Provider cancelled after pickup: " + (event.description() != null ? event.description() : "Operator review required"));
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
            deliveries.save(d);

            recordEvent(d, providerCode, eventId, pStatus.name(), FoodDeliveryStatus.FAILED, "APPLIED",
                    event.description(), occurredAt, payloadStr);
            timeline.record(d, FoodDeliveryStatus.FAILED, "Provider cancelled after pickup · " + (event.description() != null ? event.description() : "Failed"));
            return new ApplyResult("APPLIED", false, null, event.description(), null, false, d.getId(), true);
        }

        var target = mapStatus(pStatus);
        if (target == null) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "IGNORED",
                    event.description(), occurredAt, payloadStr);
            deliveries.save(d);
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId(), false);
        }

        // Monotonicity / out-of-order check
        int currentRank = d.getStatus().rank();
        int targetRank = target.rank();

        if (targetRank < currentRank) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "OUT_OF_ORDER",
                    event.description(), occurredAt, payloadStr);
            log.info("Out-of-order provider event {} for food delivery {}: current={}, incoming={}",
                    eventId, d.getId(), d.getStatus(), target);
            deliveries.save(d);
            return new ApplyResult("OUT_OF_ORDER", false, null, null, null, false, d.getId(), false);
        }

        if (target == d.getStatus()) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                    event.description(), occurredAt, payloadStr);
            deliveries.save(d);
            return new ApplyResult("APPLIED", false, null, null, null, false, d.getId(), false);
        }

        if (!d.getStatus().canTransitionTo(target)) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "IGNORED",
                    "Disallowed transition from " + d.getStatus() + " to " + target, occurredAt, payloadStr);
            log.warn("Disallowed status transition for food delivery {}: {} -> {}", d.getId(), d.getStatus(), target);
            deliveries.save(d);
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId(), false);
        }

        // Apply transition
        d.moveTo(target);
        // A provider can skip the pickup report; once the food is on the road or delivered the courier had it, and
        // "picked up" is what separates a failure before custody (nothing owed) from one after it (courier owed).
        if ((target.isInFlight() || target == FoodDeliveryStatus.DELIVERED) && d.getPickedUpAt() == null) {
            d.setPickedUpAt(occurredAt);
        }
        if (target == FoodDeliveryStatus.DRIVER_ASSIGNED && d.getAssignedAt() == null) {
            d.setAssignedAt(occurredAt);
        } else if (target == FoodDeliveryStatus.PICKED_UP) {
            d.setPickedUpAt(occurredAt);
        } else if (target == FoodDeliveryStatus.DELIVERED) {
            d.setDeliveredAt(occurredAt);
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
            supersedeFailure(d);
        } else if (target == FoodDeliveryStatus.FAILED) {
            d.setFailureCode("DELIVERY_FAILED");
            d.setFailureReason(event.description());
            d.setReconciliationRequired(true);
            d.setReconciliationReason("Delivery failed after pickup: " + (event.description() != null ? event.description() : "Courier reported failure"));
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
        } else if (target == FoodDeliveryStatus.CANCELLED) {
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
        }
        deliveries.save(d);

        recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                event.description(), occurredAt, payloadStr);
        timeline.record(d, target, event.description() != null ? event.description() : target.name());

        boolean needsSettle = target.isInFlight() || target == FoodDeliveryStatus.DELIVERED;
        boolean failedAfterPickup = target == FoodDeliveryStatus.FAILED && !isPrePickup;
        return new ApplyResult("APPLIED", false, null, event.description(), null, needsSettle, d.getId(), failedAfterPickup);
    }

    /**
     * The courier now confirms the food was delivered, after having reported a failure. The failure fields and the flag
     * described a state that no longer holds, so they are cleared; the failure event, the delivered event and an audit row
     * naming what was superseded keep the history (D-206). Only what the event cleared is cleared: a delivery that was
     * never failed is untouched.
     */
    private void supersedeFailure(FoodDelivery d) {
        if (d.getFailureCode() == null && !d.isReconciliationRequired()) {
            return;
        }
        String was = (d.getFailureCode() == null ? "" : d.getFailureCode()
                + (d.getFailureReason() == null ? "" : " · " + d.getFailureReason()))
                + (d.isReconciliationRequired() && d.getReconciliationReason() != null
                ? " [flag: " + d.getReconciliationReason() + "]" : "");
        d.setFailureCode(null);
        d.setFailureReason(null);
        d.setReconciliationRequired(false);
        d.setReconciliationReason(null);
        audit.record(null, null, "FOOD_DELIVERY_FAILURE_SUPERSEDED", "FOOD_DELIVERY", d.getId(),
                FoodDeliveryStatus.FAILED.name(), FoodDeliveryStatus.DELIVERED.name(),
                "The courier confirmed delivery after reporting a failure: " + was, "PROVIDER");
    }

    public void recordDriver(FoodDelivery delivery, String providerCode, String providerDeliveryId,
                             String name, String phone, String vehicle) {
        if (delivery == null || (name == null && phone == null && vehicle == null)) {
            return;
        }
        tx.executeWithoutResult(status -> {
            var d = deliveries.lockById(delivery.getId()).orElse(delivery);
            if (isStaleConsignment(d, providerCode, providerDeliveryId)) {
                log.info("Ignoring stale driver update from provider {} consignment {} for food delivery {}",
                        providerCode, providerDeliveryId, d.getId());
                return;
            }
            if (name != null) d.setDriverName(name);
            if (phone != null) d.setDriverPhone(phone);
            if (vehicle != null) d.setDriverVehicle(vehicle);
            d.setLastProviderUpdateAt(Instant.now());
            deliveries.save(d);
        });
    }

    public void recordDriver(FoodDelivery delivery, String name, String phone, String vehicle) {
        recordDriver(delivery, null, null, name, phone, vehicle);
    }

    public void recordLocation(FoodDelivery delivery, String providerCode, String providerDeliveryId,
                               BigDecimal lat, BigDecimal lng, Double bearing, Double speed, Instant recordedAt) {
        if (delivery == null || lat == null || lng == null) {
            return;
        }
        tx.executeWithoutResult(status -> {
            var d = deliveries.lockById(delivery.getId()).orElse(delivery);
            if (isStaleConsignment(d, providerCode, providerDeliveryId)) {
                log.info("Ignoring stale location update from provider {} consignment {} for food delivery {}",
                        providerCode, providerDeliveryId, d.getId());
                return;
            }
            Instant at = recordedAt != null ? recordedAt : Instant.now();
            var loc = new FoodDeliveryLocation();
            loc.setFoodDeliveryId(delivery.getId());
            loc.setLatitude(lat);
            loc.setLongitude(lng);
            loc.setBearing(bearing != null ? BigDecimal.valueOf(bearing) : null);
            loc.setSpeedKmph(speed != null ? BigDecimal.valueOf(speed) : null);
            loc.setRecordedAt(at);
            locations.save(loc);

            d.setLastProviderUpdateAt(Instant.now());
            deliveries.save(d);
        });
    }

    public void recordLocation(FoodDelivery delivery, BigDecimal lat, BigDecimal lng,
                               Double bearing, Double speed, Instant recordedAt) {
        recordLocation(delivery, null, null, lat, lng, bearing, speed, recordedAt);
    }

    public void recordEta(FoodDelivery delivery, String providerCode, String providerDeliveryId,
                          Integer etaMinutes, Instant estimatedArrivalAt) {
        if (delivery == null) {
            return;
        }
        tx.executeWithoutResult(status -> {
            var d = deliveries.lockById(delivery.getId()).orElse(delivery);
            if (isStaleConsignment(d, providerCode, providerDeliveryId)) {
                return;
            }
            if (etaMinutes != null) d.setEtaMinutes(etaMinutes);
            if (estimatedArrivalAt != null) d.setEstimatedArrivalAt(estimatedArrivalAt);
            d.setLastProviderUpdateAt(Instant.now());
            deliveries.save(d);
        });
    }

    public void recordEta(FoodDelivery delivery, Integer etaMinutes, Instant estimatedArrivalAt) {
        recordEta(delivery, null, null, etaMinutes, estimatedArrivalAt);
    }

    private boolean isStaleConsignment(FoodDelivery d, String providerCode, String providerDeliveryId) {
        if (providerCode != null && d.getProviderCode() != null && !providerCode.equalsIgnoreCase(d.getProviderCode())) {
            return true;
        }
        if (providerDeliveryId != null && d.getProviderDeliveryId() != null && !providerDeliveryId.equals(d.getProviderDeliveryId())) {
            return true;
        }
        return false;
    }

    private void recordEvent(FoodDelivery d, String providerCode, String eventId,
                             String providerStatus, FoodDeliveryStatus status,
                             String disposition, String description, Instant occurredAt, String payload) {
        var event = new FoodDeliveryEvent();
        event.setFoodDeliveryId(d.getId());
        event.setEventType(status != null ? status.eventName() : "ProviderEvent");
        event.setStatus(status);
        event.setProviderCode(providerCode);
        event.setProviderEventId(eventId);
        event.setProviderStatus(providerStatus);
        event.setDisposition(disposition);
        event.setDescription(description);
        event.setPayload(payload);
        event.setOccurredAt(occurredAt);
        events.save(event);
    }

    public static FoodDeliveryStatus mapStatus(DeliveryProvider.ProviderDeliveryStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING -> FoodDeliveryStatus.PROVIDER_SELECTED;
            case DRIVER_ASSIGNED -> FoodDeliveryStatus.DRIVER_ASSIGNED;
            case DRIVER_AT_PICKUP -> FoodDeliveryStatus.AT_PICKUP;
            case PICKED_UP -> FoodDeliveryStatus.PICKED_UP;
            case IN_TRANSIT -> FoodDeliveryStatus.OUT_FOR_DELIVERY;
            case ARRIVED_AT_DESTINATION -> FoodDeliveryStatus.ARRIVED;
            case DELIVERED -> FoodDeliveryStatus.DELIVERED;
            case DRIVER_CANCELLED, PICKUP_FAILED, CANCELLED -> null; // handled via rebook or post-pickup failure in processEventInTransaction
            case DELIVERY_FAILED -> FoodDeliveryStatus.FAILED;
        };
    }
}

