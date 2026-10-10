package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What Pidge says about one order, read from its order object, which is the same shape in the GET answer (under
 * {@code data}) and in a webhook (the root). From Pidge's published API:
 *
 * <ul>
 *   <li>{@code status} is the parent state: {@code cancelled}, {@code pending}, {@code fulfilled} or
 *       {@code completed}. It says nothing about the rider, which is why reading only it left every delivery on
 *       "finding a driver".</li>
 *   <li>{@code fulfillment.status} is the current stage while fulfilled: CREATED, OUT_FOR_PICKUP, REACHED_PICKUP,
 *       PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, REACHED_DELIVERY, DELIVERED, DISPOSED, UNDELIVERED, RTO_*, LOST,
 *       DAMAGED, CANCELLED.</li>
 *   <li>{@code fulfillment.logs[]} holds each stage with its time, the rider and where the rider was.</li>
 * </ul>
 */
record PidgeOrderState(
        ProviderDeliveryStatus status,
        String riderName,
        String riderPhone,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant locationAt,
        /** Newest first, only stages Pidge reported. */
        List<DeliveryProvider.ProviderEvent> events) {

    static PidgeOrderState parse(String pidgeOrderId, JsonNode order) {
        String parent = order.path("status").asText("pending").toLowerCase(Locale.ROOT).trim();
        var fulfillment = order.path("fulfillment");
        String stage = fulfillment.path("status").asText(null);

        ProviderDeliveryStatus status = switch (parent) {
            case "cancelled" -> ProviderDeliveryStatus.CANCELLED;
            case "pending" -> ProviderDeliveryStatus.PENDING;
            // Completed covers delivered, disposed and returned; the stage tells which.
            case "completed" -> stage == null ? ProviderDeliveryStatus.DELIVERED : mapStage(stage);
            default -> stage == null ? ProviderDeliveryStatus.PENDING : mapStage(stage);
        };

        var events = new ArrayList<DeliveryProvider.ProviderEvent>();
        String riderName = null;
        String riderPhone = null;
        BigDecimal lat = null;
        BigDecimal lng = null;
        Instant locationAt = null;

        for (JsonNode log : fulfillment.path("logs")) {
            var logStatus = mapStage(log.path("status").asText(null));
            Instant at = instant(log.path("timestamp").asText(null));
            var rider = log.path("rider");
            if (rider.hasNonNull("name")) {
                riderName = rider.path("name").asText();
                riderPhone = rider.hasNonNull("mobile") ? rider.path("mobile").asText() : riderPhone;
            }
            var location = log.path("location");
            if (location.hasNonNull("latitude") && location.hasNonNull("longitude")) {
                lat = new BigDecimal(location.path("latitude").asText());
                lng = new BigDecimal(location.path("longitude").asText());
                locationAt = at;
            }
            if (logStatus != ProviderDeliveryStatus.PENDING) {
                String remark = log.path("remark").isNull() ? null : log.path("remark").asText(null);
                events.add(new DeliveryProvider.ProviderEvent(
                        "pidge-" + pidgeOrderId + "-" + log.path("status").asText() + "-" + log.path("timestamp").asText(),
                        logStatus, remark == null || remark.isBlank() ? log.path("status").asText() : remark, at));
            }
        }
        // Newest first, as the poller expects.
        Collections.reverse(events);

        // The parent says cancelled or completed with no log to carry it: still report that stage once.
        if (events.isEmpty() && status != ProviderDeliveryStatus.PENDING) {
            events.add(new DeliveryProvider.ProviderEvent("pidge-" + pidgeOrderId + "-" + status,
                    status, "Status update: " + (stage == null ? parent : stage), Instant.now()));
        }
        return new PidgeOrderState(status, riderName, riderPhone, lat, lng, locationAt, List.copyOf(events));
    }

    /** One fulfillment stage, or one of the parent states, as the stage our delivery model understands. */
    static ProviderDeliveryStatus mapStage(String stage) {
        if (stage == null) {
            return ProviderDeliveryStatus.PENDING;
        }
        return switch (stage.toUpperCase(Locale.ROOT).trim().replace(' ', '_')) {
            // Manifested, no rider yet.
            case "CREATED", "PENDING" -> ProviderDeliveryStatus.PENDING;
            // A rider is assigned and on the way to the store.
            case "OUT_FOR_PICKUP" -> ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "REACHED_PICKUP" -> ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "PICKED_UP" -> ProviderDeliveryStatus.PICKED_UP;
            case "IN_TRANSIT", "OUT_FOR_DELIVERY" -> ProviderDeliveryStatus.IN_TRANSIT;
            case "REACHED_DELIVERY" -> ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "DELIVERED", "COMPLETED" -> ProviderDeliveryStatus.DELIVERED;
            // The rider's fulfilment was cancelled and the order went back to pending: another can be sought.
            case "CANCELLED" -> ProviderDeliveryStatus.DRIVER_CANCELLED;
            // Not delivered, returned, lost, damaged or disposed of: the goods did not reach the restaurant.
            case "UNDELIVERED", "RTO_OUT_FOR_DELIVERY", "RTO_UNDELIVERED", "RTO_DELIVERED", "LOST", "DAMAGED",
                 "DISPOSED" -> ProviderDeliveryStatus.DELIVERY_FAILED;
            default -> ProviderDeliveryStatus.PENDING;
        };
    }

    private static Instant instant(String text) {
        try {
            return text == null ? null : Instant.parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
