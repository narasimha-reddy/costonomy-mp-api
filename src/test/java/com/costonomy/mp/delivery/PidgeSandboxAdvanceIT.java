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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D-154: the test-only "move the sandbox rider to the next step" route and the flag that offers it. */
@AutoConfigureMockMvc
class PidgeSandboxAdvanceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PidgeProperties properties;
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
                                      pickup_address, drop_address, requested_at, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, 'PIDGE', ?, 65.0000, 'INR',
                        'Pickup Point, Bengaluru', 'Drop Point, Bengaluru', now(6), now(6), now(6), 0)
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
}
