package com.costonomy.mp.delivery.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderEvent;
import com.costonomy.mp.delivery.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Takes what a provider says and decides what it means. Doc 06 §9, §12, §13.
 *
 * <p>Three rules, each of which a real courier integration will exercise within a
 * day of going live.
 *
 * <p><b>Deduplicate on the provider's event id.</b> Providers retry. The unique
 * constraint decides it rather than a preceding lookup, which would race with the
 * retry it exists to catch.
 *
 * <p><b>An event that would move the delivery backwards is stored and not
 * applied.</b> Doc 06 §12 requires out-of-order processing. A {@code DRIVER_ASSIGNED}
 * arriving after {@code PICKED_UP} is a statement about the past; applying it would
 * take a restaurant watching their goods move and tell them the driver is still
 * being found.
 *
 * <p><b>The delivery drives the order, not the other way round.</b> Pickup moves
 * the supplier order to {@code OUT_FOR_DELIVERY} and delivery to {@code DELIVERED}
 * — §23A.38 is explicit that a supplier cannot claim either on a courier's behalf,
 * so the courier's event is the only thing that can.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryEventService {

    private final DeliveryRepository deliveries;
    private final DeliveryEventStore eventStore;
    private final DeliveryLocationRepository locations;
    private final DeliveryTimeline timeline;
    private final DeliveryOrderBridge orders;
    private final AuditService auditService;

    /**
     * Apply one provider event.
     *
     * @return what happened to it — APPLIED, DUPLICATE, OUT_OF_ORDER or IGNORED
     */
    @Transactional
    public String apply(Delivery delivery, ProviderEvent event) {
        var target = map(event.status());
        if (target == null) {
            return "IGNORED";
        }

        var record = new DeliveryEvent();
        record.setDeliveryId(delivery.getId());
        record.setEventType(target.eventName());
        record.setStatus(target);
        record.setProviderCode(delivery.getProviderCode());
        record.setProviderEventId(event.providerEventId());
        record.setProviderStatus(event.status().name());
        record.setDescription(event.description());
        record.setOccurredAt(event.occurredAt() == null ? Instant.now() : event.occurredAt());

        var current = delivery.getStatus();
        boolean backwards = target.rank() < current.rank()
                || (target == current && target != DeliveryStatus.IN_TRANSIT);
        boolean allowed = current.canTransitionTo(target);

        record.setDisposition(backwards ? "OUT_OF_ORDER" : allowed ? "APPLIED" : "IGNORED");

        try {
            eventStore.record(record);
        } catch (DataIntegrityViolationException ex) {
            // uk_delivery_event_provider. A retry of an event we already have —
            // replay protection, doc 06 §13.
            log.debug("Duplicate delivery event {} ignored", event.providerEventId());
            return "DUPLICATE";
        }

        if (backwards) {
            log.info("Ignoring out-of-order {} for delivery {}: {} cannot become {}",
                    event.status(), delivery.getId(), current, target);
            return "OUT_OF_ORDER";
        }
        if (!allowed) {
            return "IGNORED";
        }

        advance(delivery, target, event);
        return "APPLIED";
    }

    /** Record a driver position. Never called with anything we invented. */
    @Transactional
    public void recordLocation(Delivery delivery, java.math.BigDecimal latitude,
                               java.math.BigDecimal longitude, Double bearing,
                               Double speedKmph, Instant recordedAt) {

        if (!delivery.getStatus().isTrackable()) {
            // Before assignment there is no driver, so a position is either a
            // provider bug or ours. Storing it would put a pin on a map for a
            // journey nobody has started.
            log.debug("Ignoring location for delivery {} in {}",
                    delivery.getId(), delivery.getStatus());
            return;
        }

        var location = new DeliveryLocation();
        location.setDeliveryId(delivery.getId());
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setBearing(bearing == null ? null : java.math.BigDecimal.valueOf(bearing));
        location.setSpeedKmph(speedKmph == null ? null : java.math.BigDecimal.valueOf(speedKmph));
        location.setRecordedAt(recordedAt == null ? Instant.now() : recordedAt);
        locations.save(location);

        delivery.setLastProviderUpdateAt(Instant.now());
        deliveries.save(delivery);

        timeline.publish(delivery, "DeliveryLocationUpdated", null);
    }

    /** A revised ETA. Doc 06 §9. */
    @Transactional
    public void recordEta(Delivery delivery, Integer etaMinutes) {
        if (etaMinutes == null || etaMinutes.equals(delivery.getEtaMinutes())) {
            return;
        }
        delivery.setEtaMinutes(etaMinutes);
        delivery.setEstimatedArrivalAt(Instant.now().plusSeconds(etaMinutes * 60L));
        delivery.setLastProviderUpdateAt(Instant.now());
        deliveries.save(delivery);

        timeline.record(delivery, "DeliveryEtaChanged", delivery.getStatus(),
                "Arriving in about %d minutes".formatted(etaMinutes));
    }

    /** Driver identity, where the provider supports it (doc 06 §8). */
    @Transactional
    public void recordDriver(Delivery delivery, String name, String phone, String vehicle) {
        delivery.setDriverName(name);
        delivery.setDriverPhone(phone);
        delivery.setDriverVehicle(vehicle);
        deliveries.save(delivery);
    }

    // ── internals ────────────────────────────────────────────────────────

    private void advance(Delivery delivery, DeliveryStatus target, ProviderEvent event) {
        var previous = delivery.getStatus();
        delivery.setStatus(target);
        delivery.setLastProviderUpdateAt(Instant.now());

        switch (target) {
            case DRIVER_ASSIGNED -> delivery.setAssignedAt(Instant.now());
            case PICKED_UP -> delivery.setPickedUpAt(Instant.now());
            case DELIVERED -> delivery.setDeliveredAt(Instant.now());
            case DRIVER_CANCELLED, PICKUP_FAILED, DELIVERY_FAILED -> {
                delivery.setFailureCode(target.name());
                delivery.setFailureReason(event.description());
                // The driver is gone with the job; keeping their name would show a
                // restaurant a courier who is no longer coming.
                delivery.setDriverName(null);
                delivery.setDriverPhone(null);
                delivery.setDriverVehicle(null);
            }
            default -> { }
        }
        deliveries.save(delivery);

        timeline.record(delivery, target.eventName(), target, event.description());

        auditService.record(null, null, "DELIVERY_" + target.name(), "DELIVERY",
                delivery.getId(), previous.name(), target.name(), event.description(), "PROVIDER");

        // The supplier order follows the consignment.
        orders.onDeliveryStatus(delivery, target);
    }

    /** Provider vocabulary onto ours. The only place the two meet. */
    private DeliveryStatus map(ProviderDeliveryStatus status) {
        return switch (status) {
            case PENDING -> null;
            case DRIVER_ASSIGNED -> DeliveryStatus.DRIVER_ASSIGNED;
            case DRIVER_AT_PICKUP -> DeliveryStatus.DRIVER_AT_PICKUP;
            case PICKED_UP -> DeliveryStatus.PICKED_UP;
            case IN_TRANSIT -> DeliveryStatus.IN_TRANSIT;
            case ARRIVED_AT_DESTINATION -> DeliveryStatus.ARRIVED_AT_DESTINATION;
            case DELIVERED -> DeliveryStatus.DELIVERED;
            case DRIVER_CANCELLED -> DeliveryStatus.DRIVER_CANCELLED;
            case PICKUP_FAILED -> DeliveryStatus.PICKUP_FAILED;
            case DELIVERY_FAILED -> DeliveryStatus.DELIVERY_FAILED;
            case CANCELLED -> DeliveryStatus.CANCELLED;
        };
    }
}
