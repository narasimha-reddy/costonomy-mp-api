package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final ObjectMapper json;

    public FoodDeliveryEventService(FoodDeliveryRepository deliveries,
                                   FoodDeliveryEventRepository events,
                                   FoodDeliveryLocationRepository locations,
                                   FoodDeliveryTimeline timeline,
                                   @Lazy FoodDeliveryBookingService bookingService,
                                   ObjectMapper json) {
        this.deliveries = deliveries;
        this.events = events;
        this.locations = locations;
        this.timeline = timeline;
        this.bookingService = bookingService;
        this.json = json;
    }

    /**
     * Apply one provider event to the food delivery.
     * Deduplicates by providerEventId and protects against out-of-order state transitions.
     *
     * @return disposition: APPLIED, DUPLICATE, OUT_OF_ORDER, or IGNORED
     */
    @Transactional
    public String apply(FoodDelivery delivery, DeliveryProvider.ProviderEvent event,
                        String providerCode, JsonNode rawPayload) {
        if (event == null || event.status() == null) {
            return "IGNORED";
        }

        String eventId = event.providerEventId();
        if (eventId != null && events.existsByProviderCodeAndProviderEventId(providerCode, eventId)) {
            log.debug("Duplicate provider event {} from {} for food delivery {}", eventId, providerCode, delivery.getId());
            return "DUPLICATE";
        }

        var d = deliveries.lockById(delivery.getId()).orElse(delivery);
        Instant now = Instant.now();
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : now;
        d.setLastProviderUpdateAt(now);

        var pStatus = event.status();
        String payloadStr = rawPayload != null ? rawPayload.toString() : null;

        // Rider cancellation or pickup failure before pickup: trigger stand-down and rebook
        if (pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                || pStatus == DeliveryProvider.ProviderDeliveryStatus.PICKUP_FAILED) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "APPLIED",
                    event.description() != null ? event.description() : pStatus.name(), occurredAt, payloadStr);
            if (d.getStatus().isCancellable()) {
                String cause = pStatus == DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED
                        ? "RIDER_CANCELLED" : "PICKUP_FAILED";
                log.info("Driver let go for food delivery {}: cause={}, rebooking next courier", d.getId(), cause);
                bookingService.rebook(d.getId(), d.getProviderDeliveryId(), cause, event.description());
            }
            return "APPLIED";
        }

        var target = mapStatus(pStatus);
        if (target == null) {
            recordEvent(d, providerCode, eventId, pStatus.name(), d.getStatus(), "IGNORED",
                    event.description(), occurredAt, payloadStr);
            return "IGNORED";
        }

        // Monotonicity / out-of-order check
        int currentRank = d.getStatus().rank();
        int targetRank = target.rank();

        if (targetRank < currentRank) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "OUT_OF_ORDER",
                    event.description(), occurredAt, payloadStr);
            log.info("Out-of-order provider event {} for food delivery {}: current={}, incoming={}",
                    eventId, d.getId(), d.getStatus(), target);
            return "OUT_OF_ORDER";
        }

        if (target == d.getStatus()) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                    event.description(), occurredAt, payloadStr);
            return "APPLIED";
        }

        if (!d.getStatus().canTransitionTo(target)) {
            recordEvent(d, providerCode, eventId, pStatus.name(), target, "IGNORED",
                    "Disallowed transition from " + d.getStatus() + " to " + target, occurredAt, payloadStr);
            log.warn("Disallowed status transition for food delivery {}: {} -> {}", d.getId(), d.getStatus(), target);
            return "IGNORED";
        }

        // Apply transition
        d.moveTo(target);
        if (target == FoodDeliveryStatus.DRIVER_ASSIGNED && d.getAssignedAt() == null) {
            d.setAssignedAt(occurredAt);
        } else if (target == FoodDeliveryStatus.PICKED_UP) {
            d.setPickedUpAt(occurredAt);
        } else if (target == FoodDeliveryStatus.DELIVERED) {
            d.setDeliveredAt(occurredAt);
        } else if (target == FoodDeliveryStatus.FAILED) {
            d.setFailureCode("DELIVERY_FAILED");
            d.setFailureReason(event.description());
        }
        deliveries.save(d);

        recordEvent(d, providerCode, eventId, pStatus.name(), target, "APPLIED",
                event.description(), occurredAt, payloadStr);
        timeline.record(d, target, event.description() != null ? event.description() : target.name());

        // Fee settlement: D-197 requires returning the unused part of the hold at pickup
        if (target == FoodDeliveryStatus.PICKED_UP) {
            try {
                bookingService.settleFee(d.getId());
            } catch (Exception ex) {
                log.error("Fee settlement on pickup failed for food delivery {}: {}", d.getId(), ex.getMessage());
            }
        }

        return "APPLIED";
    }

    @Transactional
    public void recordDriver(FoodDelivery delivery, String name, String phone, String vehicle) {
        if (name == null && phone == null && vehicle == null) {
            return;
        }
        var d = deliveries.lockById(delivery.getId()).orElse(delivery);
        if (name != null) d.setDriverName(name);
        if (phone != null) d.setDriverPhone(phone);
        if (vehicle != null) d.setDriverVehicle(vehicle);
        d.setLastProviderUpdateAt(Instant.now());
        deliveries.save(d);
    }

    @Transactional
    public void recordLocation(FoodDelivery delivery, BigDecimal lat, BigDecimal lng,
                               Double bearing, Double speed, Instant recordedAt) {
        if (lat == null || lng == null) {
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

        var d = deliveries.lockById(delivery.getId()).orElse(delivery);
        d.setLastProviderUpdateAt(Instant.now());
        deliveries.save(d);
    }

    @Transactional
    public void recordEta(FoodDelivery delivery, Integer etaMinutes, Instant estimatedArrivalAt) {
        var d = deliveries.lockById(delivery.getId()).orElse(delivery);
        if (etaMinutes != null) d.setEtaMinutes(etaMinutes);
        if (estimatedArrivalAt != null) d.setEstimatedArrivalAt(estimatedArrivalAt);
        d.setLastProviderUpdateAt(Instant.now());
        deliveries.save(d);
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
