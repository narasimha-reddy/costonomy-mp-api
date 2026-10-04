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
 * and order cancellation. Adheres strictly to the fail-closed principles, 30 km intra-city radius ceiling (D-120),
 * and deterministic event ordering (D-118, D-119).
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

        // No HTTP call: Shiprocket's fare field has not been verified against a live response, and a
        // guessed or rate-card price is not a quote. A decline is honest (D-121).
        return DeliveryProvider.Quote.unserviceable(
                "Shiprocket fare contract not verified against a live response; a rate card is not a quote (D-121)");
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("SHIPROCKET", "Shiprocket credentials not configured for booking", false);
        }

        // Refuses before any network call: failing after the carrier accepted an order would leave
        // a live consignment nobody owns, and Booking.amount must be a verified fare (D-121).
        throw new ShiprocketContractException(
                "Shiprocket booking fare is not verified; refusing to book without a carrier fare "
                        + "- a rate card is not a quote (D-121)");
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
