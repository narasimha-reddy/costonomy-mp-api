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
            String token = properties.getApiToken().trim();
            if (token.startsWith("Bearer ") || token.startsWith("bearer ")) {
                token = token.substring(7).trim();
            }
            headers.set("Authorization", "Bearer " + token);
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

    /** The 10 digits of an Indian mobile ("+919876543210", "09876543210", "98765 43210" all give 9876543210), else null. */
    static String indianMobile(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.matches("[6-9]\\d{9}") ? digits : null;
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

        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/quote";
        var payload = Map.of(
                "pickup", Map.of(
                        "coordinates", Map.of("latitude", request.pickupLatitude(), "longitude", request.pickupLongitude())
                ),
                "drop", List.of(
                        Map.of(
                                "ref", "quote-ref-" + System.currentTimeMillis(),
                                "location", Map.of(
                                        "coordinates", Map.of("latitude", request.dropLatitude(), "longitude", request.dropLongitude())
                                ),
                                "attributes", Map.of(
                                        "cod_amount", 0,
                                        "weight", request.weightKg().multiply(BigDecimal.valueOf(1000)).intValue(),
                                        "volumetric_weight", request.weightKg().multiply(BigDecimal.valueOf(1000)).intValue()
                                )
                        )
                )
        );

        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                var data = body.path("data");
                var items = data.path("items");
                if (items.isArray() && !items.isEmpty()) {
                    // Pick the lowest priced serviceable quote
                    JsonNode bestItem = null;
                    BigDecimal lowestPrice = null;
                    for (JsonNode item : items) {
                        if (!item.hasNonNull("error") || item.path("error").isNull()) {
                            var quoteNode = item.path("quote");
                            if (quoteNode.hasNonNull("price")) {
                                BigDecimal price = new BigDecimal(quoteNode.path("price").asText());
                                if (lowestPrice == null || price.compareTo(lowestPrice) < 0) {
                                    lowestPrice = price;
                                    bestItem = item;
                                }
                            }
                        }
                    }

                    if (bestItem != null && lowestPrice != null) {
                        String networkName = bestItem.path("network_name").asText("Pidge");
                        String quoteId = "pidg_q_" + bestItem.path("network_id").asText("1") + "_" + System.currentTimeMillis();
                        // Only what Pidge states: an ETA or distance it did not give is left unknown, not made up.
                        Integer etaMinutes = null;
                        var etaNode = bestItem.path("quote").path("eta");
                        if (etaNode.hasNonNull("pickup_min")) {
                            etaMinutes = etaNode.path("pickup_min").asInt();
                        }

                        Double distance = null;
                        var distArray = data.path("distance");
                        if (distArray.isArray() && !distArray.isEmpty() && distArray.get(0).hasNonNull("distance")) {
                            distance = distArray.get(0).path("distance").asDouble() / 1000.0;
                        }

                        Instant expiresAt = Instant.now().plusSeconds(900); // 15 mins
                        return new DeliveryProvider.Quote(quoteId, true, lowestPrice, "INR", etaMinutes, distance,
                                expiresAt, null, request.vehicleType());
                    }
                }
                return DeliveryProvider.Quote.unserviceable("No serviceable delivery networks available from Pidge");
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
        // Pidge refuses an address with no city, state or pincode (400 "is required"), so they are read from our own
        // records and a missing one is named rather than sent blank.
        requireRequest(request.pickupLocality() != null && notBlank(request.pickupLocality().city())
                && notBlank(request.pickupLocality().state()) && notBlank(request.pickupLocality().pincode()),
                "pickup city, state and pincode");
        requireRequest(request.dropLocality() != null && notBlank(request.dropLocality().city())
                && notBlank(request.dropLocality().state()) && notBlank(request.dropLocality().pincode()),
                "drop city, state and pincode");
        var pickupLocality = request.pickupLocality();
        var dropLocality = request.dropLocality();
        String pickupName = notBlank(request.pickupContactName()) ? request.pickupContactName() : "Store Hub";
        // A rider is sent to these numbers, so a missing or malformed one fails the booking by name rather than being
        // replaced with somebody else's (D-121).
        String pickupPhone = indianMobile(request.pickupContactPhone());
        requireRequest(pickupPhone != null, "pickup contact phone (a 10-digit Indian mobile)");
        String dropName = notBlank(request.dropContactName()) ? request.dropContactName() : "Restaurant Partner";
        String dropPhone = indianMobile(request.dropContactPhone());
        requireRequest(dropPhone != null, "drop contact phone (a 10-digit Indian mobile)");
        // The goods' real value, from the order; never a placeholder.
        requireRequest(request.goodsValue() != null && request.goodsValue().signum() > 0, "order value");

        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/order";
        int weightGrams = request.weightKg().multiply(BigDecimal.valueOf(1000)).intValue();
        String sourceOrderId = "SO-" + request.supplierOrderId();

        var payload = Map.of(
                "channel", properties.getChannelName(),
                "sender_detail", Map.of(
                        "name", pickupName,
                        "mobile", pickupPhone,
                        "address", Map.of(
                                "address_line_1", request.pickupAddress(),
                                "city", pickupLocality.city(),
                                "state", pickupLocality.state(),
                                "pincode", pickupLocality.pincode(),
                                "latitude", request.pickupLatitude(),
                                "longitude", request.pickupLongitude()
                        )
                ),
                "poc_detail", Map.of(
                        "name", pickupName,
                        "mobile", pickupPhone
                ),
                "trips", List.of(
                        Map.of(
                                "source_order_id", sourceOrderId,
                                "reference_id", "ref-" + request.idempotencyKey(),
                                "cod_amount", 0,
                                "bill_amount", request.goodsValue().setScale(2, java.math.RoundingMode.HALF_UP),
                                "receiver_detail", Map.of(
                                        "name", dropName,
                                        "mobile", dropPhone,
                                        "address", Map.of(
                                                "address_line_1", request.dropAddress(),
                                                "city", dropLocality.city(),
                                                "state", dropLocality.state(),
                                                "pincode", dropLocality.pincode(),
                                                "latitude", request.dropLatitude(),
                                                "longitude", request.dropLongitude()
                                        )
                                ),
                                "packages", List.of(
                                        Map.of(
                                                "label", "Order #" + request.supplierOrderId(),
                                                "quantity", 1,
                                                "dead_weight", weightGrams,
                                                "volumetric_weight", weightGrams
                                        )
                                )
                        )
                )
        );

        try {
            var entity = new HttpEntity<>(payload, createHeaders());
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                var data = body.path("data");
                String deliveryId = null;
                if (data.has(sourceOrderId)) {
                    deliveryId = data.path(sourceOrderId).asText();
                } else if (data.fieldNames().hasNext()) {
                    deliveryId = data.path(data.fieldNames().next()).asText();
                }

                if (deliveryId == null || deliveryId.isBlank()) {
                    throw missing("delivery_id");
                }

                // Pidge's create-order answer carries no fare or arrival time, so none is stated here: the booking
                // keeps the fare and ETA of the quote that was booked (DeliveryBookingService), never a default.
                String trackingUrl = "https://track.pidge.in/live/" + deliveryId;
                return new DeliveryProvider.Booking(deliveryId, null, "INR", null, null, trackingUrl);
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

        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/order/" + providerDeliveryId;
        try {
            var entity = new HttpEntity<>(createHeaders());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                var data = body.path("data");
                var state = PidgeOrderState.parse(providerDeliveryId, data);
                return new DeliveryProvider.ProviderDelivery(
                        providerDeliveryId, state.status(), state.riderName(), state.riderPhone(), null,
                        null, null, null, null, state.events());
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

        var url = properties.getBaseUrl() + "/v1.0/store/channel/vendor/" + providerDeliveryId + "/cancel";
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

    /** A Pidge stage name as our provider status; see {@link PidgeOrderState#mapStage}. */
    public static DeliveryProvider.ProviderDeliveryStatus mapPidgeStatus(String pidgeStatus) {
        return PidgeOrderState.mapStage(pidgeStatus);
    }
}
