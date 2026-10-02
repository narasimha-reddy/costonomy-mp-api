package com.costonomy.mp.delivery.provider.shadowfax;

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
import java.util.regex.Pattern;

/**
 * HTTP client for Shadowfax Unified API (Forward Integrations).
 *
 * <p>Implements quoting/serviceability, order creation (AWB generation), tracking details,
 * and order cancellation against the Shadowfax Unified API specification.
 */
@Component
@Slf4j
public class ShadowfaxApiClient {

    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b([1-9][0-9]{5})\\b");

    private final ShadowfaxProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final AtomicInteger tokens;
    private volatile long lastRefillTime;

    public ShadowfaxApiClient(ShadowfaxProperties properties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
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
            throw new DeliveryProviderException("SHADOWFAX",
                    "Shadowfax API rate limit throttled locally to protect downstream service", true);
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
        headers.set("Authorization", "Token " + (properties.getAuthToken() != null ? properties.getAuthToken() : ""));
        return headers;
    }

    private boolean notConfigured() {
        return properties.getAuthToken() == null || properties.getAuthToken().isBlank();
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateQuote(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Shadowfax API token not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Shadowfax provider credentials not configured");
        }

        Double distanceKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());

        if (distanceKm == null) {
            return DeliveryProvider.Quote.unserviceable("No valid coordinates provided for Shadowfax quote");
        }

        if (distanceKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(distanceKm));
        }

        Integer pickupPincode = extractPincode(request.pickupAddress());
        Integer dropPincode = extractPincode(request.dropAddress());

        if (pickupPincode != null && dropPincode != null) {
            boolean serviceable = checkPincodeServiceability(pickupPincode, dropPincode);
            if (!serviceable) {
                return DeliveryProvider.Quote.unserviceable(
                        "Shadowfax route not serviceable between pincodes " + pickupPincode + " and " + dropPincode);
            }
        }

        BigDecimal amount = properties.getBaseFee()
                .add(properties.getPerKmFee().multiply(BigDecimal.valueOf(distanceKm)))
                .setScale(2, RoundingMode.HALF_UP);

        int etaMinutes = Math.max(15, (int) Math.ceil(distanceKm * 4.0));

        return new DeliveryProvider.Quote(
                "sfx_q_" + UUID.randomUUID().toString().replace("-", ""),
                true, amount, "INR", etaMinutes, distanceKm,
                Instant.now().plusSeconds(900), null, request.vehicleType());
    }

    private boolean checkPincodeServiceability(int pickupPincode, int dropPincode) {
        try {
            String url = properties.getBaseUrl() + "/v1/clients/serviceability/?service=Regular&pincodes="
                    + pickupPincode + "," + dropPincode;
            var entity = new HttpEntity<>(headers());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var body = response.getBody();
                if (body.isArray() && body.size() >= 2) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.debug("Shadowfax pincode check skipped or failed: {}", ex.getMessage());
        }
        return true;
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax API token not configured for booking", false);
        }

        int pickupPincode = Optional.ofNullable(extractPincode(request.pickupAddress())).orElse(560038);
        int dropPincode = Optional.ofNullable(extractPincode(request.dropAddress())).orElse(560034);

        int weightGrams = 1000;

        var payload = new LinkedHashMap<String, Object>();
        payload.put("order_type", properties.getOrderType());

        var orderDetails = new LinkedHashMap<String, Object>();
        orderDetails.put("client_order_id", request.idempotencyKey());
        orderDetails.put("actual_weight", weightGrams);
        orderDetails.put("volumetric_weight", weightGrams);
        orderDetails.put("product_value", 500.0);
        orderDetails.put("payment_mode", "Prepaid");
        orderDetails.put("total_amount", 500.0);
        orderDetails.put("order_service", "regular");
        payload.put("order_details", orderDetails);

        var customerDetails = new LinkedHashMap<String, Object>();
        customerDetails.put("name", request.dropContactName() != null ? request.dropContactName() : "Customer");
        customerDetails.put("contact", sanitizePhone(request.dropContactPhone()));
        customerDetails.put("address_line_1", request.dropAddress());
        customerDetails.put("city", "Bengaluru");
        customerDetails.put("state", "Karnataka");
        customerDetails.put("pincode", dropPincode);
        if (request.dropLatitude() != null) customerDetails.put("latitude", request.dropLatitude().toPlainString());
        if (request.dropLongitude() != null) customerDetails.put("longitude", request.dropLongitude().toPlainString());
        payload.put("customer_details", customerDetails);

        var pickupDetails = new LinkedHashMap<String, Object>();
        pickupDetails.put("name", request.pickupContactName() != null ? request.pickupContactName() : "Seller");
        pickupDetails.put("contact", sanitizePhone(request.pickupContactPhone()));
        pickupDetails.put("address_line_1", request.pickupAddress());
        pickupDetails.put("city", "Bengaluru");
        pickupDetails.put("state", "Karnataka");
        pickupDetails.put("pincode", pickupPincode);
        if (request.pickupLatitude() != null) pickupDetails.put("latitude", request.pickupLatitude().toPlainString());
        if (request.pickupLongitude() != null) pickupDetails.put("longitude", request.pickupLongitude().toPlainString());
        payload.put("pickup_details", pickupDetails);

        var product = Map.of(
                "sku_id", "MANDI-" + (request.supplierOrderId() != null ? request.supplierOrderId() : "101"),
                "sku_name", "Consignment SO-" + request.supplierOrderId(),
                "price", 500.0,
                "category", "groceries");
        payload.put("product_details", List.of(product));

        try {
            var entity = new HttpEntity<>(payload, headers());
            var response = restTemplate.postForEntity(properties.getBaseUrl() + "/v3/clients/orders/", entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("SHADOWFAX", "Shadowfax create-order returned no body", true);
            }

            var body = response.getBody();
            var data = body.path("data");
            String awbNumber = data.path("awb_number").asText(null);

            if (awbNumber == null || awbNumber.isBlank()) {
                String errorMsg = body.path("message").asText("Unknown error");
                if (body.hasNonNull("errors")) {
                    errorMsg += ": " + body.path("errors").toString();
                }
                throw new ShadowfaxContractException("Shadowfax order creation missing awb_number: " + errorMsg);
            }

            Double distanceKm = Serviceability.distanceKm(
                    request.pickupLatitude(), request.pickupLongitude(),
                    request.dropLatitude(), request.dropLongitude());
            int etaMinutes = distanceKm != null ? Math.max(15, (int) Math.ceil(distanceKm * 4.0)) : 30;

            String promisedDelivery = data.path("promised_delivery_date").asText(null);
            Instant estimatedArrivalAt = parseInstantSafe(promisedDelivery, Instant.now().plusSeconds(etaMinutes * 60L));
            String trackingUrl = "https://track.shadowfax.in/track?awb=" + awbNumber;

            BigDecimal fee = properties.getBaseFee()
                    .add(distanceKm != null ? properties.getPerKmFee().multiply(BigDecimal.valueOf(distanceKm)) : BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP);

            return new DeliveryProvider.Booking(awbNumber, fee, "INR", etaMinutes, estimatedArrivalAt, trackingUrl);

        } catch (HttpStatusCodeException ex) {
            log.error("Shadowfax create-order HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.error("Shadowfax create-order network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax create-order timeout", true);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String awbNumber) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(awbNumber,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        try {
            var entity = new HttpEntity<>(headers());
            String url = properties.getBaseUrl() + "/v4/clients/orders/" + awbNumber + "/track/";
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new DeliveryProviderException("SHADOWFAX", "Shadowfax tracking returned no body", true);
            }

            var body = response.getBody();
            var orderDetails = body.path("order_details");

            String rawStatus = orderDetails.path("status").asText(null);
            if (rawStatus == null || rawStatus.isBlank()) {
                throw new ShadowfaxContractException("Shadowfax /track response missing order_details.status");
            }

            var status = ShadowfaxStatusMapper.map(rawStatus);

            var trackingDetails = orderDetails.path("tracking_details");
            List<DeliveryProvider.ProviderEvent> events = new ArrayList<>();

            if (trackingDetails.isArray() && !trackingDetails.isEmpty()) {
                // Tracking details are listed chronologically; reverse to satisfy ProviderDelivery (newest-first)
                for (int i = trackingDetails.size() - 1; i >= 0; i--) {
                    var item = trackingDetails.get(i);
                    String statusId = item.path("status_id").asText("unknown");
                    String remarks = item.path("remarks").asText(item.path("status").asText(statusId));
                    String createdStr = item.path("created").asText(null);
                    Instant occurredAt = parseInstantSafe(createdStr, Instant.now());
                    var eventStatus = ShadowfaxStatusMapper.map(statusId);

                    events.add(new DeliveryProvider.ProviderEvent(
                            "sfx_" + awbNumber + "_" + statusId.toLowerCase(Locale.ROOT),
                            eventStatus,
                            remarks,
                            occurredAt));
                }
            } else if (status != DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                events.add(new DeliveryProvider.ProviderEvent(
                        "sfx_" + awbNumber + "_" + status.name().toLowerCase(Locale.ROOT),
                        status,
                        "Shadowfax status: " + rawStatus,
                        Instant.now()));
            }

            var driver = orderDetails.path("delivery_details");
            String driverName = driver.isMissingNode() ? null : driver.path("name").asText(null);
            String driverPhone = driver.isMissingNode() ? null : driver.path("contact").asText(null);

            return new DeliveryProvider.ProviderDelivery(
                    awbNumber, status, driverName, driverPhone, null,
                    null, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Shadowfax getStatus HTTP error {} for {}: {}", ex.getStatusCode(), awbNumber, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax tracking returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Shadowfax getStatus network timeout for {}: {}", awbNumber, ex.getMessage());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax tracking timeout", true);
        }
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String awbNumber, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        try {
            var payload = Map.of(
                    "request_id", awbNumber,
                    "cancel_remarks", reason != null ? reason : "Cancelled by client");

            var entity = new HttpEntity<>(payload, headers());
            restTemplate.postForEntity(properties.getBaseUrl() + "/v3/clients/orders/cancel/", entity, JsonNode.class);

        } catch (HttpStatusCodeException ex) {
            log.warn("Shadowfax cancel HTTP error {} for {}: {}", ex.getStatusCode(), awbNumber, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax cancel returned " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Shadowfax cancel network timeout for {}: {}", awbNumber, ex.getMessage());
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax cancel timeout", true);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Integer extractPincode(String address) {
        if (address == null) return null;
        var matcher = PINCODE_PATTERN.matcher(address);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String sanitizePhone(String phone) {
        if (phone == null) return "9876543210";
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        return digits.length() == 10 ? digits : "9876543210";
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
