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

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        // No HTTP call: the fare field of Porter's cost endpoint has never been verified against
        // a live response, and the old code guessed three field names and then fell back to a
        // rate card. A decline is honest; a guessed price is not (D-102).
        return DeliveryProvider.Quote.unserviceable(
                "Porter fare contract not verified against a live response; a rate card is not a quote (D-102)");
    }

    // ── book ───────────────────────────────────────────────────────────────

    /**
     * Refuses to book. Porter's create-order response has no verified fare, and
     * {@code Booking.amount} must be a real figure because it becomes delivery.fee and the
     * ledger. This throws before any network call: failing after the carrier has accepted an
     * order would leave a live consignment nobody owns (D-102). Our own data is validated
     * first so a bad request is reported precisely.
     */
    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("PORTER", "Porter API key not configured for booking", false);
        }

        orderPayload(request);

        throw new PorterContractException(
                "Porter create-order fare is not verified; refusing to book without a carrier fare "
                        + "- a rate card is not a quote (D-102)");
    }

    /**
     * The create-order body, built only from our own records. Anything missing is rejected by
     * name; nothing is defaulted (D-102).
     */
    Map<String, Object> orderPayload(DeliveryProvider.BookingRequest request) {
        var vehicleType = request.vehicleType() != null ? request.vehicleType() : VehicleType.TWO_WHEELER;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("request_id", request.idempotencyKey());
        payload.put("pickup_details", Map.of("address", point("pickup", request.pickupAddress(),
                request.pickupLocality(), request.pickupLatitude(), request.pickupLongitude(),
                request.pickupContactName(), request.pickupContactPhone())));
        payload.put("drop_details", Map.of("address", point("drop", request.dropAddress(),
                request.dropLocality(), request.dropLatitude(), request.dropLongitude(),
                request.dropContactName(), request.dropContactPhone())));
        payload.put("vehicle_type", mapVehicleType(vehicleType));
        payload.put("customer", Map.of("name", "Costonomy"));
        return payload;
    }

    private Map<String, Object> point(String which, String address, DeliveryProvider.Locality locality,
                                      BigDecimal lat, BigDecimal lng, String contactName, String contactPhone) {
        if (address == null || address.isBlank()) throw invalid(which + " address");
        if (locality == null || locality.city() == null || locality.city().isBlank()) {
            throw invalid(which + " city");
        }
        if (contactName == null || contactName.isBlank()) throw invalid(which + " contact name");
        var contact = new LinkedHashMap<String, Object>();
        contact.put("name", contactName);
        contact.put("phone_number", requirePhone(which + " contact phone", contactPhone));

        var out = new LinkedHashMap<String, Object>();
        out.put("apartment_address", address);
        out.put("street_address", address);
        out.put("city", locality.city());
        out.put("lat", lat);
        out.put("lng", lng);
        out.put("contact_details", contact);
        return out;
    }

    private static DeliveryProviderException invalid(String field) {
        return new DeliveryProviderException("PORTER", field + " missing or invalid for Porter booking", false);
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
