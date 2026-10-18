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
    private final TransactionTemplate tx;

    public FoodDeliveryEventService(FoodDeliveryRepository deliveries,
                                   FoodDeliveryEventRepository events,
                                   FoodDeliveryLocationRepository locations,
                                   FoodDeliveryTimeline timeline,
                                   @Lazy FoodDeliveryBookingService bookingService,
                                   @Lazy DeliveryRecoveryService recoveryService,
                                   ObjectMapper json,
                                   PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.events = events;
        this.locations = locations;
        this.timeline = timeline;
        this.bookingService = bookingService;
        this.recoveryService = recoveryService;
        this.json = json;
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
            Long deliveryId
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
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId());
        }

        d.setLastProviderUpdateAt(now);

        // Rider cancellation or pickup failure before pickup: trigger stand-down and rebook
        if (pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                || pStatus == DeliveryProvider.ProviderDeliveryStatus.PICKUP_FAILED) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "APPLIED",
                    event.description() != null ? event.description() : pStatus.name(), occurredAt, payloadStr);
            deliveries.save(d);
            boolean cancellable = d.getStatus().isCancellable();
            String cause = pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                    ? "RIDER_CANCELLED" : "PICKUP_FAILED";
            return new ApplyResult("APPLIED", cancellable, cause, event.description(), d.getProviderDeliveryId(), false, d.getId());
        }

        var target = mapStatus(pStatus);
        if (target == null) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "IGNORED",
                    event.description(), occurredAt, payloadStr);
            deliveries.save(d);
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId());
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
            return new ApplyResult("OUT_OF_ORDER", false, null, null, null, false, d.getId());
        }

        if (target == d.getStatus()) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                    event.description(), occurredAt, payloadStr);
            deliveries.save(d);
            return new ApplyResult("APPLIED", false, null, null, null, false, d.getId());
        }

        if (!d.getStatus().canTransitionTo(target)) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "IGNORED",
                    "Disallowed transition from " + d.getStatus() + " to " + target, occurredAt, payloadStr);
            log.warn("Disallowed status transition for food delivery {}: {} -> {}", d.getId(), d.getStatus(), target);
            deliveries.save(d);
            return new ApplyResult("IGNORED", false, null, null, null, false, d.getId());
        }

        // Apply transition
        d.moveTo(target);
        if (target == FoodDeliveryStatus.DRIVER_ASSIGNED && d.getAssignedAt() == null) {
            d.setAssignedAt(occurredAt);
        } else if (target == FoodDeliveryStatus.PICKED_UP) {
            d.setPickedUpAt(occurredAt);
        } else if (target == FoodDeliveryStatus.DELIVERED) {
            d.setDeliveredAt(occurredAt);
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
        } else if (target == FoodDeliveryStatus.FAILED) {
            d.setFailureCode("DELIVERY_FAILED");
            d.setFailureReason(event.description());
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
        } else if (target == FoodDeliveryStatus.CANCELLED) {
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
        }
        deliveries.save(d);

        recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                event.description(), occurredAt, payloadStr);
        timeline.record(d, target, event.description() != null ? event.description() : target.name());

        boolean needsSettle = target.isInFlight() || target == FoodDeliveryStatus.DELIVERED;
        return new ApplyResult("APPLIED", false, null, null, null, needsSettle, d.getId());
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
            case DRIVER_CANCELLED, PICKUP_FAILED -> null; // handled via rebook
            case DELIVERY_FAILED -> FoodDeliveryStatus.FAILED;
            case CANCELLED -> FoodDeliveryStatus.CANCELLED;
        };
    }
}

