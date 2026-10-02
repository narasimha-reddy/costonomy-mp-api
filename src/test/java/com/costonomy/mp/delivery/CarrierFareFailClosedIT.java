package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-102: Shadowfax and Porter have no verified carrier fare, so in a real auction they must be
 * recorded as having been asked and must never win or be charged.
 *
 * <p>The carrier clients are deliberately not mocked: this exercises the real wiring. Shadowfax
 * points at a closed port so its serviceability call fails deterministically (a FAILED quote,
 * never a serviceable one); Porter declines without any HTTP call.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.shadowfax.enabled=true",
        "costonomy.mp.shadowfax.auth-token=test-sfx-token",
        "costonomy.mp.shadowfax.base-url=http://127.0.0.1:1",
        "costonomy.mp.porter.enabled=true",
        "costonomy.mp.porter.api-key=test-porter-key"
})
class CarrierFareFailClosedIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryService deliveryService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        jdbc.update("update delivery_provider set enabled = 1 where code in ('SHADOWFAX', 'PORTER')");
    }

    /** Other ITs share this database, so put every provider row back the way the migrations seed it. */
    @AfterEach
    void restoreProviderRows() {
        jdbc.update("update delivery_provider set enabled = 0 where code in ('SHADOWFAX', 'PORTER')");
        jdbc.update("update delivery_provider set enabled = 1 where code in ('MOCK_EXPRESS', 'MOCK_SAVER')");
    }

    private Long seedReadyOrderInHyderabad() throws Exception {
        String buyerPhone = ApiClient.freshPhone();
        String buyerToken = api.login(buyerPhone);
        JsonNode r = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Fail Closed Restaurant",
                "firstOutlet", Map.of(
                        "name", "Gachibowli",
                        "addressLine1", "Road 2",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "pincode", "500081",
                        "latitude", "17.4474",
                        "longitude", "78.3762"))).get("data");
        long outletId = r.get("outlets").get(0).get("id").asLong();
        Long buyerUserId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + buyerPhone);

        String sellerPhone = ApiClient.freshPhone();
        String sellerToken = api.login(sellerPhone);
        JsonNode s = api.post(sellerToken, "/api/v1/suppliers", Map.of(
                "legalName", "Fail Closed Supplier " + System.currentTimeMillis() + " Pvt Ltd",
                "displayName", "Fail Closed Supplier",
                "contactName", "Ops Desk",
                "contactPhone", "+919876500000",
                "firstStore", Map.of(
                        "name", "Secunderabad Store",
                        "contactName", "Store Desk",
                        "contactPhone", "+919876500000",
                        "addressLine1", "SD Road",
                        "city", "Secunderabad",
                        "state", "Telangana",
                        "latitude", "17.4156",
                        "longitude", "78.4347"))).get("data");
        long supplierOrgId = s.get("id").asLong();
        long storeId = s.get("stores").get(0).get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', verification_status = 'VERIFIED' where id = ?", supplierOrgId);
        jdbc.update("update supplier_store set pincode = '500003' where id = ?", storeId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierOrgId);

        jdbc.update("""
                insert into procurement (outlet_id, created_by, status, approval_status,
                                         payment_method, payment_status, total_amount, created_at, updated_at, version)
                values (?, ?, 'SUBMITTED', 'NOT_REQUIRED', 'PREPAID', 'CAPTURED', 500.00, now(6), now(6), 0)
                """, outletId, buyerUserId);
        Long procurementId = jdbc.queryForObject(
                "select id from procurement where outlet_id = ? order by id desc limit 1", Long.class, outletId);

        String orderNumber = "SO-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 10000);
        jdbc.update("""
                insert into supplier_order (procurement_id, supplier_store_id, outlet_id,
                                            order_number, status, total_amount, accepted_amount,
                                            delivery_mode, payment_method, payment_status, created_at, updated_at, version)
                values (?, ?, ?, ?, 'READY_FOR_PICKUP', 500.00, 500.00, 'COSTONOMY_DELIVERY', 'PREPAID', 'CAPTURED', now(6), now(6), 0)
                """, procurementId, storeId, outletId, orderNumber);
        return jdbc.queryForObject("select id from supplier_order where order_number = ?", Long.class, orderNumber);
    }

    private Long deliveryIdFor(Long supplierOrderId) {
        return jdbc.queryForObject("select id from delivery where supplier_order_id = ?", Long.class, supplierOrderId);
    }

    @Test
    @DisplayName("Fareless carriers are recorded as asked but never win and are never charged")
    void farelessCarriersAreRecordedButNeverWin() throws Exception {
        Long orderId = seedReadyOrderInHyderabad();

        deliveryService.autoDispatch(orderId);
        Long deliveryId = deliveryIdFor(orderId);

        List<Map<String, Object>> quotes = jdbc.queryForList(
                "select provider_code, status, selected, failure_reason from delivery_quote where delivery_id = ?",
                deliveryId);
        var porter = quotes.stream().filter(q -> "PORTER".equals(q.get("provider_code"))).findFirst().orElseThrow();
        var shadowfax = quotes.stream().filter(q -> "SHADOWFAX".equals(q.get("provider_code"))).findFirst().orElseThrow();

        assertThat(porter.get("status")).isEqualTo("UNSERVICEABLE");
        assertThat((String) porter.get("failure_reason")).contains("fare");
        assertThat(shadowfax.get("status")).isEqualTo("FAILED");
        assertThat(porter.get("selected")).isIn(false, 0);
        assertThat(shadowfax.get("selected")).isIn(false, 0);

        String winner = jdbc.queryForObject("select provider_code from delivery where id = ?", String.class, deliveryId);
        assertThat(winner).isIn("MOCK_EXPRESS", "MOCK_SAVER");

        assertThat(jdbc.queryForObject("""
                select count(*) from delivery_provider_attempt
                 where delivery_id = ? and provider_code in ('SHADOWFAX', 'PORTER')
                """, Integer.class, deliveryId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from delivery_ledger
                 where delivery_id = ? and entry_type = 'BOOKED' and provider_code in ('SHADOWFAX', 'PORTER')
                """, Integer.class, deliveryId)).isZero();
    }

    @Test
    @DisplayName("With only fareless carriers enabled, dispatch fails cleanly with nothing booked or charged")
    void onlyFarelessCarriersEnabled_failsCleanlyWithNoBooking() throws Exception {
        jdbc.update("update delivery_provider set enabled = 0 where code in ('MOCK_EXPRESS', 'MOCK_SAVER')");
        Long orderId = seedReadyOrderInHyderabad();

        deliveryService.autoDispatch(orderId);
        Long deliveryId = deliveryIdFor(orderId);

        Map<String, Object> delivery = jdbc.queryForMap(
                "select status, failure_code, fee, provider_code from delivery where id = ?", deliveryId);
        assertThat(delivery.get("status")).isEqualTo("QUOTE_FAILED");
        assertThat(delivery.get("failure_code")).isEqualTo("NO_SERVICEABLE_PROVIDER");
        assertThat(delivery.get("provider_code")).isNull();
        assertThat(((java.math.BigDecimal) delivery.get("fee")).signum()).isZero();

        assertThat(jdbc.queryForObject("select count(*) from delivery_provider_attempt where delivery_id = ?",
                Integer.class, deliveryId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from delivery_ledger where delivery_id = ?",
                Integer.class, deliveryId)).isZero();
    }
}
