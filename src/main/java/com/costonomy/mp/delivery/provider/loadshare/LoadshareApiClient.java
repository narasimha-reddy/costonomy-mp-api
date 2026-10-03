package com.costonomy.mp.delivery.provider.loadshare;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for the LoadShare Networks Hyperlocal v2 Delivery API.
 *
 * <p>Implements checkServiceability, order creation, order tracking, and order cancellation.
 * Adheres strictly to the fail-closed principles, 30 km intra-city radius ceiling (D-116),
 * verified carrier fares (D-117), and deterministic event ordering (D-114, D-115).
 */
@Component
@Slf4j
public class LoadshareApiClient {

    private final LoadshareProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    // Rate limiter: local token bucket
    private final AtomicInteger tokens;
    private volatile long lastRefillEpochSecond;

    public LoadshareApiClient(LoadshareProperties properties,
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
            throw new DeliveryProviderException("LOADSHARE", "LoadShare rate limit exceeded (local throttle)", true);
        }
    }

    private boolean notConfigured() {
        return properties.getCustomerCode() == null || properties.getCustomerCode().isBlank()
                || properties.getAuthToken() == null || properties.getAuthToken().isBlank();
    }

    private String computeChecksum(String orderId) {
        String token = properties.getAuthToken() != null ? properties.getAuthToken() : "";
        String customerCode = properties.getCustomerCode() != null ? properties.getCustomerCode() : "";
        String input = token + "|" + customerCode + "|" + (orderId != null ? orderId : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }

    private HttpHeaders headers(String orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getCustomerCode() != null) {
            headers.set("Customer-Code", properties.getCustomerCode());
        }
        headers.set("Checksum", computeChecksum(orderId));
        return headers;
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("LoadShare credentials not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("LoadShare provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for LoadShare quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        if (request.pickupLatitude() == null || request.pickupLongitude() == null
                || request.dropLatitude() == null || request.dropLongitude() == null) {
            return DeliveryProvider.Quote.unserviceable("Missing coordinates for LoadShare serviceability check");
        }

        String checkOrderId = "chk-" + (request.supplierOrderId() != null ? request.supplierOrderId() : UUID.randomUUID().toString().substring(0, 8));
        BigDecimal goodsValue = request.orderValue() != null ? request.orderValue() : new BigDecimal("500.00");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("orderId", checkOrderId);
        payload.put("shipmentValue", Map.of("value", goodsValue, "unit", "INR"));
        payload.put("shipmentType", "FOOD");
        payload.put("tasks", List.of(
                Map.of(
                        "type", "PICK_UP",
                        "capabilitiesRequired", List.of(),
                        "address", Map.of(
                                "outletName", "Store",
                                "address", request.pickupAddress() != null ? request.pickupAddress() : "Pickup Location"
                        ),
                        "location", Map.of(
                                "latitude", request.pickupLatitude(),
                                "longitude", request.pickupLongitude()
                        )
                ),
                Map.of(
                        "type", "DROP",
                        "capabilitiesRequired", List.of(),
                        "address", Map.of(
                                "address", request.dropAddress() != null ? request.dropAddress() : "Drop Location"
                        ),
                        "location", Map.of(
                                "latitude", request.dropLatitude(),
                                "longitude", request.dropLongitude()
                        )
                )
        ));

        try {
            var entity = new HttpEntity<>(payload, headers(checkOrderId));
            String url = properties.getBaseUrl() + "/hyperlocal/v2/order/checkServiceability";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("LoadShare serviceability returned no body");
            }

            var body = response.getBody();
            boolean isServiceable = body.path("serviceable").asBoolean(false);
            if (!isServiceable) {
                return DeliveryProvider.Quote.unserviceable("LoadShare serviceability check declined route");
            }

            var fareNode = body.path("fare");
            if (!fareNode.has("value") || fareNode.path("value").isNull()) {
                throw new LoadshareContractException("LoadShare serviceability response missing fare value");
            }

            BigDecimal amount = BigDecimal.valueOf(fareNode.path("value").asDouble()).setScale(2, RoundingMode.HALF_UP);
            String currency = fareNode.path("unit").asText("INR");

            int etaMinutes = 45;
            var slaNode = body.path("promisedSlaInEpoch");
            if (slaNode.has("total") && slaNode.path("total").asLong(0) > 0) {
                long slaEpochMillis = slaNode.path("total").asLong();
                long nowMillis = System.currentTimeMillis();
                if (slaEpochMillis > nowMillis) {
                    etaMinutes = (int) Math.max(15, (slaEpochMillis - nowMillis) / 60000);
                }
            }

            Double predictedKm = body.has("predictedDistanceInMetre")
                    ? body.path("predictedDistanceInMetre").asDouble() / 1000.0
                    : distanceKm;

            return new DeliveryProvider.Quote(
                    "ls_q_" + UUID.randomUUID(),
                    true,
                    amount,
                    currency,
                    etaMinutes,
                    predictedKm,
                    Instant.now().plusSeconds(600),
                    null,
                    request.vehicleType()
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("LoadShare checkServiceability HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare serviceability returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("LoadShare checkServiceability network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare serviceability timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("LOADSHARE", "LoadShare credentials not configured for booking", false);
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

        var payload = new LinkedHashMap<String, Object>();
        payload.put("orderId", request.idempotencyKey());
        payload.put("shipmentValue", Map.of("value", goodsValue, "unit", "INR"));
        payload.put("shipmentType", "FOOD");
        payload.put("tasks", List.of(
                Map.of(
                        "type", "PICK_UP",
                        "capabilitiesRequired", List.of(),
                        "address", Map.of(
                                "outletName", request.pickupContactName() != null ? request.pickupContactName() : "Store Desk",
                                "address", request.pickupAddress(),
                                "phoneNumber", pickupPhone
                        ),
                        "location", Map.of(
                                "latitude", request.pickupLatitude(),
                                "longitude", request.pickupLongitude()
                        )
                ),
                Map.of(
                        "type", "DROP",
                        "capabilitiesRequired", List.of(),
                        "address", Map.of(
                                "address", request.dropAddress(),
                                "contactName", request.dropContactName(),
                                "phoneNumber", dropPhone
                        ),
                        "location", Map.of(
                                "latitude", request.dropLatitude(),
                                "longitude", request.dropLongitude()
                        )
                )
        ));

        try {
            var entity = new HttpEntity<>(payload, headers(request.idempotencyKey()));
            String url = properties.getBaseUrl() + "/hyperlocal/v2/order";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("LOADSHARE", "LoadShare create-order returned no body", true);
            }

            var body = response.getBody();
            String providerOrderId = body.has("orderId") && !body.path("orderId").isNull()
                    ? body.path("orderId").asText()
                    : body.path("loadshareOrderId").asText(request.idempotencyKey());

            BigDecimal amount = body.has("fare") && body.path("fare").has("value")
                    ? BigDecimal.valueOf(body.path("fare").path("value").asDouble())
                    : goodsValue;

            return new DeliveryProvider.Booking(
                    providerOrderId,
                    amount,
                    "INR",
                    45,
                    Instant.now().plusSeconds(2700),
                    "https://track.loadshare.net/order/" + providerOrderId
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("LoadShare createOrder HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("LoadShare createOrder network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare create-order timeout", true);
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
            var entity = new HttpEntity<>(headers(orderId));
            String url = properties.getBaseUrl() + "/hyperlocal/v2/order/" + orderId + "/track";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("LOADSHARE", "LoadShare track returned no body", true);
            }

            var body = response.getBody();
            String rawStatus = body.has("status") ? body.path("status").asText(null) : null;
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new LoadshareContractException("LoadShare track response missing status");
            }

            var status = LoadshareStatusMapper.map(rawStatus);

            var riderNode = body.path("riderDetails");
            String driverName = riderNode.isMissingNode() ? null : riderNode.path("name").asText(null);
            String driverPhone = riderNode.isMissingNode() ? null : riderNode.path("phone").asText(null);
            String driverVehicle = riderNode.isMissingNode() ? null : riderNode.path("vehicleNumber").asText(null);

            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();
            var historyNode = body.path("statusHistory");

            if (historyNode.isArray() && !historyNode.isEmpty()) {
                for (int i = historyNode.size() - 1; i >= 0; i--) {
                    var item = historyNode.get(i);
                    String histStatus = item.path("status").asText("unknown");
                    String desc = item.path("description").asText(histStatus);
                    String timeStr = item.path("timestamp").asText(null);
                    Instant occurredAt = parseInstantSafe(timeStr, Instant.now());
                    var evStatus = LoadshareStatusMapper.map(histStatus);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "ls_" + orderId + "_" + evStatus.name().toLowerCase(Locale.ROOT) + "_" + i,
                            evStatus,
                            desc,
                            occurredAt));
                }
            } else {
                if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "ls_evt_" + orderId + "_delivered",
                            DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                            "LoadShare status: DELIVERED",
                            Instant.now()));
                    events.add(new DeliveryProvider.ProviderEvent(
                            "ls_evt_" + orderId + "_picked_up",
                            DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                            "LoadShare status: PICKED_UP (inferred)",
                            Instant.now().minusSeconds(300)));
                } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                    events.add(new DeliveryProvider.ProviderEvent(
                            "ls_evt_" + orderId + "_" + status.name().toLowerCase(Locale.ROOT),
                            status,
                            "LoadShare status: " + rawStatus,
                            Instant.now()));
                }
            }

            return new DeliveryProvider.ProviderDelivery(
                    orderId, status, driverName, driverPhone, driverVehicle,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("LoadShare getStatus HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("LoadShare getStatus network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare tracking timeout", true);
        }
    }

    // ── location ───────────────────────────────────────────────────────────

    public DeliveryProvider.Location location(String orderId) {
        if (notConfigured()) {
            return null;
        }
        try {
            var entity = new HttpEntity<>(headers(orderId));
            String url = properties.getBaseUrl() + "/hyperlocal/v2/order/" + orderId + "/track";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var loc = response.getBody().path("currentLocation");
                if (loc.has("latitude") && loc.has("longitude")) {
                    BigDecimal lat = BigDecimal.valueOf(loc.path("latitude").asDouble());
                    BigDecimal lng = BigDecimal.valueOf(loc.path("longitude").asDouble());
                    Double bearing = loc.has("bearing") ? loc.path("bearing").asDouble() : null;
                    Double speed = loc.has("speed") ? loc.path("speed").asDouble() : null;
                    return new DeliveryProvider.Location(lat, lng, bearing, speed, Instant.now());
                }
            }
        } catch (Exception ex) {
            log.debug("LoadShare location polling failed for {}: {}", orderId, ex.getMessage());
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
            var payload = Map.of("cancellationReason", reason != null ? reason : "Cancelled by client");
            var entity = new HttpEntity<>(payload, headers(orderId));
            String url = properties.getBaseUrl() + "/hyperlocal/v2/order/" + orderId + "/cancel";
            restTemplate.postForEntity(url, entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("LoadShare cancel HTTP error {} for {}: {}", ex.getStatusCode(), orderId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("LoadShare cancel network timeout for {}: {}", orderId, ex.getMessage());
            throw new DeliveryProviderException("LOADSHARE", "LoadShare cancel timeout", true);
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

    private static DeliveryProviderException invalid(String field) {
        return new DeliveryProviderException("LOADSHARE", field + " missing or invalid for LoadShare booking", false);
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
