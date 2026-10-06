package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust HTTP client for Pidge REST APIs with:
 * <ul>
 *   <li>API token & secret headers</li>
 *   <li>Configurable connect/read timeouts</li>
 *   <li>Token-bucket rate guardrail to avoid downstream API abuse</li>
 *   <li>Structured exception mapping</li>
 * </ul>
 */
@Component
@Slf4j
public class PidgeApiClient {

    private final PidgeProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Lightweight token bucket rate limiter to prevent downstream API abuse
    private final AtomicInteger tokens;
    private volatile long lastRefillTime;

    public PidgeApiClient(PidgeProperties properties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = builder
                .setConnectTimeout(properties.getTimeout())
                .setReadTimeout(properties.getTimeout())
                .build();
        this.tokens = new AtomicInteger(properties.getRateLimitRps());
        this.lastRefillTime = System.currentTimeMillis();
    }

    private void checkRateLimit() {
        refillTokens();
        if (tokens.decrementAndGet() < 0) {
            tokens.incrementAndGet();
            throw new DeliveryProviderException("PIDGE",
                    "Pidge API rate limit throttled locally to protect downstream service", true);
        }
    }

    private synchronized void refillTokens() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed >= 1000) {
            tokens.set(properties.getRateLimitRps());
            lastRefillTime = now;
        }
    }

    private HttpHeaders createHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            headers.set("Authorization", "Bearer " + properties.getApiToken());
        }
        if (properties.getApiSecret() != null && !properties.getApiSecret().isBlank()) {
            headers.set("X-Api-Secret", properties.getApiSecret());
        }
        headers.set("X-Channel-Name", properties.getChannelName());
        return headers;
    }

    // ── required response / request fields (D-121: nothing is defaulted) ───

    private static DeliveryProviderException missing(String field) {
        return new DeliveryProviderException("PIDGE", "Pidge response missing or invalid field: " + field, false);
    }

    private static void requireRequest(boolean ok, String what) {
        if (!ok) {
            throw new DeliveryProviderException("PIDGE", what + " missing or invalid for Pidge booking", false);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String requiredText(JsonNode body, String field) {
        var node = body.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw missing(field);
        }
        return node.asText();
    }

    private static BigDecimal requiredAmount(JsonNode body, String field) {
        var node = body.path(field);
        if (!node.isNumber() && !node.isTextual()) {
            throw missing(field);
        }
        try {
            BigDecimal amount = new BigDecimal(node.asText().trim());
            if (amount.signum() <= 0) {
                throw missing(field);
            }
            return amount;
        } catch (NumberFormatException ex) {
            throw missing(field);
        }
    }

    private static int requiredInt(JsonNode body, String field) {
        var node = body.path(field);
        if (!node.isIntegralNumber() || node.asInt() <= 0) {
            throw missing(field);
        }
        return node.asInt();
    }

    private static long requiredLong(JsonNode body, String field) {
        var node = body.path(field);
        if (!node.isIntegralNumber() || node.asLong() <= 0) {
            throw missing(field);
        }
        return node.asLong();
    }

    private static double requiredDouble(JsonNode body, String field) {
        var node = body.path(field);
        if (!node.isNumber() || node.asDouble() < 0) {
            throw missing(field);
        }
        return node.asDouble();
    }

    /**
     * Request fare and ETA estimates from Pidge.
     */
    public DeliveryProvider.Quote getQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            log.debug("Pidge API token not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Pidge provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());
        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Pidge quote");
        }
        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }
        // Nothing is invented for a request the order cannot fill: the weight and vehicle are
        // facts about the goods, and a guessed one prices (and dispatches) the wrong rider (D-121).
        if (request.weightKg() == null || request.weightKg().signum() <= 0) {
            return DeliveryProvider.Quote.unserviceable("Pidge quote needs the order's weight");
        }
        if (request.vehicleType() == null) {
            return DeliveryProvider.Quote.unserviceable("Pidge quote needs a vehicle type");
        }

        var url = properties.getBaseUrl() + "/v1/channel/quote";
        var payload = Map.of(
                "pickup", Map.of("lat", request.pickupLatitude(), "lng", request.pickupLongitude()),
                "drop", Map.of("lat", request.dropLatitude(), "lng", request.dropLongitude()),
                "weight_kg", request.weightKg(),
                "vehicle_type", request.vehicleType().name(),
                "channel_name", properties.getChannelName(),
                "allocation_mode", properties.getAllocationMode()
        );

        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                if (!body.hasNonNull("serviceable")) {
                    throw missing("serviceable");
                }
                if (!body.path("serviceable").asBoolean()) {
                    return DeliveryProvider.Quote.unserviceable(body.path("decline_reason").asText("Pidge declined the route"));
                }
                String quoteId = requiredText(body, "quote_id");
                BigDecimal amount = requiredAmount(body, "total_fare");
                int eta = requiredInt(body, "eta_minutes");
                double distance = requiredDouble(body, "distance_km");
                Instant expiresAt = Instant.now().plusSeconds(requiredLong(body, "expires_in_seconds"));

                return new DeliveryProvider.Quote(quoteId, true, amount, "INR", eta, distance,
                        expiresAt, null, request.vehicleType());
            }
            return DeliveryProvider.Quote.unserviceable("Could not obtain quote from Pidge");

        } catch (DeliveryProviderException ex) {
            throw ex;
        } catch (HttpStatusCodeException ex) {
            log.warn("Pidge quoting HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("PIDGE", "Pidge HTTP error: " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Pidge quoting network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Pidge connection timeout", true);
        } catch (Exception ex) {
            log.error("Unexpected error calling Pidge quote", ex);
            throw new DeliveryProviderException("PIDGE", "Pidge quote failed: " + ex.getMessage(), false);
        }
    }

    /**
     * Create / manifest an order on Pidge.
     */
    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            throw new DeliveryProviderException("PIDGE", "Pidge API token not configured for booking", false);
        }

        // Validated before the call, by name: a booking built from guessed values creates a live
        // consignment nobody can reconcile (D-121).
        requireRequest(request.supplierOrderId() != null, "supplier order");
        requireRequest(notBlank(request.idempotencyKey()), "idempotency key");
        requireRequest(notBlank(request.providerQuoteId()), "quote id");
        requireRequest(request.weightKg() != null && request.weightKg().signum() > 0, "weight");
        requireRequest(request.vehicleType() != null, "vehicle type");
        requireRequest(notBlank(request.pickupAddress()) && request.pickupLatitude() != null
                && request.pickupLongitude() != null, "pickup address and coordinates");
        requireRequest(notBlank(request.dropAddress()) && request.dropLatitude() != null
                && request.dropLongitude() != null, "drop address and coordinates");
        requireRequest(notBlank(request.pickupContactName()) && notBlank(request.pickupContactPhone()), "pickup contact");
        requireRequest(notBlank(request.dropContactName()) && notBlank(request.dropContactPhone()), "drop contact");

        var url = properties.getBaseUrl() + "/v1/channel/order/create";
        var payload = Map.of(
                "order_reference", "SO-" + request.supplierOrderId(),
                "idempotency_key", request.idempotencyKey(),
                "quote_id", request.providerQuoteId(),
                "channel_name", properties.getChannelName(),
                "allocation_mode", properties.getAllocationMode(),
                "vehicle_type", request.vehicleType().name(),
                "weight_kg", request.weightKg(),
                "pickup", Map.of(
                        "address", request.pickupAddress(),
                        "lat", request.pickupLatitude(),
                        "lng", request.pickupLongitude(),
                        "contact_name", request.pickupContactName(),
                        "contact_phone", request.pickupContactPhone()
                ),
                "drop", Map.of(
                        "address", request.dropAddress(),
                        "lat", request.dropLatitude(),
                        "lng", request.dropLongitude(),
                        "contact_name", request.dropContactName(),
                        "contact_phone", request.dropContactPhone()
                )
        );

        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                String deliveryId = requiredText(body, "pidge_delivery_id");
                BigDecimal amount = requiredAmount(body, "fare");
                int eta = requiredInt(body, "eta_minutes");
                Instant etaTime = Instant.now().plusSeconds(eta * 60L);
                String trackingUrl = body.has("tracking_url") ? body.path("tracking_url").asText(null) : null;

                return new DeliveryProvider.Booking(deliveryId, amount, "INR", eta, etaTime, trackingUrl);
            }
            throw new DeliveryProviderException("PIDGE", "Failed to create delivery on Pidge", true);

        } catch (DeliveryProviderException ex) {
            throw ex;
        } catch (HttpStatusCodeException ex) {
            log.error("Pidge create order HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("PIDGE", "Pidge create order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.error("Pidge create order network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Pidge create order timeout", true);
        } catch (Exception ex) {
            log.error("Pidge order creation failed", ex);
            throw new DeliveryProviderException("PIDGE", "Pidge booking failed: " + ex.getMessage(), false);
        }
    }

    /**
     * Fetch order status and tracking URL from Pidge.
     */
    public DeliveryProvider.ProviderDelivery getStatus(String providerDeliveryId) {
        checkRateLimit();

        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            return new DeliveryProvider.ProviderDelivery(providerDeliveryId,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        var url = properties.getBaseUrl() + "/v1/channel/order/" + providerDeliveryId + "/status";
        try {
            var entity = new HttpEntity<>(createHeaders());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                var statusStr = body.path("status").asText("PENDING");
                var status = mapPidgeStatus(statusStr);
                var driver = body.path("driver");

                String driverName = driver.path("name").isNull() ? null : driver.path("name").asText(null);
                String driverPhone = driver.path("phone").isNull() ? null : driver.path("phone").asText(null);
                String driverVehicle = driver.path("vehicle_number").isNull() ? null : driver.path("vehicle_number").asText(null);
                Integer eta = body.has("eta_minutes") ? body.path("eta_minutes").asInt() : null;

                return new DeliveryProvider.ProviderDelivery(
                        providerDeliveryId, status, driverName, driverPhone, driverVehicle,
                        eta, null, null, null, List.of()
                );
            }
            return new DeliveryProvider.ProviderDelivery(providerDeliveryId,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());

        } catch (Exception ex) {
            log.warn("Pidge getStatus failed for {}: {}", providerDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Could not fetch Pidge status: " + ex.getMessage(), true);
        }
    }

    /**
     * Cancel an active order on Pidge.
     */
    public void cancel(String providerDeliveryId, String reason) {
        checkRateLimit();

        if (properties.getApiToken() == null || properties.getApiToken().isBlank()) {
            return;
        }

        var url = properties.getBaseUrl() + "/v1/channel/order/" + providerDeliveryId + "/cancel";
        var payload = Map.of("reason", reason != null ? reason : "Customer cancelled");

        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            restTemplate.postForEntity(url, entity, Void.class);
        } catch (Exception ex) {
            log.warn("Pidge order cancellation failed for {}: {}", providerDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Could not cancel on Pidge: " + ex.getMessage(), false);
        }
    }

    /**
     * Staging Sandbox API: Simulate order status retrieval.
     * <p>Hits {@code GET /v1.0/store/channel/vendor/order/:id?dummy_status=:status}.
     * Allowed statuses: cancelled, pending, fulfilled|registered, fulfilled|out for pickup,
     * fulfilled|reached pickup, fulfilled|picked up, fulfilled|ofd, fulfilled|reached delivery,
     * fulfilled|undelivered, fulfilled|delivered, fulfilled|rto out for delivery, fulfilled|rto delivered.
     */
    public JsonNode simulateOrderStatus(String pidgeDeliveryId, String dummyStatus) {
        checkRateLimit();
        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/order/"
                + pidgeDeliveryId + "?dummy_status=" + dummyStatus;
        try {
            var entity = new HttpEntity<>(createHeaders());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            return response.getBody();
        } catch (Exception ex) {
            log.error("Pidge sandbox simulateOrderStatus failed for {}: {}", pidgeDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Pidge sandbox status simulation failed: " + ex.getMessage(), false);
        }
    }

    /**
     * Staging Sandbox API: Simulate webhook dispatch from Pidge to our webhook URL.
     * <p>Hits {@code POST /v1.0/store/channel/vendor/order/:id/webhook/events}.
     * Payload: {@code {"dummy_status": ":status"}}.
     */
    public JsonNode triggerSandboxWebhook(String pidgeDeliveryId, String dummyStatus) {
        checkRateLimit();
        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/order/"
                + pidgeDeliveryId + "/webhook/events";
        var payload = Map.of("dummy_status", dummyStatus);
        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);
            return response.getBody();
        } catch (Exception ex) {
            log.error("Pidge sandbox triggerSandboxWebhook failed for {}: {}", pidgeDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("PIDGE", "Pidge sandbox webhook simulation failed: " + ex.getMessage(), false);
        }
    }

    public static DeliveryProvider.ProviderDeliveryStatus mapPidgeStatus(String pidgeStatus) {
        if (pidgeStatus == null) return DeliveryProvider.ProviderDeliveryStatus.PENDING;
        return switch (pidgeStatus.toUpperCase().trim()) {
            case "ORDER_CREATED", "PENDING", "SEARCHING_RIDER", "ALLOCATING" -> DeliveryProvider.ProviderDeliveryStatus.PENDING;
            case "RIDER_ASSIGNED", "ALLOCATED" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_ASSIGNED;
            case "REACHED_PICKUP", "DRIVER_AT_PICKUP" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_AT_PICKUP;
            case "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY" -> DeliveryProvider.ProviderDeliveryStatus.PICKED_UP;
            case "REACHED_DROP", "ARRIVED_AT_DESTINATION" -> DeliveryProvider.ProviderDeliveryStatus.ARRIVED_AT_DESTINATION;
            case "DELIVERED", "COMPLETED" -> DeliveryProvider.ProviderDeliveryStatus.DELIVERED;
            case "RIDER_CANCELLED", "DRIVER_CANCELLED" -> DeliveryProvider.ProviderDeliveryStatus.DRIVER_CANCELLED;
            case "PICKUP_FAILED" -> DeliveryProvider.ProviderDeliveryStatus.PICKUP_FAILED;
            case "DELIVERY_FAILED", "FAILED" -> DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED;
            case "CANCELLED" -> DeliveryProvider.ProviderDeliveryStatus.CANCELLED;
            default -> DeliveryProvider.ProviderDeliveryStatus.PENDING;
        };
    }
}
