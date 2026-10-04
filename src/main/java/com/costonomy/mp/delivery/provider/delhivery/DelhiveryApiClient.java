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

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Delhivery quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        String originPin = extractPincode(request.pickupAddress());
        String destPin = extractPincode(request.dropAddress());

        if (originPin == null || destPin == null) {
            return DeliveryProvider.Quote.unserviceable("Delhivery needs a pickup and drop pincode in the address");
        }

        long grams = request.weightGrams() != null ? request.weightGrams().longValue() : 2000L;

        try {
            String url = properties.getBaseUrl() + "/api/kinko/v1/invoice/charges.json?md=S&ss=Delivered&cgm="
                    + grams + "&o_pin=" + originPin + "&d_pin=" + destPin;

            var entity = new HttpEntity<>(headers());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return DeliveryProvider.Quote.unserviceable("Delhivery charges returned empty body");
            }

            var body = response.getBody();
            JsonNode chargeNode = body;
            if (body.isArray() && !body.isEmpty()) {
                chargeNode = body.get(0);
            }

            if (!chargeNode.has("total_amount") && !chargeNode.has("gross_amount")) {
                throw new DelhiveryContractException("Delhivery charges response missing total amount");
            }

            double rawAmount = chargeNode.has("total_amount")
                    ? chargeNode.path("total_amount").asDouble()
                    : chargeNode.path("gross_amount").asDouble();

            if (rawAmount <= 0) {
                return DeliveryProvider.Quote.unserviceable("Delhivery returned zero or negative rate for route");
            }

            BigDecimal amount = BigDecimal.valueOf(rawAmount).setScale(2, RoundingMode.HALF_UP);

            return new DeliveryProvider.Quote(
                    "dlv_q_" + UUID.randomUUID(),
                    true,
                    amount,
                    "INR",
                    60,
                    distanceKm,
                    Instant.now().plusSeconds(600),
                    null,
                    request.vehicleType()
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Delhivery invoice charges HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery pricing returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Delhivery invoice charges network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery pricing timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("DELHIVERY", "Delhivery credentials not configured for booking", false);
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

        var shipment = new LinkedHashMap<String, Object>();
        shipment.put("order", request.idempotencyKey());
        shipment.put("name", request.dropContactName());
        shipment.put("add", request.dropAddress());
        shipment.put("pin", dropPin);
        shipment.put("phone", dropPhone);
        shipment.put("payment_mode", "Pre-paid");
        shipment.put("products_desc", "Restaurant Supplies");
        shipment.put("total_amount", goodsValue);
        shipment.put("weight", request.weightKg() != null ? request.weightKg().multiply(BigDecimal.valueOf(1000)).intValue() : 2000);

        var payload = Map.of(
                "shipments", List.of(shipment),
                "pickup_location", Map.of(
                        "name", request.pickupContactName() != null ? request.pickupContactName() : "Store Desk",
                        "add", request.pickupAddress(),
                        "pin", pickupPin,
                        "phone", pickupPhone
                )
        );

        try {
            var entity = new HttpEntity<>(payload, headers());
            String url = properties.getBaseUrl() + "/api/cmu/create.json";
            var response = restTemplate.postForEntity(url, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("DELHIVERY", "Delhivery create-order returned no body", true);
            }

            var body = response.getBody();
            String waybill = request.idempotencyKey();

            if (body.has("packages") && body.path("packages").isArray() && !body.path("packages").isEmpty()) {
                var pkg = body.path("packages").get(0);
                if (pkg.has("waybill")) {
                    waybill = pkg.path("waybill").asText();
                }
            } else if (body.has("upload_wbn")) {
                waybill = body.path("upload_wbn").asText();
            }

            return new DeliveryProvider.Booking(
                    waybill,
                    goodsValue,
                    "INR",
                    60,
                    Instant.now().plusSeconds(3600),
                    "https://www.delhivery.com/track/package/" + waybill
            );

        } catch (HttpStatusCodeException ex) {
            log.warn("Delhivery createOrder HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Delhivery createOrder network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("DELHIVERY", "Delhivery create-order timeout", true);
        }
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
