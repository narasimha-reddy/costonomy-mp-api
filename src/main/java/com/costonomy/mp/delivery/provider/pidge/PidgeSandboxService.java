package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.repository.DeliveryLocationRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Test-only: move a Pidge SANDBOX delivery to its next stage (D-188).
 *
 * <p>Pidge's sandbox has no riders, so nothing ever happens to a booking unless something asks Pidge's own dummy
 * endpoint for the next stage. The order object it returns goes through {@link PidgeWebhookService#process}, the same
 * code a real webhook runs after its signature check, so the delivery, the rider, the order and the buyer's screen
 * all move exactly as they would for a real rider.
 *
 * <p>Not transactional on purpose: the call to Pidge is an HTTP round trip and must not hold a database transaction
 * open; {@code process} runs in its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PidgeSandboxService {

    private final DeliveryRepository deliveries;
    private final DeliveryService deliveryService;
    private final AccessControlService accessControl;
    private final PidgeProperties properties;
    private final PidgeApiClient client;
    private final PidgeWebhookService webhook;
    private final DeliveryEventService events;
    private final DeliveryLocationRepository locations;

    public DeliveryDtos.DeliveryResponse advance(Long actorId, Long deliveryId) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, delivery.getSupplierStoreId(), "Delivery");

        // With the sandbox off this route does not exist, so production never reveals it.
        if (!properties.isSandbox()) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        if (delivery.getMode() != DeliveryMode.COSTONOMY) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The supplier carries this delivery, so there is no rider to move.");
        }
        // Another partner's delivery is not ours to simulate.
        if (!PidgeSandboxStages.isPidge(delivery) || delivery.getProviderDeliveryId() == null) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        var dummy = PidgeSandboxStages.nextDummyStatus(delivery.getStatus()).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This delivery is " + delivery.getStatus() + " and has no next step."));

        var body = client.simulateOrderStatus(delivery.getProviderDeliveryId(), dummy);
        var order = body == null ? null : body.path("data");
        if (order == null || order.isMissingNode() || order.isNull()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The sandbox gave no order to apply.");
        }
        log.info("Pidge sandbox advance: delivery {} {} -> '{}'", deliveryId, delivery.getStatus(), dummy);
        var rider = PidgeSandboxStages.rider(deliveryId);
        webhook.process(withSandboxRider(withSyntheticPosition(order, dummy, delivery), rider));
        // Pidge's answer has no vehicle, so the webhook stores none; add the made-up bike to the rider it stored.
        deliveries.findById(deliveryId)
                .filter(stored -> rider.name().equals(stored.getDriverName()))
                .ifPresent(stored -> events.recordDriver(stored, rider.name(), rider.phone(), rider.vehicle()));
        return deliveryService.get(actorId, deliveryId);
    }

    /**
     * Test-only: put the rider at one point along the road (or the straight line, off the stored route) without
     * changing the delivery's status (D-192). One location fix stamped now, and always later than the previous fix of
     * this delivery so the same-time dedupe in {@code recordLocation} never swallows it.
     */
    public DeliveryDtos.DeliveryResponse move(Long actorId, Long deliveryId, String legName, String fractionText) {
        var delivery = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery", deliveryId));
        accessControl.requireScoped(actorId, Permissions.ORDER_READY,
                ScopeType.SUPPLIER_STORE, delivery.getSupplierStoreId(), "Delivery");
        if (!properties.isSandbox()) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        if (!PidgeSandboxStages.isPidge(delivery) || delivery.getProviderDeliveryId() == null) {
            throw new NotFoundException("Delivery", deliveryId);
        }
        if (delivery.getMode() != DeliveryMode.COSTONOMY) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "The supplier carries this delivery, so there is no rider to move.");
        }
        var leg = parseLeg(legName);
        double fraction = parseFraction(fractionText);
        if (!delivery.getStatus().isTrackable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "This delivery is " + delivery.getStatus() + ", so there is no rider to move.");
        }

        PidgeSandboxStages.Position position;
        if (onRoad(delivery)) {
            var point = PidgeSandboxRoute.shared().pointAt(leg, fraction);
            position = new PidgeSandboxStages.Position(point.latitude(), point.longitude());
        } else {
            position = PidgeSandboxStages.straightMove(leg, fraction, delivery.getPickupLatitude(),
                    delivery.getPickupLongitude(), delivery.getDropLatitude(), delivery.getDropLongitude())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "This delivery has no stored pickup and drop to move along."));
        }

        var at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var last = locations.findFirstByDeliveryIdOrderByRecordedAtDescIdDesc(deliveryId);
        if (last.isPresent() && !at.isAfter(last.get().getRecordedAt())) {
            at = last.get().getRecordedAt().plusMillis(1);
        }
        log.info("Pidge sandbox move: delivery {} {} {}", deliveryId, leg, fraction);
        events.recordLocation(delivery, position.latitude(), position.longitude(), null, null, at);
        return deliveryService.get(actorId, deliveryId);
    }

    private static PidgeSandboxRoute.Leg parseLeg(String leg) {
        if (leg != null) {
            for (var candidate : PidgeSandboxRoute.Leg.values()) {
                if (candidate.name().equalsIgnoreCase(leg.trim())) {
                    return candidate;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "leg must be approach or delivery.");
    }

    private static double parseFraction(String text) {
        try {
            double value = Double.parseDouble(text == null ? "" : text.trim());
            if (Double.isFinite(value) && value >= 0.0 && value <= 1.0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the 400 below
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "fraction must be a number from 0 to 1.");
    }

    /** True when the delivery's stored pickup and drop are the stored road route's supplier and outlet. */
    private static boolean onRoad(com.costonomy.mp.delivery.domain.Delivery delivery) {
        return PidgeSandboxRoute.shared().matches(delivery.getPickupLatitude(), delivery.getPickupLongitude(),
                delivery.getDropLatitude(), delivery.getDropLongitude());
    }

    /**
     * Pidge's dummy answer names its rider "Rider name" (D-195). Replace the name and mobile of every rider in the
     * stage logs with this delivery's made-up rider, so the webhook code stores (and announces) that one. Only this
     * sandbox route calls it; a real webhook keeps the rider Pidge names.
     */
    private static JsonNode withSandboxRider(JsonNode order, PidgeSandboxStages.Rider rider) {
        var logs = order.path("fulfillment").path("logs");
        if (!logs.isArray()) {
            return order;
        }
        var copy = order.deepCopy();
        for (var stage : copy.path("fulfillment").path("logs")) {
            if (stage.path("rider") instanceof ObjectNode stageRider && stageRider.hasNonNull("name")) {
                stageRider.put("name", rider.name());
                stageRider.put("mobile", rider.phone());
            }
        }
        return copy;
    }

    /**
     * Pidge's dummy answer always carries the same rider point (near Gurugram) stamped a few minutes ahead, so the
     * truck on the map never moves and never goes stale (D-190). Put the stage's point on the straight line from the
     * delivery's pickup to its drop instead, stamped now. Without stored coordinates, or if the answer has no stage
     * log to carry a position, the answer is returned as Pidge sent it.
     */
    private JsonNode withSyntheticPosition(JsonNode order, String dummy,
                                           com.costonomy.mp.delivery.domain.Delivery delivery) {
        var route = PidgeSandboxRoute.shared();
        var position = onRoad(delivery)
                ? PidgeSandboxStages.roadPosition(dummy, route)
                : PidgeSandboxStages.position(dummy, delivery.getPickupLatitude(),
                        delivery.getPickupLongitude(), delivery.getDropLatitude(), delivery.getDropLongitude());
        var logs = order.path("fulfillment").path("logs");
        if (position.isEmpty() || !logs.isArray() || logs.isEmpty()
                || !(logs.get(logs.size() - 1) instanceof ObjectNode)) {
            return order;
        }
        var copy = order.deepCopy();
        var stage = (ObjectNode) copy.path("fulfillment").path("logs").get(logs.size() - 1);
        stage.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        var location = stage.putObject("location");
        location.put("latitude", position.get().latitude().toPlainString());
        location.put("longitude", position.get().longitude().toPlainString());
        return copy;
    }
}
