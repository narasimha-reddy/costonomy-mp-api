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
    private final org.springframework.context.ApplicationEventPublisher publisher;
    private final DeliveryProviderAttemptRepository attempts;

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
        target = providerStoodDown(delivery, target);

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
        boolean allowed = current.canTransitionTo(target);
        // A rider cancelling or a pickup failing is newer news than the rider reaching the pickup, even though it ranks
        // below it: the state machine allows it, and ignoring it left the delivery waiting for a rider who had gone.
        boolean standDown = allowed && (target == DeliveryStatus.DRIVER_CANCELLED
                || target == DeliveryStatus.PICKUP_FAILED);
        boolean backwards = !standDown && (target.rank() < current.rank()
                || (target == current && target != DeliveryStatus.IN_TRANSIT));

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

        // The same fix again (Pidge's 30 s poll keeps returning its last milestone position, and the webhook may
        // have stored it first) is one row, not one per poll. Only a fix with the provider's own time can repeat.
        if (recordedAt != null) {
            var latest = locations.findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(delivery.getId());
            if (latest.isPresent() && recordedAt.equals(latest.get().getRecordedAt())) {
                return;
            }
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
            case DRIVER_ASSIGNED -> {
                delivery.setAssignedAt(Instant.now());
                delivery.setAssignmentDeadline(null);
            }
            case PICKED_UP -> delivery.setPickedUpAt(Instant.now());
            case DELIVERED -> {
                delivery.setDeliveredAt(Instant.now());
                delivery.setAssignmentDeadline(null);
            }
            case DRIVER_CANCELLED, PICKUP_FAILED, DELIVERY_FAILED -> {
                delivery.setAssignmentDeadline(null);
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
        recordOnAttempt(delivery, target, event);

        // Publish, do not record. The ledger row for this event was already written
        // at the top of this method, carrying the provider's event id and its
        // disposition. Calling timeline.record() here wrote a *second* row for the
        // same event — same type, same status, no provider id — and since the
        // timeline read returns everything marked APPLIED, a restaurant saw every
        // step of their delivery twice.
        timeline.publish(delivery, target.eventName(), event.description());

        auditService.record(null, null, "DELIVERY_" + target.name(), "DELIVERY",
                delivery.getId(), previous.name(), target.name(), event.description(), "PROVIDER");

        if (target == DeliveryStatus.DRIVER_CANCELLED || target == DeliveryStatus.PICKUP_FAILED) {
            // The provider let go of a consignment we still owe the restaurant. Nothing else would ever book the next
            // courier: the deadline was cleared above and no job watches these statuses. Handled after commit.
            publisher.publishEvent(new DeliveryProviderStoodDownEvent(
                    delivery.getId(), delivery.getProviderDeliveryId(), event.description()));
        }

        // The supplier order follows the consignment.
        orders.onDeliveryStatus(delivery, target);
    }

    /**
     * A provider cancelling the whole consignment, before pickup, when we did not ask it to, means that courier will
     * not carry it: the same as the rider cancelling, and recoverable. Read as terminal {@code CANCELLED} it ended the
     * delivery with the goods still waiting and no other courier tried.
     *
     * <p>Our own cancellation sets {@code CANCELLED} on the delivery before the provider's echo arrives, so that echo
     * is the same status and is stored as out of order, never read as a stand-down. Once the goods are picked up a
     * provider cancellation is left as it was.
     */
    private static DeliveryStatus providerStoodDown(Delivery delivery, DeliveryStatus target) {
        var current = delivery.getStatus();
        // Already stood down and waiting for the next courier: the provider cancelling the order too (Pidge sends both)
        // is the same news. Reading it as CANCELLED would end the delivery before the reassignment ran.
        if (target == DeliveryStatus.CANCELLED
                && (current == DeliveryStatus.DRIVER_CANCELLED || current == DeliveryStatus.PICKUP_FAILED)) {
            return current;
        }
        if (target == DeliveryStatus.CANCELLED && !current.isTerminal()
                && current.rank() < DeliveryStatus.PICKED_UP.rank()
                && current != DeliveryStatus.DRIVER_CANCELLED && current != DeliveryStatus.PICKUP_FAILED) {
            return DeliveryStatus.DRIVER_CANCELLED;
        }
        return target;
    }

    /**
     * What the provider's news means for the attempt that is current (D-201 addendum): when its rider was assigned, and
     * why it ended if it ended. {@code CANCELLED} from the provider (read as that courier standing down) is told apart
     * from the rider cancelling by the provider's own status, which {@link #providerStoodDown} did not change.
     */
    private void recordOnAttempt(Delivery delivery, DeliveryStatus target, ProviderEvent event) {
        String cause = switch (target) {
            case DRIVER_CANCELLED -> event.status() == ProviderDeliveryStatus.CANCELLED
                    ? "PROVIDER_CANCELLED" : "RIDER_CANCELLED";
            case PICKUP_FAILED -> "PICKUP_FAILED";
            case DELIVERY_FAILED -> "DELIVERY_FAILED";
            case DELIVERED -> "COMPLETED";
            default -> null;
        };
        if (cause == null && target != DeliveryStatus.DRIVER_ASSIGNED) {
            return;
        }
        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(delivery.getId()).ifPresent(attempt -> {
            if (target == DeliveryStatus.DRIVER_ASSIGNED) {
                if (attempt.getAssignedAt() == null) {
                    attempt.setAssignedAt(Instant.now());
                }
            } else if (attempt.getEndedCause() == null) {
                attempt.setEndedCause(cause);
            }
            attempts.save(attempt);
        });
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
