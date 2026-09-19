package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.*;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.repository.*;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivery, from ready-for-pickup to delivered. Doc 06 §6, doc 04 §14.
 *
 * <p>Two modes, and the difference is who reports movement. On <b>Costonomy
 * delivery</b> Mandi quotes, books and follows a courier's events. On <b>supplier
 * own delivery</b> the supplier is the courier: there is no provider, no quoting
 * and no tracking, and they report their own progress — doc 06 §2 says as much,
 * and inventing a position for a van we cannot see would be exactly the
 * fabrication doc 06 §8 forbids.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {

    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final DeliveryLocationRepository locations;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryQuotingService quoting;
    private final DeliveryBookingService booking;
    private final DeliveryEventService eventService;
    private final DeliveryOrderBridge orderBridge;
    private final DeliveryTimeline timeline;
    private final DeliveryDirectory directory;
    private final DeliveryProviderRegistry registry;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    /** Doc 06 §8: past this, a position is shown as stale rather than as current. */
    @Value("${costonomy.mp.delivery.location-stale-after:60s}")
    private Duration locationStaleAfter;

    // ── Requesting ───────────────────────────────────────────────────────

    /**
     * Arrange delivery for an order that is ready. Doc 06 §6.
     *
     * <p>Idempotent on the order: {@code uk_delivery_order} means a retried request
     * returns the delivery that already exists rather than sending a second courier
     * to the same door.
     */
    @Transactional
    public DeliveryDtos.DeliveryResponse request(Long actorId, Long supplierOrderId,
                                                 DeliveryDtos.RequestDeliveryRequest body) {

        var order = directory.order(supplierOrderId);
        if (order == null) {
            throw new NotFoundException("SupplierOrder", supplierOrderId);
        }
        // Either side may arrange it — the supplier when the goods are packed, the
        // restaurant when they are waiting — so the check accepts either scope.
        requireEitherSide(actorId, order.outletId(), order.supplierStoreId(), supplierOrderId);

        var existing = deliveries.findBySupplierOrderId(supplierOrderId).orElse(null);
        if (existing != null) {
            return toResponse(existing, order.orderNumber());
        }

        if (!"READY_FOR_PICKUP".equals(order.status())) {
            // Doc 06 §6 step 1. Dispatching a courier to goods that are not packed
            // wastes their time and starts an ETA the supplier cannot meet.
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This order isn't ready for pickup yet.");
        }

        var pickup = directory.pickupFor(order.supplierStoreId());
        var drop = directory.dropFor(order.outletId());
        if (pickup == null || drop == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This order is missing a pickup or delivery address.");
        }

        var policy = directory.deliveryPolicy(order.supplierStoreId());
        var mode = resolveMode(body.mode(), order.deliveryMode(), policy);

        var delivery = new Delivery();
        delivery.setSupplierOrderId(supplierOrderId);
        delivery.setOutletId(order.outletId());
        delivery.setSupplierStoreId(order.supplierStoreId());
        delivery.setMode(mode);
        delivery.setPickupAddress(pickup.address());
        delivery.setPickupLatitude(pickup.latitude());
        delivery.setPickupLongitude(pickup.longitude());
        delivery.setPickupContactName(pickup.contactName());
        delivery.setPickupContactPhone(pickup.contactPhone());
        delivery.setDropAddress(drop.address());
        delivery.setDropLatitude(drop.latitude());
        delivery.setDropLongitude(drop.longitude());
        delivery.setDropContactName(drop.contactName());
        delivery.setDropContactPhone(drop.contactPhone());
        delivery.setRequestedAt(Instant.now());
        deliveries.save(delivery);

        timeline.record(delivery, "DeliveryRequested", DeliveryStatus.DELIVERY_REQUESTED,
                "Arranging delivery");

        if (mode == DeliveryMode.SUPPLIER_OWN) {
            // The supplier carries it. No quoting, no booking, and the fee is
            // theirs — usually zero, which doc 01 §20 says the restaurant then
            // pays. The delivery goes straight to the state where someone is
            // expected to collect it.
            delivery.setFee(policy.ownDeliveryFee() == null
                    ? BigDecimal.ZERO : policy.ownDeliveryFee());
            delivery.setStatus(DeliveryStatus.DRIVER_ASSIGNED);
            delivery.setAssignedAt(Instant.now());
            delivery.setDriverName(pickup.contactName());
            delivery.setDriverPhone(pickup.contactPhone());
            deliveries.save(delivery);

            timeline.record(delivery, DeliveryStatus.DRIVER_ASSIGNED.eventName(),
                    DeliveryStatus.DRIVER_ASSIGNED,
                    "The supplier is delivering this order");
            return toResponse(delivery, order.orderNumber());
        }

        quoteAndBook(delivery, order, body.requiredEtaMinutes(), List.of(), "BOOKING");
        return toResponse(delivery, order.orderNumber());
    }

    /**
     * Gather quotes, then book. Doc 06 §6 steps 3–6.
     *
     * <p>One call rather than two endpoints the client sequences: doc 04 §14 exposes
     * quoting separately for operations, but a restaurant asking for delivery wants
     * a courier, not a list of prices they are not allowed to see anyway.
     */
    private void quoteAndBook(Delivery delivery, DeliveryDirectory.OrderInfo order,
                              Integer requiredEtaMinutes, List<String> excluded,
                              String attemptType) {

        var outcome = quoting.gather(delivery, order.deliveryFee(),
                directory.consignmentWeightGrams(delivery.getSupplierOrderId()),
                requiredEtaMinutes, excluded);

        if (!outcome.anyServiceable()) {
            delivery.setStatus(DeliveryStatus.QUOTE_FAILED);
            delivery.setFailureCode("NO_SERVICEABLE_PROVIDER");
            delivery.setFailureReason("No delivery partner covers this route right now.");
            deliveries.save(delivery);
            timeline.record(delivery, DeliveryStatus.QUOTE_FAILED.eventName(),
                    DeliveryStatus.QUOTE_FAILED,
                    delivery.getFailureReason());
            return;
        }

        delivery.setStatus(DeliveryStatus.QUOTE_RECEIVED);
        deliveries.save(delivery);

        booking.book(delivery, excluded, attemptType);
    }

    /**
     * Which mode applies.
     *
     * <p>A request may name one, the order may already carry one from checkout, and
     * otherwise the supplier's configuration decides. <b>Own delivery wins the
     * default</b> where it is available: doc 01 §20 has the restaurant paying for
     * delivery unless the supplier absorbs it, so the free option is the one to
     * choose on their behalf.
     */
    private DeliveryMode resolveMode(String requested, String onOrder,
                                     DeliveryDirectory.DeliveryPolicy policy) {

        DeliveryMode asked = parseMode(requested);
        if (asked == null) {
            asked = fromOrder(onOrder);
        }

        if (asked == DeliveryMode.SUPPLIER_OWN && !policy.ownDeliveryEnabled()) {
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This supplier doesn't deliver orders themselves.");
        }
        if (asked == DeliveryMode.COSTONOMY && !policy.costonomyDeliveryEnabled()) {
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This supplier doesn't use Mandi delivery.");
        }
        if (asked != null) {
            return asked;
        }

        if (policy.ownDeliveryEnabled()) {
            return DeliveryMode.SUPPLIER_OWN;
        }
        if (policy.costonomyDeliveryEnabled()) {
            return DeliveryMode.COSTONOMY;
        }
        throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                "This supplier has no delivery option configured.");
    }

    /**
     * The order's mode, translated into this module's.
     *
     * <p>Two vocabularies over one column since D-091. The order says how the
     * restaurant chose to receive the goods — including {@code PICKUP}, which is
     * not a delivery at all — and this module only knows who carries them. They
     * were the same word for a while and are not any more, so the mapping is
     * written down rather than left to {@code valueOf} to get wrong.
     */
    private DeliveryMode fromOrder(String onOrder) {
        if (onOrder == null || onOrder.isBlank()) {
            return null;
        }
        return switch (onOrder) {
            case "SUPPLIER_DELIVERY", "SUPPLIER_OWN" -> DeliveryMode.SUPPLIER_OWN;
            case "COSTONOMY_DELIVERY", "COSTONOMY" -> DeliveryMode.COSTONOMY;
            // A collected order has no consignment. Refused here rather than
            // quietly booked, because a courier sent to goods the kitchen is
            // coming for is a cost nobody agreed to.
            case "PICKUP" -> throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "This order is being collected, so there is nothing to deliver.");
            default -> null;
        };
    }

    private DeliveryMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose SUPPLIER_OWN or COSTONOMY.");
        }
    }

    // ── Reassignment and cancellation ────────────────────────────────────

    /**
     * Send this consignment to a different courier. Doc 06 §7.
     *
     * <p><b>The delivery keeps its identity.</b> Same row, same id, same thing the
     * restaurant is watching — the attempt count goes up and a new
     * {@code delivery_provider_attempt} is written. Creating a second delivery
     * would show two journeys for one consignment and would make delivery failure
     * rates meaningless.
     */
    @Transactional
    public DeliveryDtos.DeliveryResponse reassign(Long actorId, Long deliveryId, String reason) {
        var delivery = loadForEitherSide(actorId, deliveryId);

        if (delivery.getMode() == DeliveryMode.SUPPLIER_OWN) {
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "This order is being delivered by the supplier.");
        }
        if (delivery.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery is already " + delivery.getStatus() + ".");
        }
        if (delivery.getStatus().isInFlight()) {
            // Past pickup the goods are on a vehicle. Reassigning would mean asking
            // a second courier to collect something the first one already has.
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "This order has already been picked up.");
        }

        var order = directory.order(delivery.getSupplierOrderId());
        var tried = booking.triedProviders(deliveryId);

        // Stand the current courier down first, so we are not paying two.
        releaseCurrentProvider(delivery, reason == null ? "Reassigned" : reason);

        // Excluding the couriers already tried is the whole point: handing it back
        // to the one that just cancelled is not a reassignment.
        var usable = quoting.usableQuotes(deliveryId, tried);
        if (usable.isEmpty()) {
            quoteAndBook(delivery, order, null, tried, "REASSIGNMENT");
        } else {
            booking.book(delivery, tried, "REASSIGNMENT");
        }

        if (delivery.getStatus() == DeliveryStatus.PROVIDER_UNAVAILABLE) {
            throw new BusinessException(ErrorCode.DELIVERY_REASSIGNMENT_FAILED,
                    "No other delivery partner could take this order.");
        }

        auditService.record(actorId, null, "DELIVERY_REASSIGNED", "DELIVERY", deliveryId,
                null, delivery.getStatus().name(), reason, "API");
        timeline.record(delivery, "DeliveryReassigned", delivery.getStatus(),
                "Finding another driver");

        return toResponse(delivery, order == null ? null : order.orderNumber());
    }

    @Transactional
    public DeliveryDtos.DeliveryResponse cancel(Long actorId, Long deliveryId, String reason) {
        var delivery = loadForEitherSide(actorId, deliveryId);

        if (delivery.getStatus() == DeliveryStatus.CANCELLED) {
            return toResponse(delivery, null);
        }
        if (!delivery.getStatus().canTransitionTo(DeliveryStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery can't be cancelled once the goods have been picked up.");
        }

        releaseCurrentProvider(delivery, reason);

        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledAt(Instant.now());
        delivery.setFailureReason(reason);
        deliveries.save(delivery);

        timeline.record(delivery, DeliveryStatus.CANCELLED.eventName(),
                DeliveryStatus.CANCELLED, reason);
        auditService.record(actorId, null, "DELIVERY_CANCELLED", "DELIVERY", deliveryId,
                null, DeliveryStatus.CANCELLED.name(), reason, "API");

        return toResponse(delivery, null);
    }

    /** Tell the current courier to stand down, and close their attempt. */
    private void releaseCurrentProvider(Delivery delivery, String reason) {
        if (delivery.getProviderDeliveryId() == null) {
            return;
        }
        var adapter = registry.adapter(delivery.getProviderCode());
        if (adapter != null) {
            try {
                adapter.cancel(delivery.getProviderDeliveryId(), reason);
            } catch (DeliveryProviderException ex) {
                // Their problem, not ours to fail on. A courier we cannot reach is
                // still a courier we are no longer using, and blocking the
                // reassignment would strand the consignment.
                log.warn("Could not cancel {} delivery {}: {}",
                        delivery.getProviderCode(), delivery.getProviderDeliveryId(),
                        ex.getMessage());
            }
        }

        attempts.findFirstByDeliveryIdOrderByAttemptNumberDesc(delivery.getId())
                .ifPresent(attempt -> {
                    if ("BOOKED".equals(attempt.getOutcome())) {
                        attempt.setOutcome("CANCELLED");
                        attempt.setFailureReason(reason);
                        attempt.setEndedAt(Instant.now());
                        attempts.save(attempt);
                    }
                });

        delivery.setDriverName(null);
        delivery.setDriverPhone(null);
        delivery.setDriverVehicle(null);
    }

    // ── Supplier own delivery ────────────────────────────────────────────

    /**
     * The supplier reports their own progress. Doc 06 §2.
     *
     * <p>Only on own delivery. On Costonomy delivery this is refused — §23A.38: a
     * supplier cannot claim a pickup or a delivery a courier performed, and the
     * temptation to let them "just mark it delivered" is exactly what that rule
     * exists to prevent.
     */
    @Transactional
    public DeliveryDtos.DeliveryResponse supplierReports(Long actorId, Long deliveryId,
                                                        DeliveryStatus target) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));

        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, delivery.getSupplierStoreId(), "Delivery");

        if (!delivery.getMode().isSupplierReported()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "A delivery partner is carrying this order, so its progress comes from them.");
        }
        if (delivery.getStatus() == target) {
            return toResponse(delivery, null);
        }
        if (!delivery.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery can't move from %s to %s."
                            .formatted(delivery.getStatus(), target));
        }

        var previous = delivery.getStatus();
        delivery.setStatus(target);
        if (target == DeliveryStatus.PICKED_UP) {
            delivery.setPickedUpAt(Instant.now());
        }
        if (target == DeliveryStatus.DELIVERED) {
            delivery.setDeliveredAt(Instant.now());
        }
        deliveries.save(delivery);

        timeline.record(delivery, target.eventName(), target, null);
        auditService.record(actorId, null, "DELIVERY_" + target.name(), "DELIVERY",
                deliveryId, previous.name(), target.name(), "Supplier own delivery", "API");

        orderBridge.onDeliveryStatus(delivery, target);
        return toResponse(delivery, null);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryResponse get(Long actorId, Long deliveryId) {
        var delivery = loadForEitherSide(actorId, deliveryId);
        var order = directory.order(delivery.getSupplierOrderId());
        return toResponse(delivery, order == null ? null : order.orderNumber());
    }

    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryResponse forOrder(Long actorId, Long supplierOrderId) {
        var delivery = deliveries.findBySupplierOrderId(supplierOrderId)
                .orElseThrow(() -> new NotFoundException("Delivery", supplierOrderId));
        return get(actorId, delivery.getId());
    }

    @Transactional(readOnly = true)
    public List<DeliveryDtos.EventResponse> timelineFor(Long actorId, Long deliveryId) {
        loadForEitherSide(actorId, deliveryId);
        return appliedEvents(deliveryId);
    }

    /** The delivery, for internal callers that already did their own access check. */
    @Transactional(readOnly = true)
    public Delivery load(Long deliveryId) {
        return deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
    }

    // ── internals ────────────────────────────────────────────────────────

    private List<DeliveryDtos.EventResponse> appliedEvents(Long deliveryId) {
        return events.findByDeliveryIdOrderByOccurredAtAscIdAsc(deliveryId).stream()
                // Duplicates and out-of-order events stay in the database for
                // diagnosis. A restaurant does not need to see a courier's retries.
                .filter(event -> "APPLIED".equals(event.getDisposition()))
                .map(event -> new DeliveryDtos.EventResponse(
                        event.getId(), event.getEventType(), event.getStatus(),
                        event.getDescription(), event.getOccurredAt()))
                .toList();
    }

    private void requireEitherSide(Long actorId, Long outletId, Long storeId, Long orderId) {
        if (accessControl.has(actorId, Permissions.ORDER_VIEW, ScopeType.OUTLET, outletId)
                || accessControl.has(actorId, Permissions.ORDER_VIEW,
                ScopeType.SUPPLIER_STORE, storeId)) {
            return;
        }
        log.warn("Scope violation: user={} SupplierOrder={} — reported as not found",
                actorId, orderId);
        throw new NotFoundException("SupplierOrder", orderId);
    }

    private Delivery loadForEitherSide(Long actorId, Long deliveryId) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        requireEitherSide(actorId, delivery.getOutletId(),
                delivery.getSupplierStoreId(), deliveryId);
        return delivery;
    }

    private DeliveryDtos.DeliveryResponse toResponse(Delivery delivery, String orderNumber) {
        var latest = delivery.getMode().isTracked()
                ? locations.findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(delivery.getId())
                        .orElse(null)
                : null;

        DeliveryDtos.LocationResponse location = null;
        boolean stale = false;
        Integer ageSeconds = null;

        if (latest != null) {
            location = new DeliveryDtos.LocationResponse(latest.getLatitude(),
                    latest.getLongitude(), latest.getBearing(), latest.getRecordedAt());
            long age = Duration.between(latest.getRecordedAt(), Instant.now()).getSeconds();
            ageSeconds = (int) Math.max(0, age);
            // Doc 06 §8: past the freshness threshold the app shows a stale state.
            // The server decides, so every client agrees on what "stale" means.
            stale = age > locationStaleAfter.getSeconds();
        }

        return new DeliveryDtos.DeliveryResponse(
                delivery.getId(), delivery.getSupplierOrderId(), orderNumber,
                delivery.getMode(), delivery.getStatus(), delivery.getFee(),
                delivery.getCurrency(), delivery.getPickupAddress(), delivery.getDropAddress(),
                delivery.getDriverName(), delivery.getDriverPhone(), delivery.getDriverVehicle(),
                delivery.getEtaMinutes(), delivery.getEstimatedArrivalAt(),
                delivery.getMode().isTracked() && delivery.getStatus().isTrackable(),
                location, stale, ageSeconds,
                delivery.getFailureCode(), delivery.getFailureReason(),
                delivery.getRequestedAt(), delivery.getPickedUpAt(), delivery.getDeliveredAt(),
                appliedEvents(delivery.getId()));
    }
}
