package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.pidge.PidgeApiClient;
import com.costonomy.mp.delivery.provider.pidge.PidgeDeliveryProvider;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.delivery.service.DeliveryJobs;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * B2: the real poll job, over a real Pidge adapter and client whose HTTP answers are faked, stores the polled rider
 * position once however many times Pidge repeats it, and never moves a delivery back to an earlier status.
 */
@AutoConfigureMockMvc
class PidgePollLocationIT extends AbstractIntegrationTest {

    private static final String BASE_URL = "https://pidge.example.invalid";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryJobs jobs;
    @MockBean private DeliveryProviderRegistry registry;

    private ApiClient api;
    private MockRestServiceServer server;
    private String buyerToken;
    private String supplierToken;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        // The poll asks for every active Pidge delivery in the shared database, ours and other tests' alike.
        jdbc.update("update delivery set status = 'CANCELLED' where provider_code = 'PIDGE' "
                + "and status in ('PROVIDER_SELECTED','DRIVER_ASSIGNED','DRIVER_AT_PICKUP','PICKED_UP',"
                + "'IN_TRANSIT','ARRIVED_AT_DESTINATION')");
        var properties = new PidgeProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiToken("fake-token");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setRateLimitRps(1000);
        var customizer = new MockServerRestTemplateCustomizer();
        var client = new PidgeApiClient(properties,
                new RestTemplateBuilder().additionalCustomizers(customizer), new ObjectMapper());
        server = customizer.getServer();
        when(registry.adapter("PIDGE")).thenReturn(new PidgeDeliveryProvider(client));
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

    private void pidgeAnswers(String pid, String stage, String logs) {
        server.expect(requestTo(BASE_URL + "/v1.0/store/channel/vendor/order/" + pid))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": {"id": "%s", "status": "fulfilled", "fulfillment": {"status": "%s",
                          "logs": [%s]}}}
                        """.formatted(pid, stage, logs), MediaType.APPLICATION_JSON));
    }

    private static final String ASSIGNED_WITH_FIX = """
            {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
            {"timestamp": "2026-10-06T10:02:00.000Z", "status": "OUT_FOR_PICKUP",
             "location": {"latitude": 17.44, "longitude": 78.49},
             "rider": {"name": "Sunil", "mobile": "9876543210"}}""";

    private int locationRows(Long id) {
        return jdbc.queryForObject("select count(*) from delivery_location where delivery_id = ?",
                Integer.class, id);
    }

    private String status(Long id) {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, id);
    }

    @Test
    void sameFixTwiceStoresOneRow() throws Exception {
        String pid = "pidg_poll_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        pidgeAnswers(pid, "OUT_FOR_PICKUP", ASSIGNED_WITH_FIX);
        pidgeAnswers(pid, "OUT_FOR_PICKUP", ASSIGNED_WITH_FIX);

        jobs.pollActiveDeliveries();
        assertThat(status(id)).isEqualTo("DRIVER_ASSIGNED");
        assertThat(locationRows(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select latitude from delivery_location where delivery_id = ?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("17.44");

        jobs.pollActiveDeliveries();
        assertThat(locationRows(id)).isEqualTo(1);
    }

    @Test
    void aNewFixIsAnotherRow() throws Exception {
        String pid = "pidg_poll_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        pidgeAnswers(pid, "OUT_FOR_PICKUP", ASSIGNED_WITH_FIX);
        pidgeAnswers(pid, "REACHED_PICKUP", ASSIGNED_WITH_FIX + """
                ,
                {"timestamp": "2026-10-06T10:05:00.000Z", "status": "REACHED_PICKUP",
                 "location": {"latitude": 17.45, "longitude": 78.50}}""");

        jobs.pollActiveDeliveries();
        jobs.pollActiveDeliveries();

        assertThat(locationRows(id)).isEqualTo(2);
        assertThat(status(id)).isEqualTo("DRIVER_AT_PICKUP");
    }

    @Test
    void aStatusLogWithoutALocationStoresNothingAndDoesNotFailThePoll() throws Exception {
        String pid = "pidg_poll_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "PROVIDER_SELECTED");
        pidgeAnswers(pid, "OUT_FOR_PICKUP", """
                {"timestamp": "2026-10-06T10:00:00.000Z", "status": "CREATED"},
                {"timestamp": "2026-10-06T10:02:00.000Z", "status": "OUT_FOR_PICKUP",
                 "rider": {"name": "Sunil", "mobile": "9876543210"}}""");

        jobs.pollActiveDeliveries();

        assertThat(status(id)).isEqualTo("DRIVER_ASSIGNED");
        assertThat(locationRows(id)).isZero();
    }

    /**
     * P5 (documents current behaviour). Pidge's GET lists every stage so far. A delivery already IN_TRANSIT whose
     * poll answer only reaches OUT_FOR_PICKUP is not moved back: DeliveryEventService stores the older stage as
     * OUT_OF_ORDER and leaves the status alone.
     */
    @Test
    void aPollAnswerBehindTheDeliveryDoesNotMoveItBackwards() throws Exception {
        String pid = "pidg_poll_" + System.nanoTime();
        Long id = seed(pid, "COSTONOMY", "IN_TRANSIT");
        pidgeAnswers(pid, "OUT_FOR_PICKUP", ASSIGNED_WITH_FIX);

        jobs.pollActiveDeliveries();

        assertThat(status(id)).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.queryForObject(
                "select count(*) from delivery_event where delivery_id = ? and disposition = 'OUT_OF_ORDER'",
                Integer.class, id)).isEqualTo(1);
    }
}
