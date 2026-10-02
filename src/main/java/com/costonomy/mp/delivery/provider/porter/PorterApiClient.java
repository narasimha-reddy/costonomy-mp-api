package com.costonomy.mp.delivery.provider.porter;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.common.domain.Serviceability;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for Porter Logistics API.
 *
 * <p>Implements quoting/cost estimation, order creation, tracking details,
 * and order cancellation against the Porter API specification.
 */
@Component
@Slf4j
public class PorterApiClient {

    private final PorterProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final AtomicInteger tokens;
    private volatile long lastRefillTime;

    public PorterApiClient(PorterProperties properties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
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
            throw new DeliveryProviderException("PORTER",
                    "Porter API rate limit throttled locally to protect downstream service", true);
        }
    }

    private synchronized void refillTokens() {
        long now = System.currentTimeMillis();
        if (now - lastRefillTime >= 1000) {
            tokens.set(properties.getRateLimitRps());
            lastRefillTime = now;
        }
    }

    private HttpHeaders headers() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String key = properties.getApiKey() != null ? properties.getApiKey() : "";
        headers.set("x-api-key", key);
        headers.set("Authorization", "Bearer " + key);
        return headers;
    }

    private boolean notConfigured() {
        return properties.getApiKey() == null || properties.getApiKey().isBlank();
    }

    private static String mapVehicleType(VehicleType vehicleType) {
        if (vehicleType == null) {
            return "2_wheeler";
        }
        return switch (vehicleType) {
            case TWO_WHEELER -> "2_wheeler";
            case THREE_WHEELER -> "three_wheeler";
            case FOUR_WHEELER_TRUCK -> "tata_ace";
        };
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Porter API key not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Porter provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Porter quote");
        }

        var vehicleType = request.vehicleType() != null ? request.vehicleType() : VehicleType.TWO_WHEELER;
        String porterVehicle = mapVehicleType(vehicleType);

        try {
            var payload = new LinkedHashMap<String, Object>();
            var pickup = new LinkedHashMap<String, Object>();
            pickup.put("lat", request.pickupLatitude());
            pickup.put("lng", request.pickupLongitude());
            pickup.put("address", request.pickupAddress() != null ? request.pickupAddress() : "Pickup Location");
            payload.put("pickup_details", pickup);

            var drop = new LinkedHashMap<String, Object>();
            drop.put("lat", request.dropLatitude());
            drop.put("lng", request.dropLongitude());
            drop.put("address", request.dropAddress() != null ? request.dropAddress() : "Drop Location");
            payload.put("drop_details", drop);

            payload.put("vehicle_type", porterVehicle);

            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/orders/cost";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("Porter cost estimation returned non-200");
            }

            var body = response.getBody();
            BigDecimal amount = null;
            if (body.has("cost") && body.path("cost").has("amount")) {
                amount = new BigDecimal(body.path("cost").path("amount").asText());
            } else if (body.has("fare")) {
                amount = new BigDecimal(body.path("fare").asText());
            } else if (body.has("estimated_fare")) {
                amount = new BigDecimal(body.path("estimated_fare").asText());
            }

            if (amount == null) {
                BigDecimal multiplier = vehicleType == VehicleType.FOUR_WHEELER_TRUCK ? BigDecimal.valueOf(2.5)
                        : (vehicleType == VehicleType.THREE_WHEELER ? BigDecimal.valueOf(1.6) : BigDecimal.ONE);
                amount = properties.getBaseFee()
                        .add(properties.getPerKmFee().multiply(BigDecimal.valueOf(distanceKm)))
                        .multiply(multiplier)
                        .setScale(2, RoundingMode.HALF_UP);
            }

            int etaMinutes = body.has("eta") ? body.path("eta").asInt()
                    : Math.max(15, (int) Math.ceil(distanceKm * 4.0));

            Double routeDistance = body.has("distance") ? body.path("distance").asDouble() : distanceKm;
            String quoteId = body.has("quote_id") ? body.path("quote_id").asText()
                    : "prtr_quote_" + UUID.randomUUID().toString().substring(0, 12);

            Instant expiresAt = Instant.now().plusSeconds(900);

            return new DeliveryProvider.Quote(
                    quoteId,
                    true,
                    amount,
                    "INR",
                    etaMinutes,
                    routeDistance,
                    expiresAt,
                    null,
                    vehicleType);

        } catch (HttpStatusCodeException ex) {
            log.warn("Porter calculateQuote HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            if (ex.getStatusCode().is4xxClientError()) {
                return DeliveryProvider.Quote.unserviceable("Porter rejected route or vehicle: " + ex.getStatusCode());
            }
            throw new DeliveryProviderException("PORTER", "Porter cost calculation failed with " + ex.getStatusCode(), true);
        } catch (ResourceAccessException ex) {
            log.warn("Porter calculateQuote timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("PORTER", "Porter cost calculation timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("PORTER", "Porter API key not configured for booking", false);
        }

        try {
            var vehicleType = request.vehicleType() != null ? request.vehicleType() : VehicleType.TWO_WHEELER;
            var payload = new LinkedHashMap<String, Object>();
            payload.put("request_id", request.idempotencyKey());

            var pickupAddressObj = new LinkedHashMap<String, Object>();
            pickupAddressObj.put("apartment_address", request.pickupAddress() != null ? request.pickupAddress() : "Pickup");
            pickupAddressObj.put("street_address", request.pickupAddress() != null ? request.pickupAddress() : "Pickup");
            pickupAddressObj.put("city", "Bengaluru");
            pickupAddressObj.put("lat", request.pickupLatitude());
            pickupAddressObj.put("lng", request.pickupLongitude());
            var pickupContact = new LinkedHashMap<String, Object>();
            pickupContact.put("name", request.pickupContactName() != null ? request.pickupContactName() : "Supplier");
            pickupContact.put("phone_number", sanitizePhone(request.pickupContactPhone()));
            pickupAddressObj.put("contact_details", pickupContact);

            var pickupDetails = new LinkedHashMap<String, Object>();
            pickupDetails.put("address", pickupAddressObj);
            payload.put("pickup_details", pickupDetails);

            var dropAddressObj = new LinkedHashMap<String, Object>();
            dropAddressObj.put("apartment_address", request.dropAddress() != null ? request.dropAddress() : "Drop");
            dropAddressObj.put("street_address", request.dropAddress() != null ? request.dropAddress() : "Drop");
            dropAddressObj.put("city", "Bengaluru");
            dropAddressObj.put("lat", request.dropLatitude());
            dropAddressObj.put("lng", request.dropLongitude());
            var dropContact = new LinkedHashMap<String, Object>();
            dropContact.put("name", request.dropContactName() != null ? request.dropContactName() : "Outlet");
            dropContact.put("phone_number", sanitizePhone(request.dropContactPhone()));
            dropAddressObj.put("contact_details", dropContact);

            var dropDetails = new LinkedHashMap<String, Object>();
            dropDetails.put("address", dropAddressObj);
            payload.put("drop_details", dropDetails);

            payload.put("vehicle_type", mapVehicleType(vehicleType));

            var customer = new LinkedHashMap<String, Object>();
            customer.put("name", "Costonomy Mandi");
            payload.put("customer", customer);

            var entity = new HttpEntity<>(payload, headers());
            var response = restTemplate.postForEntity(properties.getBaseUrl() + "/v1/orders/create", entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("PORTER", "Porter create-order returned no body", true);
            }

            var body = response.getBody();
            String orderId = body.has("order_id") ? body.path("order_id").asText(null)
                    : (body.has("data") ? body.path("data").path("order_id").asText(null) : null);

            if (orderId == null || orderId.isBlank()) {
                String errorMsg = body.path("message").asText("Unknown error");
                throw new PorterContractException("Porter order creation missing order_id: " + errorMsg);
            }

            Double distanceKm = Serviceability.distanceKm(
                    request.pickupLatitude(), request.pickupLongitude(),
                    request.dropLatitude(), request.dropLongitude());
            int etaMinutes = distanceKm != null ? Math.max(15, (int) Math.ceil(distanceKm * 4.0)) : 30;

            Instant estimatedArrivalAt = Instant.now().plusSeconds(etaMinutes * 60L);
            String trackingUrl = body.has("tracking_url") ? body.path("tracking_url").asText(null)
                    : "https://track.porter.in/" + orderId;

            BigDecimal multiplier = vehicleType == VehicleType.FOUR_WHEELER_TRUCK ? BigDecimal.valueOf(2.5)
                    : (vehicleType == VehicleType.THREE_WHEELER ? BigDecimal.valueOf(1.6) : BigDecimal.ONE);
            BigDecimal fee = properties.getBaseFee()
                    .add(distanceKm != null ? properties.getPerKmFee().multiply(BigDecimal.valueOf(distanceKm)) : BigDecimal.ZERO)
                    .multiply(multiplier)
                    .setScale(2, RoundingMode.HALF_UP);

            return new DeliveryProvider.Booking(orderId, fee, "INR", etaMinutes, estimatedArrivalAt, trackingUrl);

        } catch (HttpStatusCodeException ex) {
            log.error("Porter create-order HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("PORTER", "Porter create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.error("Porter create-order network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("PORTER", "Porter create-order timeout", true);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String orderId) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(orderId,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/v1/orders/" + orderId;
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("PORTER", "Porter tracking returned no body", true);
            }

            var body = response.getBody();
            var dataNode = body.has("data") ? body.path("data") : body;

            String rawStatus = dataNode.path("status").asText(null);
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new PorterContractException("Porter /orders response missing status");
            }

            var status = PorterStatusMapper.map(rawStatus);

            var partner = dataNode.path("partner_details");
            String driverName = partner.isMissingNode() ? null : partner.path("name").asText(null);
            String driverPhone = partner.isMissingNode() ? null : partner.path("mobile").asText(null);
            String driverVehicle = partner.isMissingNode() ? null : partner.path("vehicle_number").asText(null);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var eventsNode = dataNode.path("events");

            if (eventsNode.isArray() && !eventsNode.isEmpty()) {
                // Parse historic events newest-first
                for (int i = eventsNode.size() - 1; i >= 0; i--) {
                    var item = eventsNode.get(i);
                    String evStatusStr = item.path("status").asText("unknown");
                    String evDesc = item.path("description").asText(evStatusStr);
                    String timeStr = item.path("timestamp").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = PorterStatusMapper.map(evStatusStr);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "prtr_" + orderId + "_" + evStatusStr.toLowerCase(Locale.ROOT) + "_" + (item.has("id") ? item.path("id").asText() : i),
                            evStatus,
                            evDesc,
                            occurredAt));
                }
            } else {
                // Synthesize events for DeliveryJobs state transitions:
                // If DELIVERED, include PICKED_UP before DELIVERED (newest-first: DELIVERED, then PICKED_UP)
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "prtr_evt_" + orderId + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "Porter status: delivered",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "prtr_evt_" + orderId + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "Porter status: picked_up (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "prtr_evt_" + orderId + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "Porter status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    orderId, status, driverName, driverPhone, driverVehicle,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Porter getStatus HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("PORTER", "Porter tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Porter getStatus network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("PORTER", "Porter tracking timeout", true);
        }
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String orderId, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        try {
            var payload = Map.of("cancellation_reason", reason != null ? reason : "Cancelled by client");
            var entity = new HttpEntity<>(payload, headers());
            restTemplate.postForEntity(properties.getBaseUrl() + "/v1/orders/" + orderId + "/cancel", entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Porter cancel HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("PORTER", "Porter cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Porter cancel network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("PORTER", "Porter cancel timeout", true);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String sanitizePhone(String phone) {
        if (phone == null) return "+919876543210";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return "+" + digits;
        }
        return "+919876543210";
    }

    private static Instant parseInstantSafe(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ex) {
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }
}
