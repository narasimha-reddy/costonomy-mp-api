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

    /**
     * Shadowfax publishes no fare through its API, so this can never return a priced quote:
     * a rate card is not a quote (D-121). It still checks serviceability so a decline says
     * why, which is useful evidence when a rate contract is negotiated.
     */
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

        String pickupPincode = extractPincode(request.pickupAddress());
        String dropPincode = extractPincode(request.dropAddress());

        if (pickupPincode == null || dropPincode == null) {
            return DeliveryProvider.Quote.unserviceable(
                    "Shadowfax needs a pickup and drop pincode; none found in the address");
        }

        if (!checkPincodeServiceability(pickupPincode, dropPincode)) {
            return DeliveryProvider.Quote.unserviceable(
                    "Shadowfax does not serve pincodes %s to %s for Regular".formatted(pickupPincode, dropPincode));
        }

        return DeliveryProvider.Quote.unserviceable(
                "Shadowfax publishes no fare through its API; a rate card is not a quote (D-121)");
    }

    /**
     * Fails closed: true only when Shadowfax itself lists both pincodes with the Regular
     * service. An unreachable or malformed answer throws, so it is recorded as a failure
     * and never counted as serviceable.
     */
    private boolean checkPincodeServiceability(String pickupPincode, String dropPincode) {
        JsonNode body;
        try {
            String url = properties.getBaseUrl() + "/v1/clients/serviceability/?service=Regular&pincodes="
                    + pickupPincode + "," + dropPincode;
            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers()), JsonNode.class);
            body = response.getBody();
        } catch (HttpStatusCodeException ex) {
            throw new DeliveryProviderException("SHADOWFAX",
                    "Shadowfax serviceability returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax serviceability timeout", true);
        }
        if (body == null || !body.isArray()) {
            throw new ShadowfaxContractException("Shadowfax serviceability response is not an array");
        }
        return servesRegular(body, pickupPincode) && servesRegular(body, dropPincode);
    }

    private static boolean servesRegular(JsonNode entries, String pincode) {
        for (var entry : entries) {
            if (pincode.equals(entry.path("code").asText())) {
                var services = entry.path("services");
                if (!services.isArray()) {
                    throw new ShadowfaxContractException(
                            "Shadowfax serviceability entry for " + pincode + " has no services array");
                }
                for (var service : services) {
                    if ("Regular".equals(service.asText())) {
                        return true;
                    }
                }
                return false;
            }
        }
        return false; // not listed means not served
    }

    // ── book ───────────────────────────────────────────────────────────────

    /**
     * Refuses to book. Shadowfax's create-order response carries no fare (only the product
     * value we declared), and {@code Booking.amount} must be a real figure because it becomes
     * delivery.fee and the ledger. This throws before any network call: failing after the
     * carrier has accepted an order would leave a live consignment nobody owns (D-121).
     * Our own data is validated first so a bad request is reported precisely.
     */
    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("SHADOWFAX", "Shadowfax API token not configured for booking", false);
        }

        orderPayload(request);

        throw new ShadowfaxContractException(
                "Shadowfax create-order returns no fare (only product_value, our own declared value); "
                        + "refusing to book without a carrier fare - a rate card is not a quote (D-121)");
    }

    /**
     * The create-order body, built only from our own records. Anything missing is rejected
     * by name; nothing is defaulted (D-121).
     */
    Map<String, Object> orderPayload(DeliveryProvider.BookingRequest request) {
        var pickup = requireLocality("pickup", request.pickupLocality());
        var drop = requireLocality("drop", request.dropLocality());
        if (request.supplierOrderId() == null) {
            throw invalid("supplierOrderId");
        }
        BigDecimal weightKg = positive("weightKg", request.weightKg());
        BigDecimal value = positive("goodsValue", request.goodsValue()).setScale(2, RoundingMode.HALF_UP);
        int weightGrams = weightKg.multiply(BigDecimal.valueOf(1000))
                .setScale(0, RoundingMode.CEILING).intValueExact();

        var payload = new LinkedHashMap<String, Object>();
        payload.put("order_type", properties.getOrderType());

        var orderDetails = new LinkedHashMap<String, Object>();
        orderDetails.put("client_order_id", request.idempotencyKey());
        orderDetails.put("actual_weight", weightGrams);
        orderDetails.put("product_value", value);
        orderDetails.put("payment_mode", "Prepaid");
        orderDetails.put("total_amount", value);
        orderDetails.put("order_service", "regular");
        payload.put("order_details", orderDetails);

        payload.put("customer_details", party("drop", request.dropContactName(), request.dropContactPhone(),
                request.dropAddress(), drop, request.dropLatitude(), request.dropLongitude()));
        payload.put("pickup_details", party("pickup", request.pickupContactName(), request.pickupContactPhone(),
                request.pickupAddress(), pickup, request.pickupLatitude(), request.pickupLongitude()));

        var product = new LinkedHashMap<String, Object>();
        product.put("sku_id", "SO-" + request.supplierOrderId());
        product.put("sku_name", "Consignment SO-" + request.supplierOrderId());
        product.put("price", value);
        product.put("category", "groceries");
        payload.put("product_details", List.of(product));
        return payload;
    }

    private Map<String, Object> party(String which, String name, String phone, String address,
                                      DeliveryProvider.Locality locality, BigDecimal lat, BigDecimal lng) {
        if (name == null || name.isBlank()) throw invalid(which + " contact name");
        if (address == null || address.isBlank()) throw invalid(which + " address");
        var party = new LinkedHashMap<String, Object>();
        party.put("name", name);
        party.put("contact", requirePhone(which + " contact phone", phone));
        party.put("address_line_1", address);
        party.put("city", locality.city());
        party.put("state", locality.state());
        party.put("pincode", Integer.parseInt(locality.pincode()));
        if (lat != null) party.put("latitude", lat.toPlainString());
        if (lng != null) party.put("longitude", lng.toPlainString());
        return party;
    }

    private static DeliveryProvider.Locality requireLocality(String which, DeliveryProvider.Locality l) {
        if (l == null) throw invalid(which + " locality");
        if (l.city() == null || l.city().isBlank()) throw invalid(which + " city");
        if (l.state() == null || l.state().isBlank()) throw invalid(which + " state");
        if (l.pincode() == null || !l.pincode().matches("[1-9][0-9]{5}")) throw invalid(which + " pincode");
        return l;
    }

    private static BigDecimal positive(String field, BigDecimal value) {
        if (value == null || value.signum() <= 0) throw invalid(field);
        return value;
    }

    private static DeliveryProviderException invalid(String field) {
        return new DeliveryProviderException("SHADOWFAX", field + " missing or invalid for Shadowfax booking", false);
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

    /** The last 6-digit pincode in an address: our addresses are built city, state, pincode. */
    private static String extractPincode(String address) {
        if (address == null) return null;
        var matcher = PINCODE_PATTERN.matcher(address);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private static String requirePhone(String field, String phone) {
        if (phone == null) throw invalid(field);
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            digits = digits.substring(2);
        }
        if (digits.length() != 10) throw invalid(field);
        return digits;
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
