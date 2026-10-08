package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeApiClient;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-188: the test-only "move the sandbox rider to the next step" route and the flag that offers it. */
@AutoConfigureMockMvc
class PidgeSandboxAdvanceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PidgeProperties properties;
    @Autowired private com.costonomy.mp.delivery.provider.pidge.PidgeWebhookService webhook;
    @MockBean private PidgeApiClient pidge;

    private ApiClient api;
    private String buyerToken;
    private String supplierToken;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        properties.setSandbox(true);
    }

    @AfterEach
    void tearDown() {
        properties.setSandbox(false);
    }

    private Long seed(String providerDeliveryId, String mode, String status) throws Exception {
        String buyerPhone = ApiClient.freshPhone();
        buyerToken = api.login(buyerPhone);
        JsonNode r = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Sandbox Test Restaurant",
                "firstOutlet", Map.of(
                        "name", "Indiranagar", "addressLine1", "100 Feet Rd", "city", "Bengaluru",
                        "state", "Karnataka", "pincode", "560038",
                        "latitude", "12.9716", "longitude", "77.5946"))).get("data");
        long outletId = r.get("outlets").get(0).get("id").asLong();
        Long buyerUserId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + buyerPhone);

        supplierToken = api.login(ApiClient.freshPhone());
        JsonNode s = api.post(supplierToken, "/api/v1/suppliers", Map.of(
                "legalName", "Sandbox Supplier " + System.nanoTime() + " Pvt Ltd",
                "displayName", "Sandbox Supplier", "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of(
                        "name", "Koramangala Store", "contactName", "Store Desk", "contactPhone", "+919876500000",
                        "addressLine1", "80 Feet Rd", "city", "Bengaluru", "state", "Karnataka",
                        "latitude", "12.9352", "longitude", "77.6245"))).get("data");
        long supplierOrgId = s.get("id").asLong();
        long storeId = s.get("stores").get(0).get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', verification_status = 'VERIFIED' where id = ?", supplierOrgId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierOrgId);

        jdbc.update("""
                insert into procurement (outlet_id, created_by, status, approval_status,
                                         payment_method, payment_status, total_amount, created_at, updated_at, version)
                values (?, ?, 'SUBMITTED', 'NOT_REQUIRED', 'PREPAID', 'CAPTURED', 500.00, now(6), now(6), 0)
                """, outletId, buyerUserId);
        Long procurementId = jdbc.queryForObject(
                "select id from procurement where outlet_id = ? order by id desc limit 1", Long.class, outletId);
        String orderNumber = "SO-" + System.nanoTime();
        jdbc.update("""
                insert into supplier_order (procurement_id, supplier_store_id, outlet_id,
                                            order_number, status, total_amount, accepted_amount,
                                            delivery_mode, payment_method, payment_status, created_at, updated_at, version)
                values (?, ?, ?, ?, 'READY_FOR_PICKUP', 500.00, 500.00, 'COSTONOMY_DELIVERY', 'PREPAID', 'CAPTURED', now(6), now(6), 0)
                """, procurementId, storeId, outletId, orderNumber);
        Long supplierOrderId = jdbc.queryForObject(
                "select id from supplier_order where order_number = ?", Long.class, orderNumber);
        jdbc.update("""
                insert into delivery (supplier_order_id, outlet_id, supplier_store_id, mode, status,
                                      provider_code, provider_delivery_id, fee, currency,
                                      pickup_address, pickup_latitude, pickup_longitude,
                                      drop_address, drop_latitude, drop_longitude, requested_at, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 'PIDGE', ?, 65.0000, 'INR',
                        'Pickup Point, Bengaluru', 12.9352000, 77.6245000,
                        'Drop Point, Bengaluru', 12.9716000, 77.5946000, now(6), now(6), now(6), 0)
                """, supplierOrderId, outletId, storeId, mode, status, providerDeliveryId);
        return jdbc.queryForObject("select id from delivery where provider_delivery_id = ?", Long.class,
                providerDeliveryId);
    }

    private JsonNode dummyOrder(String providerDeliveryId) throws Exception {
        return json.readTree("""
                {"data": {"id": "%s", "status": "fulfilled", "fulfillment": {"status": "OUT_FOR_PICKUP", "logs": [
                  {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
                  {"timestamp": "2026-10-06T10:02:00.000Z", "status": "OUT_FOR_PICKUP",
                   "location": {"latitude": 17.44, "longitude": 78.49},
                   "rider": {"id": "306", "name": "Sandbox Sunil", "mobile": "9876543210"}}]}}}
                """.formatted(providerDeliveryId));
    }

    /** Pidge's dummy answer for a stage: always the same Gurugram point, stamped a few minutes ahead. */
    private JsonNode dummyAt(String providerDeliveryId, String stage) throws Exception {
        return json.readTree("""
                {"data": {"id": "%s", "status": "fulfilled", "fulfillment": {"status": "%s", "logs": [
                  {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
                  {"timestamp": "%s", "status": "%s",
                   "location": {"latitude": 28.4425540, "longitude": 77.0802300},
                   "rider": {"id": "306", "name": "Sandbox Sunil", "mobile": "9876543210"}}]}}}
                """.formatted(providerDeliveryId, stage, Instant.now().plusSeconds(180), stage));
    }

    private double metres(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double a = Math.pow(Math.sin((p2 - p1) / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.sqrt(a));
    }

    private int advance(String token, Long deliveryId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/deliveries/" + deliveryId + "/sandbox/advance")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode advanceBody(String token, Long deliveryId) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.post("/api/v1/deliveries/" + deliveryId + "/sandbox/advance")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void advanceMovesTheDeliveryAndTakesTheRiderFromTheDummyOrder() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|out for pickup"))).thenReturn(dummyOrder(pid));

        assertThat(api.get(supplierToken, "/api/v1/deliveries/" + id).at("/data/sandboxControls").asBoolean()).isTrue();

        JsonNode response = advanceBody(supplierToken, id);
        assertThat(response.at("/data/status").asText()).isEqualTo("DRIVER_ASSIGNED");
        assertThat(response.at("/data/driverName").asText()).isEqualTo("Sandbox Sunil");
        assertThat(jdbc.queryForObject("select status from delivery where id = ?", String.class, id))
                .isEqualTo("DRIVER_ASSIGNED");
        assertThat(jdbc.queryForObject("select driver_name from delivery where id = ?", String.class, id))
                .isEqualTo("Sandbox Sunil");
        // The response never names the provider.
        assertThat(response.toString()).doesNotContain("PIDGE").doesNotContain("Pidge");
    }

    @Test
    void buyerGetsNotFoundAndNothingIsSimulated() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");

        assertThat(advance(buyerToken, id)).isEqualTo(404);
        verify(pidge, never()).simulateOrderStatus(anyString(), anyString());
    }

    @Test
    void sandboxOffIsNotFoundAndOffersNoControls() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        properties.setSandbox(false);

        assertThat(advance(supplierToken, id)).isEqualTo(404);
        verify(pidge, never()).simulateOrderStatus(anyString(), anyString());
        assertThat(api.get(supplierToken, "/api/v1/deliveries/" + id).at("/data/sandboxControls").asBoolean()).isFalse();
    }

    @Test
    void supplierOwnDeliveryIsAConflictWithNoControls() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "SUPPLIER_OWN", "DRIVER_ASSIGNED");

        assertThat(advance(supplierToken, id)).isEqualTo(409);
        verify(pidge, never()).simulateOrderStatus(anyString(), anyString());
        assertThat(api.get(supplierToken, "/api/v1/deliveries/" + id).at("/data/sandboxControls").asBoolean()).isFalse();
    }

    @Test
    void aTerminalDeliveryIsAConflictWithNoControls() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DELIVERED");

        assertThat(advance(supplierToken, id)).isEqualTo(409);
        assertThat(api.get(supplierToken, "/api/v1/deliveries/" + id).at("/data/sandboxControls").asBoolean()).isFalse();
    }

    @Test
    void theRiderWalksFromThePickupToTheDropWhateverPointPidgeSends() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|out for pickup"))).thenReturn(dummyAt(pid, "OUT_FOR_PICKUP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached pickup"))).thenReturn(dummyAt(pid, "REACHED_PICKUP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|picked up"))).thenReturn(dummyAt(pid, "PICKED_UP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|ofd"))).thenReturn(dummyAt(pid, "OUT_FOR_DELIVERY"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached delivery"))).thenReturn(dummyAt(pid, "REACHED_DELIVERY"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|delivered"))).thenReturn(dummyAt(pid, "DELIVERED"));

        for (int i = 0; i < 6; i++) {
            assertThat(advance(supplierToken, id)).isEqualTo(200);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select latitude, longitude, recorded_at from delivery_location where delivery_id = ? order by recorded_at, id", id);
        // Every stage stores a fix, the first (out for pickup, the status moves to DRIVER_ASSIGNED) included.
        assertThat(rows).hasSize(6);
        double previous = Double.NEGATIVE_INFINITY;
        Instant previousAt = null;
        for (var row : rows) {
            double lat = ((BigDecimal) row.get("latitude")).doubleValue();
            double lng = ((BigDecimal) row.get("longitude")).doubleValue();
            // Never the fixed Gurugram point.
            assertThat(lat).isBetween(12.9, 13.0);
            // The first fix is short of the pickup (negative), the rest are along the line toward the drop.
            double fromPickup = (previousAt == null ? -1 : 1) * metres(12.9352, 77.6245, lat, lng);
            assertThat(fromPickup).isGreaterThanOrEqualTo(previous - 1.0);
            previous = fromPickup;
            Instant at = ((java.sql.Timestamp) row.get("recorded_at")).toInstant();
            assertThat(at).isBefore(Instant.now().plusSeconds(1));
            if (previousAt != null) {
                assertThat(at).isAfter(previousAt);
            }
            previousAt = at;
        }
        var last = rows.get(rows.size() - 1);
        assertThat(metres(12.9716, 77.5946, ((BigDecimal) last.get("latitude")).doubleValue(),
                ((BigDecimal) last.get("longitude")).doubleValue())).isLessThan(50.0);
        // The 40 percent fix sits strictly between the two ends.
        assertThat(previous).isGreaterThan(5000.0);
        var ofd = rows.get(3);
        assertThat(metres(12.9352, 77.6245, ((BigDecimal) ofd.get("latitude")).doubleValue(),
                ((BigDecimal) ofd.get("longitude")).doubleValue())).isBetween(1500.0, 3000.0);
    }

    private JsonNode orderAt(String providerDeliveryId, String stage, String at, String lat, String lng) throws Exception {
        return json.readTree("""
                {"id": "%s", "status": "fulfilled", "fulfillment": {"status": "%s", "logs": [
                  {"timestamp": "%s", "status": "%s", "location": {"latitude": %s, "longitude": %s},
                   "rider": {"id": "306", "name": "Sandbox Sunil", "mobile": "9876543210"}}]}}
                """.formatted(providerDeliveryId, stage, at, stage, lat, lng));
    }

    @Test
    void anAssignedRiderPositionIsStoredAndReturnedOnTheFirstStage() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|out for pickup"))).thenReturn(dummyAt(pid, "OUT_FOR_PICKUP"));

        assertThat(advance(supplierToken, id)).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from delivery where id = ?", String.class, id))
                .isEqualTo("DRIVER_ASSIGNED");
        for (String token : new String[] {supplierToken, buyerToken}) {
            JsonNode d = api.get(token, "/api/v1/deliveries/" + id).get("data");
            assertThat(d.at("/status").asText()).isEqualTo("DRIVER_ASSIGNED");
            assertThat(d.at("/trackable").asBoolean()).isTrue();
            assertThat(d.at("/location").isNull()).isFalse();
            // About 2 km short of the pickup, on the side away from the drop.
            double lat = d.at("/location/latitude").asDouble();
            double lng = d.at("/location/longitude").asDouble();
            assertThat(metres(12.9352, 77.6245, lat, lng)).isBetween(1990.0, 2010.0);
            assertThat(lat).isLessThan(12.9352);
        }
    }

    @Test
    void theFirstStageLocationIsRecordedAfterTheStatusMovesAndOneFixIsOneRow() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        var order = orderAt(pid, "OUT_FOR_PICKUP", "2026-10-06T10:02:00.000Z", "12.9170000", "77.6340000");

        webhook.process(order);

        assertThat(jdbc.queryForObject("select status from delivery where id = ?", String.class, id))
                .isEqualTo("DRIVER_ASSIGNED");
        assertThat(jdbc.queryForObject("select count(*) from delivery_location where delivery_id = ?", Integer.class, id))
                .isEqualTo(1);

        // The same fix again (a retry, or the 30 s poll) stays one row.
        webhook.process(order);
        webhook.process(order);
        assertThat(jdbc.queryForObject("select count(*) from delivery_location where delivery_id = ?", Integer.class, id))
                .isEqualTo(1);

        // A new fix at a new time is a second row.
        webhook.process(orderAt(pid, "OUT_FOR_PICKUP", "2026-10-06T10:03:00.000Z", "12.9200000", "77.6320000"));
        assertThat(jdbc.queryForObject("select count(*) from delivery_location where delivery_id = ?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    void aLocationForADeliveryStillWaitingForADriverIsNotStored() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        // Pidge says CREATED (no stage that assigns a driver), so no rider exists yet.
        webhook.process(orderAt(pid, "CREATED", "2026-10-06T10:02:00.000Z", "12.9170000", "77.6340000"));

        assertThat(jdbc.queryForObject("select count(*) from delivery_location where delivery_id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void withoutStoredCoordinatesTheDummyPositionStillGoesThrough() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");
        jdbc.update("update delivery set pickup_latitude = null, pickup_longitude = null where id = ?", id);
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached pickup"))).thenReturn(dummyAt(pid, "REACHED_PICKUP"));

        assertThat(advance(supplierToken, id)).isEqualTo(200);

        var lat = jdbc.queryForObject(
                "select latitude from delivery_location where delivery_id = ?", BigDecimal.class, id);
        assertThat(lat).isEqualByComparingTo("28.4425540");
    }

    // ── D-192: real roads ────────────────────────────────────────────────

    private void onTheSeedRoute(Long id) {
        jdbc.update("""
                update delivery set pickup_latitude = 12.9611000, pickup_longitude = 77.6387000,
                                    drop_latitude = 12.9784000, drop_longitude = 77.6408000 where id = ?
                """, id);
    }

    private void stubAllStages(String pid) throws Exception {
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|out for pickup"))).thenReturn(dummyAt(pid, "OUT_FOR_PICKUP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached pickup"))).thenReturn(dummyAt(pid, "REACHED_PICKUP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|picked up"))).thenReturn(dummyAt(pid, "PICKED_UP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|ofd"))).thenReturn(dummyAt(pid, "OUT_FOR_DELIVERY"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached delivery"))).thenReturn(dummyAt(pid, "REACHED_DELIVERY"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|delivered"))).thenReturn(dummyAt(pid, "DELIVERED"));
    }

    /** Smallest distance in metres from a point to the polyline (vertices and segments, flat-earth locally). */
    private double metresToPolyline(double lat, double lng, java.util.List<double[]> pts) {
        double best = Double.MAX_VALUE;
        double cos = Math.cos(Math.toRadians(lat));
        for (int i = 0; i < pts.size() - 1; i++) {
            double ax = (pts.get(i)[1] - lng) * 111_320 * cos, ay = (pts.get(i)[0] - lat) * 111_320;
            double bx = (pts.get(i + 1)[1] - lng) * 111_320 * cos, by = (pts.get(i + 1)[0] - lat) * 111_320;
            double dx = bx - ax, dy = by - ay;
            if (dx == 0 && dy == 0) {
                continue;
            }
            double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)));
            best = Math.min(best, Math.hypot(ax + dx * t, ay + dy * t));
        }
        return best;
    }

    private static double metresAlong(com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute route, double lat, double lng) {
        // Distance along approach + delivery of the nearest vertex, as a monotone ruler.
        double ruler = 0;
        double best = Double.MAX_VALUE;
        double at = 0;
        for (var leg : com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.Leg.values()) {
            var pts = route.points(leg);
            var cum = route.cumulativeMetres(leg);
            for (int i = 0; i < pts.size(); i++) {
                double d = Math.hypot((pts.get(i)[0] - lat) * 111_320,
                        (pts.get(i)[1] - lng) * 111_320 * Math.cos(Math.toRadians(lat)));
                if (d < best) {
                    best = d;
                    at = ruler + cum[i];
                }
            }
            ruler += route.lengthMetres(leg);
        }
        return at;
    }

    private java.util.List<Map<String, Object>> fixes(Long id) {
        return jdbc.queryForList(
                "select latitude, longitude, recorded_at from delivery_location where delivery_id = ? order by recorded_at, id", id);
    }

    @Test
    void aMatchingDeliveryWalksTheRoadStageByStage() throws Exception {
        var route = com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.shared();
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        onTheSeedRoute(id);
        stubAllStages(pid);

        for (int i = 0; i < 6; i++) {
            assertThat(advance(supplierToken, id)).isEqualTo(200);
        }

        var rows = fixes(id);
        assertThat(rows).hasSize(6);
        var all = new java.util.ArrayList<double[]>(route.points(com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.Leg.APPROACH));
        all.addAll(route.points(com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.Leg.DELIVERY));
        double previous = -1;
        for (var row : rows) {
            double lat = ((BigDecimal) row.get("latitude")).doubleValue();
            double lng = ((BigDecimal) row.get("longitude")).doubleValue();
            // (a) on the road polyline, (b) in order along it.
            assertThat(metresToPolyline(lat, lng, all)).isLessThan(5.0);
            double along = metresAlong(route, lat, lng);
            assertThat(along).isGreaterThanOrEqualTo(previous);
            previous = along;
        }
        // Reached and picked up are the same spot, the supplier; the last is the outlet.
        assertThat(rows.get(1).get("latitude")).isEqualTo(rows.get(2).get("latitude"));
        var last = rows.get(5);
        assertThat(metres(12.978401, 77.640814, ((BigDecimal) last.get("latitude")).doubleValue(),
                ((BigDecimal) last.get("longitude")).doubleValue())).isLessThan(5.0);
        var near = rows.get(4);
        assertThat(metres(12.978401, 77.640814, ((BigDecimal) near.get("latitude")).doubleValue(),
                ((BigDecimal) near.get("longitude")).doubleValue())).isBetween(20.0, 35.0);
    }

    @Test
    void aNonMatchingDeliveryStillUsesTheStraightLine() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|reached pickup"))).thenReturn(dummyAt(pid, "REACHED_PICKUP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|picked up"))).thenReturn(dummyAt(pid, "PICKED_UP"));
        when(pidge.simulateOrderStatus(eq(pid), eq("fulfilled|ofd"))).thenReturn(dummyAt(pid, "OUT_FOR_DELIVERY"));

        for (int i = 0; i < 3; i++) {
            assertThat(advance(supplierToken, id)).isEqualTo(200);
        }
        var rows = fixes(id);
        var ofd = rows.get(2);
        // 40 percent of the straight line (the road stage would be 35 percent of a different leg).
        assertThat(metres(12.9352, 77.6245, ((BigDecimal) ofd.get("latitude")).doubleValue(),
                ((BigDecimal) ofd.get("longitude")).doubleValue())).isBetween(2000.0, 2300.0);
        assertThat(((BigDecimal) ofd.get("latitude")).doubleValue()).isEqualTo(12.9352 + 0.4 * (12.9716 - 12.9352), org.assertj.core.data.Offset.offset(1e-6));
    }

    private org.springframework.test.web.servlet.MvcResult move(String token, Long id, String leg, String fraction) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/v1/deliveries/" + id + "/sandbox/move")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (leg != null) {
            req.param("leg", leg);
        }
        if (fraction != null) {
            req.param("fraction", fraction);
        }
        return mvc.perform(req).andReturn();
    }

    @Test
    void moveStoresStrictlyLaterFixesAlongTheApproachAndKeepsTheStatus() throws Exception {
        var route = com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.shared();
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");
        onTheSeedRoute(id);

        double[] fractions = {0.0, 0.25, 0.5, 0.75, 1.0};
        for (double f : fractions) {
            var result = move(supplierToken, id, "approach", String.valueOf(f));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            var body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.at("/data/status").asText()).isEqualTo("DRIVER_ASSIGNED");
            assertThat(body.at("/data/location").isNull()).isFalse();
            assertThat(body.at("/data/location/latitude").asDouble()).isEqualTo(
                    route.pointAt(com.costonomy.mp.delivery.provider.pidge.PidgeSandboxRoute.Leg.APPROACH, f).latitude().doubleValue(),
                    org.assertj.core.data.Offset.offset(1e-7));
        }
        assertThat(jdbc.queryForObject("select status from delivery where id = ?", String.class, id)).isEqualTo("DRIVER_ASSIGNED");

        var rows = fixes(id);
        assertThat(rows).hasSize(5);
        Instant previousAt = null;
        double previous = -1;
        for (var row : rows) {
            Instant at = ((java.sql.Timestamp) row.get("recorded_at")).toInstant();
            if (previousAt != null) {
                assertThat(at).isAfter(previousAt);
            }
            assertThat(at).isBeforeOrEqualTo(Instant.now());
            previousAt = at;
            double along = metresAlong(route, ((BigDecimal) row.get("latitude")).doubleValue(),
                    ((BigDecimal) row.get("longitude")).doubleValue());
            assertThat(along).isGreaterThanOrEqualTo(previous);
            previous = along;
        }
        // The same point twice in a row is still two rows (a later stamp each time).
        assertThat(move(supplierToken, id, "delivery", "0.5").getResponse().getStatus()).isEqualTo(200);
        assertThat(move(supplierToken, id, "delivery", "0.5").getResponse().getStatus()).isEqualTo(200);
        assertThat(fixes(id)).hasSize(7);
    }

    @Test
    void moveBeatsAFutureDatedLastFixByOneMillisecond() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");
        onTheSeedRoute(id);
        jdbc.update("insert into delivery_location (delivery_id, latitude, longitude, recorded_at) "
                + "values (?, 12.96, 77.63, ?)", id, java.sql.Timestamp.from(Instant.now().plusSeconds(120)));
        var future = jdbc.queryForObject("select max(recorded_at) from delivery_location where delivery_id = ?",
                java.sql.Timestamp.class, id).toInstant();

        assertThat(move(supplierToken, id, "approach", "0.5").getResponse().getStatus()).isEqualTo(200);

        var newest = jdbc.queryForObject("select max(recorded_at) from delivery_location where delivery_id = ?",
                java.sql.Timestamp.class, id).toInstant();
        assertThat(newest).isEqualTo(future.plusMillis(1));
        assertThat(fixes(id)).hasSize(2);
    }

    @Test
    void moveOffTheRouteUsesTheStraightLine() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");

        assertThat(move(supplierToken, id, "approach", "0").getResponse().getStatus()).isEqualTo(200);
        assertThat(move(supplierToken, id, "delivery", "1").getResponse().getStatus()).isEqualTo(200);

        var rows = fixes(id);
        assertThat(metres(12.9352, 77.6245, ((BigDecimal) rows.get(0).get("latitude")).doubleValue(),
                ((BigDecimal) rows.get(0).get("longitude")).doubleValue())).isBetween(1990.0, 2010.0);
        assertThat(metres(12.9716, 77.5946, ((BigDecimal) rows.get(1).get("latitude")).doubleValue(),
                ((BigDecimal) rows.get(1).get("longitude")).doubleValue())).isLessThan(1.0);
    }

    @Test
    void moveGuards() throws Exception {
        String pid = "pidg_sbx_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "DRIVER_ASSIGNED");
        onTheSeedRoute(id);

        // Buyer: it does not exist.
        assertThat(move(buyerToken, id, "approach", "0.5").getResponse().getStatus()).isEqualTo(404);
        // Bad leg and bad fractions.
        assertThat(move(supplierToken, id, "sideways", "0.5").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, null, "0.5").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, "approach", "1.5").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, "approach", "-0.1").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, "approach", "abc").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, "approach", "NaN").getResponse().getStatus()).isEqualTo(400);
        assertThat(move(supplierToken, id, "approach", null).getResponse().getStatus()).isEqualTo(400);
        assertThat(fixes(id)).isEmpty();

        // Not trackable: waiting for a driver, and delivered.
        jdbc.update("update delivery set status = 'PROVIDER_SELECTED' where id = ?", id);
        assertThat(move(supplierToken, id, "approach", "0.5").getResponse().getStatus()).isEqualTo(409);
        jdbc.update("update delivery set status = 'DELIVERED' where id = ?", id);
        assertThat(move(supplierToken, id, "approach", "0.5").getResponse().getStatus()).isEqualTo(409);
        assertThat(fixes(id)).isEmpty();

        // Sandbox off: 404 and nothing stored.
        jdbc.update("update delivery set status = 'DRIVER_ASSIGNED' where id = ?", id);
        properties.setSandbox(false);
        assertThat(move(supplierToken, id, "approach", "0.5").getResponse().getStatus()).isEqualTo(404);
        assertThat(fixes(id)).isEmpty();
    }
}
