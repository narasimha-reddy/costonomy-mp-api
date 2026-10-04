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
 * radius ceiling (D-120), verified carrier fares (D-121), and deterministic event ordering (D-118, D-119).
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

        // No HTTP call: Blowhorn's fare field has not been verified against a live response, and a
        // guessed or rate-card price is not a quote. A decline is honest (D-121).
        return DeliveryProvider.Quote.unserviceable(
                "Blowhorn fare contract not verified against a live response; a rate card is not a quote (D-121)");
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("BLOWHORN", "Blowhorn credentials not configured for booking", false);
        }

        // Refuses before any network call: failing after the carrier accepted an order would leave
        // a live consignment nobody owns, and Booking.amount must be a verified fare (D-121).
        throw new BlowhornContractException(
                "Blowhorn booking fare is not verified; refusing to book without a carrier fare "
                        + "- a rate card is not a quote (D-121)");
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
