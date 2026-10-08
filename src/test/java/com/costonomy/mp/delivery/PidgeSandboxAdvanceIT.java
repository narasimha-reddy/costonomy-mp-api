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
}
