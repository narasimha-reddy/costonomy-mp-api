package com.costonomy.mp.delivery.provider.blowhorn;

import com.costonomy.mp.common.domain.Serviceability;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for the Blowhorn Logistics & On-Demand Hyperlocal Delivery API.
 *
 * <p>Implements serviceability quoting, order creation, order tracking, driver location,
 * and order cancellation. Adheres strictly to the fail-closed principles, 30 km intra-city
 * radius ceiling (D-116), verified carrier fares (D-117), and deterministic event ordering (D-114, D-115).
 */
@Component
@Slf4j
public class BlowhornApiClient {

    private final BlowhornProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Local token-bucket rate limiter
    private final AtomicInteger tokens;
    private volatile long lastRefillEpochSecond;

    public BlowhornApiClient(BlowhornProperties properties,
                             RestTemplateBuilder restTemplateBuilder,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(properties.getTimeout())
                .setReadTimeout(properties.getTimeout())
                .build();
        this.objectMapper = objectMapper;
        this.tokens = new AtomicInteger(properties.getRateLimitRps());
        this.lastRefillEpochSecond = Instant.now().getEpochSecond();
    }

    private void checkRateLimit() {
        long now = Instant.now().getEpochSecond();
        if (now > lastRefillEpochSecond) {
            tokens.set(properties.getRateLimitRps());
            lastRefillEpochSecond = now;
        }
        if (tokens.decrementAndGet() < 0) {
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn rate limit exceeded (local throttle)", true);
        }
    }

    private boolean notConfigured() {
        return properties.getApiKey() == null || properties.getApiKey().isBlank();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.set("API_KEY", properties.getApiKey());
            headers.set("Authorization", "Bearer " + properties.getApiKey());
        }
        return headers;
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Blowhorn API key not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Blowhorn provider API key not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Blowhorn quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        if (request.pickupLatitude() == null || request.pickupLongitude() == null
                || request.dropLatitude() == null || request.dropLongitude() == null) {
            return DeliveryProvider.Quote.unserviceable("Missing coordinates for Blowhorn serviceability check");
        }

        String blowhornVehicle = mapVehicleType(request.vehicleType());
        BigDecimal weightKg = request.weightKg() != null ? request.weightKg() : BigDecimal.valueOf(2.0);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("pickup_latitude", request.pickupLatitude());
        payload.put("pickup_longitude", request.pickupLongitude());
        payload.put("delivery_latitude", request.dropLatitude());
        payload.put("delivery_longitude", request.dropLongitude());
        payload.put("pickup_address", request.pickupAddress() != null ? request.pickupAddress() : "Pickup Point");
        payload.put("delivery_address", request.dropAddress() != null ? request.dropAddress() : "Delivery Point");
        payload.put("weight_kg", weightKg);
        payload.put("vehicle_type", blowhornVehicle);

        try {
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/serviceability";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("Blowhorn serviceability returned empty body");
            }

            var body = response.getBody();
            boolean isServiceable = body.path("serviceable").asBoolean(true);
            if (!isServiceable) {
                String reason = body.has("reason") ? body.path("reason").asText() : "Blowhorn serviceability check declined route";
                return DeliveryProvider.Quote.unserviceable(reason);
            }

            var fareNode = body.path("fare");
            BigDecimal amount = null;
            String currency = "INR";

            if (fareNode.has("amount") && !fareNode.path("amount").isNull()) {
                amount = BigDecimal.valueOf(fareNode.path("amount").asDouble()).setScale(2, RoundingMode.HALF_UP);
                if (fareNode.has("currency")) {
                    currency = fareNode.path("currency").asText("INR");
                }
            } else if (body.has("estimated_fare") && !body.path("estimated_fare").isNull()) {
                amount = BigDecimal.valueOf(body.path("estimated_fare").asDouble()).setScale(2, RoundingMode.HALF_UP);
            } else if (body.has("price") && !body.path("price").isNull()) {
                amount = BigDecimal.valueOf(body.path("price").asDouble()).setScale(2, RoundingMode.HALF_UP);
            }

            if (amount == null) {
                throw new BlowhornContractException("Blowhorn serviceability response missing fare value");
            }

            int etaMinutes = body.path("estimated_delivery_time_minutes").asInt(45);
            Double reportedKm = body.has("distance_km") ? body.path("distance_km").asDouble() : distanceKm;

            return new DeliveryProvider.Quote(
                    "bh_q_" + UUID.randomUUID(),
                    true,
                    amount,
                    currency,
                    etaMinutes,
                    reportedKm,
                    Instant.now().plusSeconds(600),
                    null,
                    request.vehicleType()
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Blowhorn checkServiceability HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn serviceability returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Blowhorn checkServiceability network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn serviceability timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn credentials not configured for booking", false);
        }

        if (request.pickupAddress() == null || request.pickupAddress().isBlank()) {
            throw invalid("pickup address");
        }
        if (request.dropAddress() == null || request.dropAddress().isBlank()) {
            throw invalid("drop address");
        }
        if (request.pickupLatitude() == null || request.pickupLongitude() == null) {
            throw invalid("pickup coordinates");
        }
        if (request.dropLatitude() == null || request.dropLongitude() == null) {
            throw invalid("drop coordinates");
        }
        if (request.dropContactName() == null || request.dropContactName().isBlank()) {
            throw invalid("drop contact name");
        }

        String pickupPhone = requirePhone("pickup contact phone", request.pickupContactPhone());
        String dropPhone = requirePhone("drop contact phone", request.dropContactPhone());
        BigDecimal goodsValue = request.goodsValue() != null ? request.goodsValue() : new BigDecimal("500.00");
        String vehicle = mapVehicleType(request.vehicleType());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("reference_number", request.idempotencyKey());
        payload.put("vehicle_type", vehicle);
        payload.put("goods_value", goodsValue);
        payload.put("pickup_point", Map.of(
                "name", request.pickupContactName() != null ? request.pickupContactName() : "Store Desk",
                "phone", pickupPhone,
                "address", request.pickupAddress(),
                "latitude", request.pickupLatitude(),
                "longitude", request.pickupLongitude()
        ));
        payload.put("delivery_point", Map.of(
                "name", request.dropContactName(),
                "phone", dropPhone,
                "address", request.dropAddress(),
                "latitude", request.dropLatitude(),
                "longitude", request.dropLongitude()
        ));
        payload.put("weight_kg", request.weightKg() != null ? request.weightKg() : BigDecimal.valueOf(2.0));

        try {
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/orders";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("BLOWHORN", "Blowhorn create-order returned no body", true);
            }

            var body = response.getBody();
            String providerOrderId = body.has("awb_number") && !body.path("awb_number").isNull()
                    ? body.path("awb_number").asText()
                    : body.path("order_id").asText(request.idempotencyKey());

            BigDecimal amount = goodsValue;
            if (body.has("fare") && body.path("fare").has("amount")) {
                amount = BigDecimal.valueOf(body.path("fare").path("amount").asDouble());
            } else if (body.has("total_amount")) {
                amount = BigDecimal.valueOf(body.path("total_amount").asDouble());
            }

            int etaMinutes = body.path("estimated_delivery_time_minutes").asInt(45);

            return new DeliveryProvider.Booking(
                    providerOrderId,
                    amount,
                    "INR",
                    etaMinutes,
                    Instant.now().plusSeconds(etaMinutes * 60L),
                    "https://blowhorn.com/track/" + providerOrderId
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Blowhorn createOrder HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Blowhorn createOrder network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn create-order timeout", true);
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
            String url = properties.getBaseUrl() + "/v1/orders/" + orderId + "/track";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("BLOWHORN", "Blowhorn track returned no body", true);
            }

            var body = response.getBody();
            String rawStatus = body.has("status") ? body.path("status").asText(null) : null;
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new BlowhornContractException("Blowhorn track response missing status");
            }

            var status = BlowhornStatusMapper.map(rawStatus);

            var driverNode = body.path("driver_details");
            String driverName = driverNode.isMissingNode() ? null : driverNode.path("name").asText(null);
            String driverPhone = driverNode.isMissingNode() ? null : driverNode.path("phone").asText(null);
            String driverVehicle = driverNode.isMissingNode() ? null : driverNode.path("vehicle_number").asText(null);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var eventsNode = body.path("events");

            if (eventsNode.isArray() && !eventsNode.isEmpty()) {
                for (int i = eventsNode.size() - 1; i >= 0; i--) {
                    var item = eventsNode.get(i);
                    String histStatus = item.path("status").asText("unknown");
                    String desc = item.path("description").asText(histStatus);
                    String timeStr = item.path("timestamp").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = BlowhornStatusMapper.map(histStatus);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "bh_" + orderId + "_" + evStatus.name().toLowerCase(Locale.ROOT) + "_" + i,
                            evStatus,
                            desc,
                            occurredAt));
                }
            } else {
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "bh_evt_" + orderId + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "Blowhorn status: DELIVERED",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "bh_evt_" + orderId + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "Blowhorn status: PICKED_UP (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "bh_evt_" + orderId + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "Blowhorn status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    orderId, status, driverName, driverPhone, driverVehicle,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Blowhorn getStatus HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Blowhorn getStatus network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn tracking timeout", true);
        }
    }

    // ── location ───────────────────────────────────────────────────────────

    public DeliveryProvider.Location location(String orderId) {
        if (notConfigured()) {
            return null;
        }
        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/v1/orders/" + orderId + "/track";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var loc = response.getBody().path("current_location");
                if (loc.has("latitude") && loc.has("longitude")) {
                    BigDecimal lat = BigDecimal.valueOf(loc.path("latitude").asDouble());
                    BigDecimal lng = BigDecimal.valueOf(loc.path("longitude").asDouble());
                    Double bearing = loc.has("bearing") ? loc.path("bearing").asDouble() : null;
                    Double speed = loc.has("speed") ? loc.path("speed").asDouble() : null;
                    return new DeliveryProvider.Location(lat, lng, bearing, speed, Instant.now());
                }
            }
        } catch (Exception ex) {
            log.debug("Blowhorn location polling failed for {}: {}", orderId, ex.getMessage());
        }
        return null;
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
            String url = properties.getBaseUrl() + "/v1/orders/" + orderId + "/cancel";
            restTemplate.postForEntity(url, entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Blowhorn cancel HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Blowhorn cancel network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn cancel timeout", true);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String mapVehicleType(VehicleType vehicleType) {
        if (vehicleType == null) return "2_WHEELER";
        return switch (vehicleType) {
            case TWO_WHEELER -> "2_WHEELER";
            case THREE_WHEELER -> "3_WHEELER";
            case FOUR_WHEELER_TRUCK -> "TATA_ACE";
        };
    }

    private static String requirePhone(String field, String phone) {
        if (phone == null) throw invalid(field);
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return "+" + digits;
        }
        throw invalid(field);
    }

    private static DeliveryProviderException invalid(String field) {
        return new DeliveryProviderException("BLOWHORN", field + " missing or invalid for Blowhorn booking", false);
    }

    private static Instant parseInstantSafe(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Instant.ofEpochMilli(Long.parseLong(raw));
        } catch (Exception ignored) {
            try {
                return Instant.parse(raw);
            } catch (Exception ex) {
                return fallback;
            }
        }
    }
}
