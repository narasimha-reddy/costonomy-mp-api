package com.costonomy.mp.delivery.provider.shiprocket;

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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * HTTP client for the Shiprocket Logistics API.
 *
 * <p>Implements courier serviceability querying, adhoc order creation, tracking polling,
 * and order cancellation. Adheres strictly to the fail-closed principles, 30 km intra-city radius ceiling (D-116),
 * and deterministic event ordering (D-114, D-115).
 */
@Component
@Slf4j
public class ShiprocketApiClient {

    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b[1-9][0-9]{5}\\b");

    private final ShiprocketProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Rate limiter: local token bucket
    private final AtomicInteger tokens;
    private volatile long lastRefillEpochSecond;

    // Cached token for email/password login
    private volatile String cachedAuthToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public ShiprocketApiClient(ShiprocketProperties properties,
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
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket rate limit exceeded (local throttle)", true);
        }
    }

    private boolean notConfigured() {
        return (properties.getApiToken() == null || properties.getApiToken().isBlank())
                && (properties.getEmail() == null || properties.getEmail().isBlank());
    }

    private synchronized String getEffectiveToken() {
        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            return properties.getApiToken();
        }

        if (properties.getEmail() == null || properties.getEmail().isBlank()
                || properties.getPassword() == null || properties.getPassword().isBlank()) {
            return null;
        }

        if (cachedAuthToken != null && Instant.now().isBefore(tokenExpiresAt)) {
            return cachedAuthToken;
        }

        try {
            var loginPayload = Map.of(
                    "email", properties.getEmail(),
                    "password", properties.getPassword()
            );
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(loginPayload, headers);

            var resp = restTemplate.postForEntity(properties.getBaseUrl() + "/v1/external/auth/login", entity, JsonNode.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                String token = resp.getBody().path("token").asText(null);
                if (token != null && !token.isBlank()) {
                    cachedAuthToken = token;
                    // Valid for 240 hours; refresh after 230 hours
                    tokenExpiresAt = Instant.now().plusSeconds(230 * 3600);
                    return cachedAuthToken;
                }
            }
            throw new ShiprocketContractException("Shiprocket auth login returned no token");
        } catch (HttpStatusCodeException ex) {
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket auth failed: " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket auth network timeout", true);
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String token = getEffectiveToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Shiprocket credentials not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Shiprocket provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Shiprocket quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        String pickupPincode = extractPincode(request.pickupAddress());
        String dropPincode = extractPincode(request.dropAddress());

        if (pickupPincode == null || dropPincode == null) {
            return DeliveryProvider.Quote.unserviceable(
                    "Shiprocket needs a pickup and drop pincode; none found in the address");
        }

        BigDecimal weight = request.weightKg() != null ? request.weightKg() : BigDecimal.valueOf(1.0);

        try {
            String url = "%s/v1/external/courier/serviceability/?pickup_postcode=%s&delivery_postcode=%s&weight=%s&cod=0"
                    .formatted(properties.getBaseUrl(), pickupPincode, dropPincode, weight.toPlainString());

            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("Shiprocket serviceability returned no body");
            }

            var body = response.getBody();
            var dataNode = body.path("data");
            var couriersNode = dataNode.path("available_courier_companies");

            if (!couriersNode.isArray() || couriersNode.isEmpty()) {
                return DeliveryProvider.Quote.unserviceable(
                        "No Shiprocket courier serviceable for route %s -> %s".formatted(pickupPincode, dropPincode));
            }

            // Find the lowest rate courier
            JsonNode bestCourier = null;
            BigDecimal lowestRate = null;

            for (var courier : couriersNode) {
                if (courier.has("rate")) {
                    BigDecimal rate = BigDecimal.valueOf(courier.path("rate").asDouble());
                    if (lowestRate == null || rate.compareTo(lowestRate) < 0) {
                        lowestRate = rate;
                        bestCourier = courier;
                    }
                }
            }

            if (bestCourier == null || lowestRate == null) {
                return DeliveryProvider.Quote.unserviceable("No courier rates available in Shiprocket serviceability response");
            }

            String courierId = bestCourier.path("courier_company_id").asText("unknown");
            int etaDays = bestCourier.path("estimated_delivery_days").asInt(1);
            int etaMinutes = Math.max(30, etaDays * 240); // intra-city daytime estimate

            return new DeliveryProvider.Quote(
                    "sr_q_" + courierId + "_" + UUID.randomUUID(),
                    true,
                    lowestRate.setScale(2, RoundingMode.HALF_UP),
                    "INR",
                    etaMinutes,
                    distanceKm,
                    Instant.now().plusSeconds(600),
                    null,
                    request.vehicleType());

        } catch (HttpStatusCodeException ex) {
            log.warn("Shiprocket calculateQuote HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket serviceability returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Shiprocket calculateQuote network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket serviceability timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket credentials not configured for booking", false);
        }

        String pickupPincode = extractPincode(request.pickupAddress());
        String dropPincode = extractPincode(request.dropAddress());
        if (pickupPincode == null || dropPincode == null) {
            throw invalid("pickup or drop pincode");
        }
        if (request.dropContactName() == null || request.dropContactName().isBlank()) {
            throw invalid("drop contact name");
        }
        if (request.dropAddress() == null || request.dropAddress().isBlank()) {
            throw invalid("drop address");
        }

        String dropPhone = requirePhone("drop contact phone", request.dropContactPhone());
        BigDecimal weight = request.weightKg() != null ? request.weightKg() : BigDecimal.valueOf(1.0);
        BigDecimal goodsValue = request.goodsValue() != null ? request.goodsValue() : new BigDecimal("500.00");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("order_id", request.idempotencyKey());
        payload.put("order_date", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now()));
        payload.put("pickup_location", "Primary");
        payload.put("billing_customer_name", request.dropContactName());
        payload.put("billing_last_name", "");
        payload.put("billing_address", request.dropAddress());
        payload.put("billing_city", request.dropLocality() != null && request.dropLocality().city() != null ? request.dropLocality().city() : "Bengaluru");
        payload.put("billing_pincode", dropPincode);
        payload.put("billing_state", request.dropLocality() != null && request.dropLocality().state() != null ? request.dropLocality().state() : "Karnataka");
        payload.put("billing_country", "India");
        payload.put("billing_email", "ops@costonomy.com");
        payload.put("billing_phone", dropPhone);
        payload.put("shipping_is_billing", true);
        payload.put("order_items", List.of(Map.of(
                "name", "Consignment " + request.idempotencyKey(),
                "sku", "SO-" + request.supplierOrderId(),
                "units", 1,
                "selling_price", goodsValue.toPlainString()
        )));
        payload.put("payment_method", "Prepaid");
        payload.put("sub_total", goodsValue);
        payload.put("length", 10);
        payload.put("breadth", 10);
        payload.put("height", 10);
        payload.put("weight", weight);

        try {
            var entity = new HttpEntity<>(payload, headers());
            var response = restTemplate.postForEntity(
                    properties.getBaseUrl() + "/v1/external/orders/create/adhoc", entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("SHIPROCKET", "Shiprocket create-order returned no body", true);
            }

            var body = response.getBody();
            String shipmentId = body.has("shipment_id") && !body.path("shipment_id").isNull()
                    ? body.path("shipment_id").asText()
                    : body.path("order_id").asText(null);

            if (shipmentId == null || shipmentId.isBlank()) {
                throw new ShiprocketContractException("Shiprocket create-order response missing shipment_id and order_id");
            }

            BigDecimal amount = body.has("total_amount") && !body.path("total_amount").isNull()
                    ? BigDecimal.valueOf(body.path("total_amount").asDouble())
                    : goodsValue;

            return new DeliveryProvider.Booking(
                    shipmentId,
                    amount,
                    "INR",
                    120,
                    Instant.now().plusSeconds(7200),
                    "https://shiprocket.co/tracking/" + shipmentId
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Shiprocket createOrder HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Shiprocket createOrder network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket create-order timeout", true);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String shipmentId) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(shipmentId,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/v1/external/courier/track/shipment/" + shipmentId;
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("SHIPROCKET", "Shiprocket tracking returned no body", true);
            }

            var body = response.getBody();
            var trackingData = body.has("tracking_data") ? body.path("tracking_data") : body;

            String rawStatus = null;
            if (trackingData.has("shipment_track") && trackingData.path("shipment_track").isArray() && !trackingData.path("shipment_track").isEmpty()) {
                rawStatus = trackingData.path("shipment_track").get(0).path("current_status").asText(null);
            }
            if (rawStatus == null || rawStatus.isBlank()) {
                rawStatus = trackingData.path("current_status").asText(null);
            }
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new ShiprocketContractException("Shiprocket tracking response missing current_status");
            }

            var status = ShiprocketStatusMapper.map(rawStatus);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var activities = trackingData.path("shipment_track_activities");

            if (activities.isArray() && !activities.isEmpty()) {
                for (int i = activities.size() - 1; i >= 0; i--) {
                    var act = activities.get(i);
                    String actDesc = act.path("activity").asText("unknown");
                    String timeStr = act.path("date").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = ShiprocketStatusMapper.map(actDesc);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "sr_" + shipmentId + "_" + evStatus.name().toLowerCase(Locale.ROOT) + "_" + i,
                            evStatus,
                            actDesc,
                            occurredAt));
                }
            } else {
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "sr_evt_" + shipmentId + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "Shiprocket status: DELIVERED",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "sr_evt_" + shipmentId + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "Shiprocket status: PICKED_UP (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "sr_evt_" + shipmentId + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "Shiprocket status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    shipmentId, status, null, null, null,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Shiprocket getStatus HTTP error {} for {}: {}", ex.getStatusCode(), shipmentId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Shiprocket getStatus network timeout for {}: {}", shipmentId, ex.getMessage());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket tracking timeout", true);
        }
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String shipmentId, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        try {
            var payload = Map.of("ids", List.of(shipmentId));
            var entity = new HttpEntity<>(payload, headers());
            restTemplate.postForEntity(properties.getBaseUrl() + "/v1/external/orders/cancel", entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Shiprocket cancel HTTP error {} for {}: {}", ex.getStatusCode(), shipmentId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Shiprocket cancel network timeout for {}: {}", shipmentId, ex.getMessage());
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket cancel timeout", true);
        }
    }

    // ── location ───────────────────────────────────────────────────────────

    public DeliveryProvider.Location location(String providerDeliveryId) {
        return null;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String extractPincode(String address) {
        if (address == null) return null;
        var matcher = PINCODE_PATTERN.matcher(address);
        String last = null;
        while (matcher.find()) {
            last = matcher.group();
        }
        return last;
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
        return new DeliveryProviderException("SHIPROCKET", field + " missing or invalid for Shiprocket booking", false);
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
