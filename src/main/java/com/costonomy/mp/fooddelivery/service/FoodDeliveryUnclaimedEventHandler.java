package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.UnclaimedDeliveryEventHandler;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Handles provider webhook events for food deliveries that are not claimed by supplier delivery (D-197).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryUnclaimedEventHandler implements UnclaimedDeliveryEventHandler {

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryEventService eventService;

    @Override
    public boolean handle(String providerCode, String providerDeliveryId, JsonNode payload) {
        if (providerDeliveryId == null && payload == null) {
            return false;
        }

        // 1. Look up by providerDeliveryId
        FoodDelivery delivery = null;
        if (providerDeliveryId != null) {
            delivery = deliveries.findByProviderCodeAndProviderDeliveryId(providerCode, providerDeliveryId).orElse(null);
        }

        // 2. If not found, check payload reference/custom_id (e.g. FD-123 or mp-food-delivery-123-1)
        if (delivery == null && payload != null) {
            Long foodDeliveryId = extractFoodDeliveryId(payload);
            if (foodDeliveryId != null) {
                delivery = deliveries.findById(foodDeliveryId).orElse(null);
            }
        }

        if (delivery == null) {
            return false; // Not ours
        }

        log.info("Claimed unclaimed delivery event from provider {} for food delivery {}", providerCode, delivery.getId());

        // Extract status and event details
        DeliveryProvider.ProviderDeliveryStatus pStatus = extractProviderStatus(payload);
        String eventId = extractEventId(payload, providerDeliveryId);
        String description = payload != null && payload.hasNonNull("description") ? payload.path("description").asText() : null;
        Instant occurredAt = extractOccurredAt(payload);

        // Driver details if present
        String riderName = payload != null && payload.hasNonNull("rider_name") ? payload.path("rider_name").asText() : null;
        String riderPhone = payload != null && payload.hasNonNull("rider_phone") ? payload.path("rider_phone").asText() : null;
        String riderVehicle = payload != null && payload.hasNonNull("rider_vehicle") ? payload.path("rider_vehicle").asText() : null;
        if (riderName != null || riderPhone != null || riderVehicle != null) {
            eventService.recordDriver(delivery, riderName, riderPhone, riderVehicle);
        }

        // Location if present
        if (payload != null && payload.hasNonNull("latitude") && payload.hasNonNull("longitude")) {
            try {
                BigDecimal lat = new BigDecimal(payload.path("latitude").asText());
                BigDecimal lng = new BigDecimal(payload.path("longitude").asText());
                eventService.recordLocation(delivery, lat, lng, null, null, occurredAt);
            } catch (Exception ex) {
                log.debug("Could not parse coordinates from payload: {}", ex.getMessage());
            }
        }

        if (pStatus != null) {
            var provEvent = new DeliveryProvider.ProviderEvent(eventId, pStatus, description, occurredAt);
            eventService.apply(delivery, provEvent, providerCode, payload);
        }

        return true;
    }

    private Long extractFoodDeliveryId(JsonNode payload) {
        String[] fields = {"source_ref", "reference", "external_id", "order_id", "custom_id", "client_reference_id"};
        for (String field : fields) {
            if (payload.hasNonNull(field)) {
                String text = payload.path(field).asText().trim();
                if (text.startsWith("FD-")) {
                    try {
                        return Long.parseLong(text.substring(3));
                    } catch (NumberFormatException ignored) {}
                }
                if (text.startsWith("mp-food-delivery-")) {
                    String[] parts = text.split("-");
                    if (parts.length >= 4) {
                        try {
                            return Long.parseLong(parts[3]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return null;
    }

    private DeliveryProvider.ProviderDeliveryStatus extractProviderStatus(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("status")) {
            return null;
        }
        String statusStr = payload.path("status").asText().toUpperCase();
        return switch (statusStr) {
            case "ALLOCATED", "DRIVER_ASSIGNED", "ASSIGNED" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "REACHED_PICKUP", "AT_PICKUP" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "PICKED_UP", "PICKED" -> DeliveryProvider.ProviderDeliveryStatus.PICKED_UP;
            case "IN_TRANSIT", "OUT_FOR_DELIVERY", "ON_THE_WAY" -> DeliveryProvider.ProviderDeliveryStatus.IN_TRANSIT;
            case "REACHED_DROP", "ARRIVED", "ARRIVED_AT_DESTINATION" -> DeliveryProvider.ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "DELIVERED", "COMPLETED" -> DeliveryProvider.ProviderDeliveryStatus.DELIVERED;
            case "RIDER_CANCELLED", "DRIVER_CANCELLED" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED;
            case "PICKUP_FAILED" -> DeliveryProvider.ProviderDeliveryStatus.PICKUP_FAILED;
            case "DELIVERY_FAILED", "FAILED" -> DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED;
            case "CANCELLED" -> DeliveryProvider.ProviderDeliveryStatus.CANCELLED;
            default -> null;
        };
    }

    private String extractEventId(JsonNode payload, String providerDeliveryId) {
        if (payload != null && payload.hasNonNull("event_id")) {
            return payload.path("event_id").asText();
        }
        if (payload != null && payload.hasNonNull("id")) {
            return payload.path("id").asText();
        }
        return "evt_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Instant extractOccurredAt(JsonNode payload) {
        if (payload != null && payload.hasNonNull("occurred_at")) {
            try {
                return Instant.parse(payload.path("occurred_at").asText());
            } catch (Exception ignored) {}
        }
        return Instant.now();
    }
}
