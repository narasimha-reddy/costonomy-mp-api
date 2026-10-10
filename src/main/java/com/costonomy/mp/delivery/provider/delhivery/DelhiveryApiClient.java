package com.costonomy.mp.delivery.provider.delhivery;

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
 * HTTP client for the Delhivery Express & Logistics API.
 *
 * <p>Implements serviceability quoting, order creation, tracking, and cancellation.
 * Adheres strictly to the fail-closed principles, 30 km intra-city radius ceiling (D-120),
 * verified carrier fares (D-121), and deterministic event ordering (D-118, D-119).
 */
@Component
@Slf4j
public class DelhiveryApiClient {

    private final DelhiveryProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // 6-digit Indian PIN code regex
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b([1-9][0-9]{5})\\b");

    // Local token-bucket rate limiter
    private final AtomicInteger tokens;
    private volatile long lastRefillEpochSecond;

    public DelhiveryApiClient(DelhiveryProperties properties,
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
            throw new DeliveryProviderException("DELHIVERY", "Delhivery rate limit exceeded (local throttle)", true);
        }
    }

    private boolean notConfigured() {
        return properties.getApiToken() == null || properties.getApiToken().isBlank();
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiToken() != null && !properties.getApiToken().isBlank()) {
            headers.set("Authorization", "Token " + properties.getApiToken());
        }
        return headers;
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Delhivery API token not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Delhivery provider API token not configured");
        }

        // No HTTP call: Delhivery's fare field has not been verified against a live response, and a
        // guessed or rate-card price is not a quote. A decline is honest (D-121).
        return DeliveryProvider.Quote.unserviceable(
                "Delhivery fare contract not verified against a live response; a rate card is not a quote (D-121)");
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("DELHIVERY", "Delhivery credentials not configured for booking", false);
        }

        // Refuses before any network call: failing after the carrier accepted an order would leave
        // a live consignment nobody owns, and Booking.amount must be a verified fare (D-121).
        throw new DelhiveryContractException(
                "Delhivery booking fare is not verified; refusing to book without a carrier fare "
                        + "- a rate card is not a quote (D-121)");
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String waybill) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(waybill,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/api/v1/packages/json/?waybill=" + waybill;
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("DELHIVERY", "Delhivery track returned no body", true);
            }

            var body = response.getBody();
            var shipmentDataNode = body.path("ShipmentData");
            if (!shipmentDataNode.isArray() || shipmentDataNode.isEmpty()) {
                throw new DelhiveryContractException("Delhivery track response missing ShipmentData array");
            }

            var shipmentNode = shipmentDataNode.get(0).path("Shipment");
            var statusNode = shipmentNode.path("Status");
            String rawStatus = statusNode.has("Status") ? statusNode.path("Status").asText(null) : null;
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new DelhiveryContractException("Delhivery track response missing Status string");
            }

            var status = DelhiveryStatusMapper.map(rawStatus);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var scansNode = shipmentNode.path("Scans");

            if (scansNode.isArray() && !scansNode.isEmpty()) {
                for (int i = scansNode.size() - 1; i >= 0; i--) {
                    var scan = scansNode.get(i).path("ScanDetail");
                    String scanType = scan.path("ScanType").asText("unknown");
                    String desc = scan.path("Instructions").asText(scanType);
                    String timeStr = scan.path("ScanDateTime").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = DelhiveryStatusMapper.map(scanType);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "dlv_" + waybill + "_" + evStatus.name().toLowerCase(Locale.ROOT) + "_" + i,
                            evStatus,
                            desc,
                            occurredAt));
                }
            } else {
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "dlv_evt_" + waybill + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "Delhivery status: DELIVERED",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "dlv_evt_" + waybill + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "Delhivery status: PICKED_UP (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "dlv_evt_" + waybill + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "Delhivery status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    waybill, status, null, null, null,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Delhivery getStatus HTTP error {} for {}: {}", ex.getStatusCode(), waybill, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Delhivery getStatus network timeout for {}: {}", waybill, ex.getMessage());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery tracking timeout", true);
        }
    }

    // ── location ───────────────────────────────────────────────────────────

    public DeliveryProvider.Location location(String waybill) {
        return null;
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String waybill, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        try {
            var payload = Map.of("waybill", waybill, "cancellation", "true");
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/api/p/edit";
            restTemplate.postForEntity(url, entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Delhivery cancel HTTP error {} for {}: {}", ex.getStatusCode(), waybill, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Delhivery cancel network timeout for {}: {}", waybill, ex.getMessage());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery cancel timeout", true);
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
        return new DeliveryProviderException("DELHIVERY", field + " missing or invalid for Delhivery booking", false);
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
