package com.costonomy.mp.delivery.provider.borzo;

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
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HTTP client for the Borzo Business API.
 *
 * <p>Live-verified against the sandbox ({@code robotapitest-in.borzodelivery.com})
 * end to end — quote, book, poll, cancel — unlike Pidge, whose contract was never
 * checked against a real response. Every required field below is read with
 * {@code required(...)}, which throws {@link BorzoContractException} rather than
 * substituting a value, so a Borzo response shaped differently than observed
 * fails loudly instead of booking a courier at a fabricated price.
 *
 * <p><b>Two contract quirks, both confirmed live, not assumed:</b>
 * <ul>
 *   <li>Borzo can answer {@code is_successful: true} with an unusable response —
 *       an {@code address}-less point comes back with an empty {@code points}
 *       array and a null {@code delivery_fee_amount}, the real reason sitting in
 *       {@code parameter_warnings}. So the parse here checks the fields it needs
 *       are actually present, not just the top-level flag.</li>
 *   <li>{@code calculate-order} never returns an ETA field. What it does return is
 *       {@code points[].required_finish_datetime} — a per-point deadline that
 *       moves with real route distance (confirmed: a 19 km route pushed it out
 *       further than a 6 km one). The drop point's value, minus now, is used as
 *       {@code etaMinutes} — a provider-reported figure in a different shape, not
 *       a guess.</li>
 * </ul>
 */
@Component
@Slf4j
public class BorzoApiClient {

    private final BorzoProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final AtomicInteger tokens;
    private volatile long lastRefillTime;

    public BorzoApiClient(BorzoProperties properties, RestTemplateBuilder builder, ObjectMapper objectMapper) {
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
            throw new DeliveryProviderException("BORZO",
                    "Borzo API rate limit throttled locally to protect downstream service", true);
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
        headers.set("X-DV-Auth-Token", properties.getAuthToken());
        return headers;
    }

    private boolean notConfigured() {
        return properties.getAuthToken() == null || properties.getAuthToken().isBlank();
    }

    // ── quote ──────────────────────────────────────────────────────────────

    public DeliveryProvider.Quote calculateOrder(DeliveryProvider.QuoteRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            log.debug("Borzo API token not configured; simulating unserviceable quote");
            return DeliveryProvider.Quote.unserviceable("Borzo provider credentials not configured");
        }

        // Only vehicle_type_id 8 (motorbike) has been verified against the
        // sandbox. Guessing an id for a three-wheeler or truck would silently
        // book the wrong vehicle class, so this is a decline, not a fabrication.
        var vehicleType = request.vehicleType() != null ? request.vehicleType() : VehicleType.TWO_WHEELER;
        if (vehicleType != VehicleType.TWO_WHEELER) {
            return DeliveryProvider.Quote.unserviceable(
                    "Borzo vehicle_type_id not verified for " + vehicleType);
        }

        Double haversineKm = Serviceability.distanceKm(
                request.pickupLatitude(), request.pickupLongitude(),
                request.dropLatitude(), request.dropLongitude());
        if (haversineKm != null && haversineKm > 30.0) {
            return DeliveryProvider.Quote.unserviceable(
                    "Exceeds 30 km intra-city radius limit (%.1f km)".formatted(haversineKm));
        }

        if (isBlank(request.pickupAddress()) || isBlank(request.dropAddress())) {
            // Borzo's calculate-order requires an address string per point, even
            // when lat/lng are both present — confirmed live, not assumed.
            return DeliveryProvider.Quote.unserviceable("Borzo requires pickup/drop address text");
        }

        var payload = Map.of(
                "matter", matterFor(request.supplierOrderId()),
                "vehicle_type_id", properties.getVehicleTypeId(),
                "points", List.of(
                        point(request.pickupAddress(), request.pickupLatitude(), request.pickupLongitude(), null, null),
                        point(request.dropAddress(), request.dropLatitude(), request.dropLongitude(), null, null)
                )
        );

        try {
            var response = post("/calculate-order", payload);
            var body = requireSuccessful(response);
            var order = body.path("order");
            var points = requirePoints(order);
            var dropPoint = points.get(points.size() - 1);

            BigDecimal amount = required(order, "payment_amount", "calculate-order")
                    .map(JsonNode::asText).map(BigDecimal::new)
                    .orElseThrow(() -> new BorzoContractException("Borzo calculate-order missing payment_amount"));

            Integer etaMinutes = etaMinutesFromRequiredFinish(dropPoint);
            Double distanceKm = points.stream()
                    .mapToInt(p -> p.path("previous_point_driving_distance_meters").asInt(0))
                    .sum() / 1000.0;

            return new DeliveryProvider.Quote(
                    "borzo_q_" + UUID.randomUUID(),
                    true, amount, "INR", etaMinutes, distanceKm,
                    // Informational only — Borzo recomputes price fresh at
                    // create-order rather than honouring this figure, so there is
                    // no real server-side expiry to report.
                    Instant.now().plusSeconds(300),
                    null, request.vehicleType());

        } catch (HttpStatusCodeException ex) {
            log.warn("Borzo calculate-order HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BORZO", "Borzo HTTP error: " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Borzo calculate-order network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("BORZO", "Borzo connection timeout", true);
        }
    }

    // ── book ───────────────────────────────────────────────────────────────

    public DeliveryProvider.Booking createOrder(DeliveryProvider.BookingRequest request) {
        checkRateLimit();

        if (notConfigured()) {
            throw new DeliveryProviderException("BORZO", "Borzo API token not configured for booking", false);
        }

        var vehicleType = request.vehicleType() != null ? request.vehicleType() : VehicleType.TWO_WHEELER;
        if (vehicleType != VehicleType.TWO_WHEELER) {
            throw new BorzoContractException("Borzo vehicle_type_id not verified for " + vehicleType);
        }

        // The client-supplied reference Borzo echoes back on each point. Used
        // here as our idempotency key (DeliveryBookingService.book() derives one
        // per delivery+attempt) — Borzo has no documented idempotency-key
        // mechanism of its own, so this is the one lever its contract offers:
        // a stable tag we can use to recognise our own attempt on reconciliation,
        // even though it does not confirm server-side deduplication.
        var payload = Map.of(
                "matter", matterFor(request.supplierOrderId()),
                "vehicle_type_id", properties.getVehicleTypeId(),
                "payment_method", "balance",
                "points", List.of(
                        point(request.pickupAddress(), request.pickupLatitude(), request.pickupLongitude(),
                                request.pickupContactName(), request.pickupContactPhone(), request.idempotencyKey()),
                        point(request.dropAddress(), request.dropLatitude(), request.dropLongitude(),
                                request.dropContactName(), request.dropContactPhone(), request.idempotencyKey())
                )
        );

        try {
            var response = post("/create-order", payload);
            var body = requireSuccessful(response);
            var order = body.path("order");
            var points = requirePoints(order);
            var dropPoint = points.get(points.size() - 1);

            String orderId = required(order, "order_id", "create-order")
                    .map(JsonNode::asText)
                    .filter(id -> !id.isBlank())
                    .orElseThrow(() -> new BorzoContractException("Borzo create-order missing order_id"));

            BigDecimal amount = required(order, "payment_amount", "create-order")
                    .map(JsonNode::asText).map(BigDecimal::new)
                    .orElseThrow(() -> new BorzoContractException("Borzo create-order missing payment_amount"));

            Integer etaMinutes = etaMinutesFromRequiredFinish(dropPoint);
            Instant estimatedArrivalAt = etaMinutes == null ? null : Instant.now().plusSeconds(etaMinutes * 60L);
            String trackingUrl = dropPoint.path("tracking_url").isMissingNode() ? null
                    : dropPoint.path("tracking_url").asText(null);

            return new DeliveryProvider.Booking(orderId, amount, "INR", etaMinutes, estimatedArrivalAt, trackingUrl);

        } catch (HttpStatusCodeException ex) {
            log.error("Borzo create-order HTTP error {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BORZO", "Borzo create-order returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.error("Borzo create-order network timeout: {}", ex.getMessage());
            throw new DeliveryProviderException("BORZO", "Borzo create-order timeout", true);
        }
    }

    // ── status ─────────────────────────────────────────────────────────────

    public DeliveryProvider.ProviderDelivery getStatus(String providerDeliveryId) {
        checkRateLimit();

        if (notConfigured()) {
            return new DeliveryProvider.ProviderDelivery(providerDeliveryId,
                    DeliveryProvider.ProviderDeliveryStatus.PENDING, null, null, null, null, null, null, null, List.of());
        }

        // Query-param only — GET /orders/{id} 400s with invalid_api_method,
        // confirmed live.
        var url = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl() + "/orders")
                .queryParam("order_id", providerDeliveryId)
                .toUriString();

        try {
            var entity = new HttpEntity<>(headers());
            var response = restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            var body = requireSuccessful(response);
            var order = body.path("order");

            String orderStatus = required(order, "status", "orders")
                    .map(JsonNode::asText)
                    .orElseThrow(() -> new BorzoContractException("Borzo /orders missing order.status"));

            var points = order.path("points");
            String dropPointStatus = points.isArray() && points.size() > 0
                    ? points.get(points.size() - 1).path("delivery").path("status").asText(null)
                    : null;

            var status = BorzoStatusMapper.map(orderStatus, dropPointStatus);

            // Never observed non-null live — the sandbox order never progressed
            // past "active". Read defensively: a wrong guess at the field name
            // here degrades to null (an honest absence), not a fabricated value.
            var courier = order.path("courier");
            String driverName = courier.isMissingNode() || courier.isNull() ? null : courier.path("name").asText(null);
            String driverPhone = courier.isMissingNode() || courier.isNull() ? null : courier.path("phone").asText(null);
            String driverVehicle = courier.isMissingNode() || courier.isNull() ? null : courier.path("vehicle_number").asText(null);

            Integer etaMinutes = points.isArray() && points.size() > 0
                    ? etaMinutesFromRequiredFinish(points.get(points.size() - 1))
                    : null;

            List<DeliveryProvider.ProviderEvent> events;
            if (status == null || status == DeliveryProvider.ProviderDeliveryStatus.PENDING) {
                events = List.of();
            } else if (status == DeliveryProvider.ProviderDeliveryStatus.DELIVERED) {
                // Borzo reports completed without an explicit pickup event. Include PICKED_UP
                // (newest-first: DELIVERED, then PICKED_UP) so DeliveryJobs applies PICKED_UP first,
                // moving the delivery through PICKED_UP and supplier_order through OUT_FOR_DELIVERY.
                events = List.of(
                        new DeliveryProvider.ProviderEvent(
                                "borzo_" + providerDeliveryId + "_delivered",
                                DeliveryProvider.ProviderDeliveryStatus.DELIVERED,
                                "Borzo order status: " + orderStatus,
                                Instant.now()),
                        new DeliveryProvider.ProviderEvent(
                                "borzo_" + providerDeliveryId + "_picked_up",
                                DeliveryProvider.ProviderDeliveryStatus.PICKED_UP,
                                "Borzo order status: " + orderStatus,
                                Instant.now())
                );
            } else {
                events = List.of(new DeliveryProvider.ProviderEvent(
                        "borzo_" + providerDeliveryId + "_" + status.name().toLowerCase(Locale.ROOT),
                        status,
                        "Borzo order status: " + orderStatus,
                        Instant.now()));
            }

            return new DeliveryProvider.ProviderDelivery(
                    providerDeliveryId, status, driverName, driverPhone, driverVehicle,
                    etaMinutes, null, null, null, events);

        } catch (HttpStatusCodeException ex) {
            log.warn("Borzo getStatus HTTP error {} for {}: {}", ex.getStatusCode(), providerDeliveryId, ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BORZO", "Borzo status returned " + ex.getStatusCode(), ex.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException ex) {
            log.warn("Borzo getStatus network timeout for {}: {}", providerDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("BORZO", "Borzo status timeout", true);
        }
    }

    // ── cancel ─────────────────────────────────────────────────────────────

    public void cancel(String providerDeliveryId, String reason) {
        checkRateLimit();

        if (notConfigured()) {
            return;
        }

        long orderId;
        try {
            orderId = Long.parseLong(providerDeliveryId);
        } catch (NumberFormatException ex) {
            throw new BorzoContractException("Borzo provider_delivery_id is not a Borzo order id: " + providerDeliveryId);
        }

        try {
            post("/cancel-order", Map.of("order_id", orderId));
        } catch (HttpStatusCodeException ex) {
            log.warn("Borzo order cancellation failed for {}: {} {}", providerDeliveryId, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new DeliveryProviderException("BORZO", "Could not cancel on Borzo: " + ex.getStatusCode(), false);
        } catch (ResourceAccessException ex) {
            log.warn("Borzo order cancellation timeout for {}: {}", providerDeliveryId, ex.getMessage());
            throw new DeliveryProviderException("BORZO", "Borzo cancel timeout", true);
        }
    }

    // ── shared parsing/building ───────────────────────────────────────────

    private ResponseEntity<JsonNode> post(String path, Map<String, ?> payload) {
        var entity = new HttpEntity<>(payload, headers());
        return restTemplate.postForEntity(properties.getBaseUrl() + path, entity, JsonNode.class);
    }

    /**
     * Borzo can answer {@code is_successful: true} with an unusable body — an
     * invalid point comes back with an empty {@code points} array rather than a
     * non-2xx status or {@code is_successful: false}. So both are checked.
     */
    private JsonNode requireSuccessful(ResponseEntity<JsonNode> response) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new DeliveryProviderException("BORZO", "Borzo returned no body", true);
        }
        var body = response.getBody();
        if (!body.path("is_successful").asBoolean(false)) {
            throw new BorzoContractException("Borzo reported is_successful=false: " + body.path("warnings"));
        }
        return body;
    }

    private List<JsonNode> requirePoints(JsonNode order) {
        var points = order.path("points");
        if (!points.isArray() || points.isEmpty()) {
            // The address-missing failure mode: is_successful stays true, but
            // points comes back empty and the real reason sits in
            // parameter_warnings on the enclosing response — not visible here,
            // but logged by the caller's HTTP error path when it is a hard error.
            throw new BorzoContractException("Borzo returned no points — likely a rejected point (see parameter_warnings)");
        }
        var result = new ArrayList<JsonNode>();
        points.forEach(result::add);
        return result;
    }

    private Optional<JsonNode> required(JsonNode node, String field, String endpoint) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            log.error("Borzo {} response missing required field '{}'", endpoint, field);
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * Borzo never returns a duration-style ETA. What it returns is a per-point
     * deadline, {@code required_finish_datetime}, that moves with real route
     * distance — confirmed live by comparing a 6 km and a 19 km route. Minutes
     * from now to the drop point's deadline is used as the ETA: a real,
     * provider-computed figure in a different shape, not an invented one.
     */
    private Integer etaMinutesFromRequiredFinish(JsonNode point) {
        var raw = point.path("required_finish_datetime");
        if (raw.isMissingNode() || raw.isNull() || raw.asText().isBlank()) {
            return null;
        }
        try {
            var deadline = OffsetDateTime.parse(raw.asText()).toInstant();
            long minutes = java.time.Duration.between(Instant.now(), deadline).toMinutes();
            return (int) Math.max(1, minutes);
        } catch (Exception ex) {
            log.warn("Borzo required_finish_datetime unparseable: {}", raw.asText());
            return null;
        }
    }

    private Map<String, Object> point(String address, BigDecimal latitude, BigDecimal longitude,
                                      String contactName, String contactPhone) {
        return point(address, latitude, longitude, contactName, contactPhone, null);
    }

    private Map<String, Object> point(String address, BigDecimal latitude, BigDecimal longitude,
                                      String contactName, String contactPhone, String clientOrderId) {
        var map = new LinkedHashMap<String, Object>();
        map.put("address", address);
        if (latitude != null) map.put("latitude", latitude.toPlainString());
        if (longitude != null) map.put("longitude", longitude.toPlainString());
        // Omitted entirely rather than sent blank when we have nothing — a quote
        // request carries no contact details, and Borzo prices it anyway (with a
        // parameter_warning), confirmed live.
        if (!isBlank(contactName) || !isBlank(contactPhone)) {
            map.put("contact_person", Map.of(
                    "name", contactName != null ? contactName : "",
                    "phone", contactPhone != null ? contactPhone : ""));
        }
        if (clientOrderId != null) {
            map.put("client_order_id", clientOrderId);
        }
        return map;
    }

    private String matterFor(Long supplierOrderId) {
        return supplierOrderId != null ? "Mandi order SO-" + supplierOrderId : "Mandi delivery quote";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
