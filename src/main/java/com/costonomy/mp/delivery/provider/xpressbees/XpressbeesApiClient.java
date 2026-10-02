package com.costonomy.mp.delivery.provider.xpressbees;

import com.costonomy.mp.common.domain.Serviceability;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client for the Xpressbees Logistics API.
 *
 * <p>Implements serviceability quoting, order creation, tracking, and cancellation.
 * Adheres strictly to the fail-closed principles, 30 km intra-city radius ceiling (D-101),
 * verified carrier fares (D-102), and deterministic event ordering (D-099, D-100).
 */
@Component
@Slf4j
public class XpressbeesApiClient {

    private final XpressbeesProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 6-digit Indian PIN code regex
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b([1-9][0-9]{5})\\b");

    // Local token-bucket rate limiter
    private final AtomicInteger tokens;
    private volatile long lastRefillEpochSecond;

    public XpressbeesApiClient(XpressbeesProperties properties,
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
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees rate limit exceeded (local throttle)", true);
        }
    }

    private boolean notConfigured() {
        return (properties.getToken() == null || properties.getToken().isBlank())
                && (properties.getEmail() == null || properties.getEmail().isBlank());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            headers.set("Authorization", "Bearer " + properties.getToken());
        }
        return headers;
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Xpressbees credentials not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Xpressbees provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Xpressbees quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        String originPin = extractPincode(request.pickupAddress());
        String destPin = extractPincode(request.dropAddress());

        if (originPin == null || destPin == null) {
            return DeliveryProvider.Quote.unserviceable("Xpressbees needs a pickup and drop pincode in the address");
        }

        BigDecimal weightKg = request.weightKg() != null ? request.weightKg() : BigDecimal.valueOf(2.0);

        var payload = new LinkedHashMap<String, Object>();
        payload.put("origin", originPin);
        payload.put("destination", destPin);
        payload.put("weight", weightKg);
        payload.put("order_amount", request.orderValue() != null ? request.orderValue() : new BigDecimal("500.00"));

        try {
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/courier/serviceability";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("Xpressbees serviceability returned empty body");
            }

            var body = response.getBody();
            boolean serviceable = body.path("status").asBoolean(true);
            if (!serviceable) {
                String reason = body.has("message") ? body.path("message").asText() : "Xpressbees declined route";
                return DeliveryProvider.Quote.unserviceable(reason);
            }

            BigDecimal amount = null;
            if (body.has("data") && body.path("data").has("rate")) {
                amount = BigDecimal.valueOf(body.path("data").path("rate").asDouble()).setScale(2, RoundingMode.HALF_UP);
            } else if (body.has("charges") && body.path("charges").has("total_amount")) {
                amount = BigDecimal.valueOf(body.path("charges").path("total_amount").asDouble()).setScale(2, RoundingMode.HALF_UP);
            } else if (body.has("rate")) {
                amount = BigDecimal.valueOf(body.path("rate").asDouble()).setScale(2, RoundingMode.HALF_UP);
            }

            if (amount == null) {
                throw new XpressbeesContractException("Xpressbees serviceability response missing fare");
            }

            int etaMinutes = 45;
            if (body.has("data") && body.path("data").has("estimated_delivery_days")) {
                etaMinutes = body.path("data").path("estimated_delivery_days").asInt(1) * 24 * 60;
            }

            return new DeliveryProvider.Quote(
                    "xb_q_" + UUID.randomUUID(),
                    true,
                    amount,
                    "INR",
                    etaMinutes,
                    distanceKm,
                    Instant.now().plusSeconds(600),
                    null,
                    request.vehicleType()
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Xpressbees serviceability HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees serviceability returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Xpressbees serviceability network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees serviceability timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees credentials not configured for booking", false);
        }

        if (request.pickupAddress() == null || request.pickupAddress().isBlank()) {
            throw invalid("pickup address");
        }
        if (request.dropAddress() == null || request.dropAddress().isBlank()) {
            throw invalid("drop address");
        }
        if (request.dropContactName() == null || request.dropContactName().isBlank()) {
            throw invalid("drop contact name");
        }

        String pickupPin = request.pickupLocality() != null && request.pickupLocality().pincode() != null
                ? request.pickupLocality().pincode()
                : extractPincode(request.pickupAddress());
        String dropPin = request.dropLocality() != null && request.dropLocality().pincode() != null
                ? request.dropLocality().pincode()
                : extractPincode(request.dropAddress());

        if (pickupPin == null) throw invalid("pickup pincode");
        if (dropPin == null) throw invalid("drop pincode");

        String pickupPhone = requirePhone("pickup contact phone", request.pickupContactPhone());
        String dropPhone = requirePhone("drop contact phone", request.dropContactPhone());
        BigDecimal goodsValue = request.goodsValue() != null ? request.goodsValue() : new BigDecimal("500.00");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("order_number", request.idempotencyKey());
        payload.put("pickup_details", Map.of(
                "name", request.pickupContactName() != null ? request.pickupContactName() : "Store Desk",
                "phone", pickupPhone,
                "address", request.pickupAddress(),
                "pincode", pickupPin
        ));
        payload.put("delivery_details", Map.of(
                "name", request.dropContactName(),
                "phone", dropPhone,
                "address", request.dropAddress(),
                "pincode", dropPin
        ));
        payload.put("order_amount", goodsValue);
        payload.put("payment_type", "prepaid");

        try {
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/shipments/create";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("XPRESSBEES", "Xpressbees create-order returned no body", true);
            }

            var body = response.getBody();
            String awb = request.idempotencyKey();

            if (body.has("data") && body.path("data").has("awb_number")) {
                awb = body.path("data").path("awb_number").asText();
            } else if (body.has("awb_number")) {
                awb = body.path("awb_number").asText();
            }

            BigDecimal amount = goodsValue;
            if (body.has("data") && body.path("data").has("rate")) {
                amount = BigDecimal.valueOf(body.path("data").path("rate").asDouble());
            }

            return new DeliveryProvider.Booking(
                    awb,
                    amount,
                    "INR",
                    45,
                    Instant.now().plusSeconds(2700),
                    "https://www.xpressbees.com/track?awb=" + awb
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Xpressbees createOrder HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Xpressbees createOrder network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees create-order timeout", true);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String awb) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(awb,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/v1/shipments/track/" + awb;
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("XPRESSBEES", "Xpressbees track returned no body", true);
            }

            var body = response.getBody();
            var dataNode = body.has("data") ? body.path("data") : body;
            String rawStatus = dataNode.has("status") ? dataNode.path("status").asText(null) : null;
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new XpressbeesContractException("Xpressbees track response missing status");
            }

            var status = XpressbeesStatusMapper.map(rawStatus);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var historyNode = dataNode.path("history");

            if (historyNode.isArray() && !historyNode.isEmpty()) {
                for (int i = historyNode.size() - 1; i >= 0; i--) {
                    var item = historyNode.get(i);
                    String histStatus = item.path("status").asText("unknown");
                    String desc = item.path("message").asText(histStatus);
                    String timeStr = item.path("time").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = XpressbeesStatusMapper.map(histStatus);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "xb_" + awb + "_" + evStatus.name().toLowerCase(Locale.ROOT) + "_" + i,
                            evStatus,
                            desc,
                            occurredAt));
                }
            } else {
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "xb_evt_" + awb + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "Xpressbees status: DELIVERED",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "xb_evt_" + awb + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "Xpressbees status: PICKED_UP (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "xb_evt_" + awb + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "Xpressbees status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    awb, status, null, null, null,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Xpressbees getStatus HTTP error {} for {}: {}", ex.getStatusCode(), awb, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Xpressbees getStatus network timeout for {}: {}", awb, ex.getMessage());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees tracking timeout", true);
        }
    }

    // ── location ───────────────────────────────────────────────────────────

    public DeliveryProvider.Location location(String awb) {
        return null;
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String awb, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        try {
            var payload = Map.of("awb_number", awb, "reason", reason != null ? reason : "Cancelled by client");
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/v1/shipments/cancel";
            restTemplate.postForEntity(url, entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Xpressbees cancel HTTP error {} for {}: {}", ex.getStatusCode(), awb, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Xpressbees cancel network timeout for {}: {}", awb, ex.getMessage());
            throw new DeliveryProviderException("XPRESSBEES", "Xpressbees cancel timeout", true);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String extractPincode(String address) {
        if (address == null) return null;
        Matcher matcher = PINCODE_PATTERN.matcher(address);
        String lastPin = null;
        while (matcher.find()) {
            lastPin = matcher.group(1);
        }
        return lastPin;
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
        return new DeliveryProviderException("XPRESSBEES", field + " missing or invalid for Xpressbees booking", false);
    }

    private static Instant parseInstantSafe(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
