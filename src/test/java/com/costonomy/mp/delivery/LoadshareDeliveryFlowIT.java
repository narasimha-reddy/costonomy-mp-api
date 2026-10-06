package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDelivery;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderEvent;
import com.costonomy.mp.delivery.provider.loadshare.LoadshareApiClient;
import com.costonomy.mp.delivery.provider.loadshare.LoadshareDeliveryProvider;
import com.costonomy.mp.delivery.service.DeliveryJobs;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for the LoadShare delivery provider:
 * 1. Adapter registration in {@link DeliveryProviderRegistry} when both bean and DB row are active.
 * 2. Quote and booking delegation to {@link LoadshareApiClient}.
 * 3. Status polling via {@link DeliveryJobs} progressing delivery and supplier order states.
 * 4. Cancellation delegation.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.loadshare.enabled=true",
        "costonomy.mp.loadshare.customer-code=TEST_CUST_CODE",
        "costonomy.mp.loadshare.auth-token=test-loadshare-token"
})
class LoadshareDeliveryFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryJobs deliveryJobs;
    @Autowired private DeliveryProviderRegistry registry;

    @MockBean private LoadshareApiClient loadshareApiClient;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private Long createSeedDelivery(String providerDeliveryId) throws Exception {
        String buyerPhone = ApiClient.freshPhone();
        String buyerToken = api.login(buyerPhone);
        JsonNode r = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Loadshare Test Restaurant",
                "firstOutlet", Map.of(
                        "name", "Indiranagar",
                        "addressLine1", "100 Feet Rd",
                        "city", "Bengaluru",
                        "state", "Karnataka",
                        "pincode", "560038",
                        "latitude", "12.9716",
                        "longitude", "77.5946"))).get("data");
        long outletId = r.get("outlets").get(0).get("id").asLong();
        Long buyerUserId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + buyerPhone);

        String sellerPhone = ApiClient.freshPhone();
        String sellerToken = api.login(sellerPhone);
        JsonNode s = api.post(sellerToken, "/api/v1/suppliers", Map.of(
                "legalName", "Loadshare Supplier " + System.currentTimeMillis() + " Pvt Ltd",
                "displayName", "Loadshare Supplier",
                "contactName", "Ops Desk",
                "contactPhone", "+919876500000",
                "firstStore", Map.of(
                        "name", "Koramangala Store",
                        "contactName", "Store Desk",
                        "contactPhone", "+919876500000",
                        "addressLine1", "80 Feet Rd",
                        "city", "Bengaluru",
                        "state", "Karnataka",
                        "latitude", "12.9352",
                        "longitude", "77.6245"))).get("data");
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

        String orderNumber = "SO-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000);
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
                values (?, ?, ?, 'COSTONOMY', 'PROVIDER_SELECTED',
                        'LOADSHARE', ?, 48.5000, 'INR',
                        'Pickup Point, Indiranagar, Bengaluru, 560038', 'Drop Point, Koramangala, Bengaluru, 560034',
                        now(6), now(6), now(6), 0)
                """, supplierOrderId, outletId, storeId, providerDeliveryId);

        return jdbc.queryForObject(
                "select id from delivery where provider_delivery_id = ? order by id desc limit 1",
                Long.class, providerDeliveryId);
    }

    @Test
    @DisplayName("LoadShare adapter is registered in DeliveryProviderRegistry and active when DB row is enabled")
    void loadshareRegistersInRegistry() {
        var adapter = registry.adapter("LOADSHARE");
        assertThat(adapter).isNotNull().isInstanceOf(LoadshareDeliveryProvider.class);
        assertThat(adapter.code()).isEqualTo("LOADSHARE");
        assertThat(adapter.supportsTracking()).isTrue();

        // Enable in DB
        jdbc.update("update delivery_provider set enabled = 1 where code = 'LOADSHARE'");

        var enabledProviders = registry.enabled();
        assertThat(enabledProviders.stream().anyMatch(a -> "LOADSHARE".equals(a.record().getCode()))).isTrue();
    }

    @Test
    @DisplayName("LoadShare quote and booking delegate to LoadshareApiClient")
    void quoteAndBookingDelegateToClient() {
        var adapter = registry.adapter("LOADSHARE");
        assertThat(adapter).isNotNull();

        var quoteRequest = new DeliveryProvider.QuoteRequest(
                101L, new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Indiranagar, Bengaluru, 560038", "Koramangala, Bengaluru, 560034");

        var expectedQuote = new DeliveryProvider.Quote(
                "ls_q_101", true, new BigDecimal("48.50"), "INR", 45, 4.8,
                Instant.now().plusSeconds(600), null, null);

        when(loadshareApiClient.calculateQuote(any())).thenReturn(expectedQuote);

        var quote = adapter.quote(quoteRequest);
        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("48.50");
        verify(loadshareApiClient).calculateQuote(quoteRequest);

        var bookingRequest = new DeliveryProvider.BookingRequest(
                101L, "ls_q_101", "Indiranagar, Bengaluru, 560038",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"), "Pickup Contact", "+919876500000",
                "Koramangala, Bengaluru, 560034", new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Drop Contact", "+919876500001", "idemp-key-ls-1");

        var expectedBooking = new DeliveryProvider.Booking(
                "LS-ORDER-9999", new BigDecimal("48.50"), "INR", 45,
                Instant.now().plusSeconds(2700), "https://track.loadshare.net/order/LS-ORDER-9999");

        when(loadshareApiClient.createOrder(any())).thenReturn(expectedBooking);

        var booking = adapter.book(bookingRequest);
        assertThat(booking.providerDeliveryId()).isEqualTo("LS-ORDER-9999");
        assertThat(booking.amount()).isEqualByComparingTo("48.50");
        verify(loadshareApiClient).createOrder(bookingRequest);
    }

    @Test
    @DisplayName("DeliveryJobs polls LoadShare status and advances delivery to DRIVER_ASSIGNED then DELIVERED with deduplication")
    void statusPollingAdvancesDeliveryAndOrder() throws Exception {
        String providerDeliveryId = "ls_poll_" + System.currentTimeMillis();
        Long deliveryId = createSeedDelivery(providerDeliveryId);

        // 1. Initial poll returns DRIVER_ASSIGNED
        var assignedDelivery = new ProviderDelivery(
                providerDeliveryId, ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Driver Ravi", "+919876543210", "KA-01-LS-5678",
                30, Instant.now().plusSeconds(1800), null, null,
                List.of(new ProviderEvent(
                        "ls_" + providerDeliveryId + "_driver_assigned",
                        ProviderDeliveryStatus.DRIVER_ASSIGNED,
                        "Driver Assigned",
                        Instant.now()))
        );

        when(loadshareApiClient.getStatus(providerDeliveryId)).thenReturn(assignedDelivery);

        deliveryJobs.pollActiveDeliveries();

        // Verify delivery updated in DB
        Map<String, Object> deliveryRow = jdbc.queryForMap(
                "select status, driver_name, driver_phone, assigned_at from delivery where id = ?",
                deliveryId);
        assertThat(deliveryRow.get("status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(deliveryRow.get("driver_name")).isEqualTo("Driver Ravi");
        assertThat(deliveryRow.get("driver_phone")).isEqualTo("+919876543210");
        assertThat(deliveryRow.get("assigned_at")).isNotNull();

        // 2. Poll again with identical status -> Deduplicated on uk_delivery_event_provider
        deliveryJobs.pollActiveDeliveries();

        var events = jdbc.queryForList(
                "select provider_event_id, disposition from delivery_event where delivery_id = ? and provider_event_id is not null",
                deliveryId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("disposition")).isEqualTo("APPLIED");

        // 3. Poll returns DELIVERED with newest-first events [DELIVERED, PICKED_UP]
        var completedDelivery = new ProviderDelivery(
                providerDeliveryId, ProviderDeliveryStatus.DELIVERED,
                "Driver Ravi", "+919876543210", "KA-01-LS-5678",
                0, null, null, null,
                List.of(
                        new ProviderEvent(
                                "ls_" + providerDeliveryId + "_delivered",
                                ProviderDeliveryStatus.DELIVERED,
                                "Delivered to outlet",
                                Instant.now()),
                        new ProviderEvent(
                                "ls_" + providerDeliveryId + "_picked_up",
                                ProviderDeliveryStatus.PICKED_UP,
                                "Picked up from store",
                                Instant.now()))
        );

        when(loadshareApiClient.getStatus(providerDeliveryId)).thenReturn(completedDelivery);

        deliveryJobs.pollActiveDeliveries();

        // Verify delivery is DELIVERED and supplier order is DELIVERED
        Map<String, Object> finalDelivery = jdbc.queryForMap(
                "select status, delivered_at, picked_up_at from delivery where id = ?", deliveryId);
        assertThat(finalDelivery.get("status")).isEqualTo("DELIVERED");
        assertThat(finalDelivery.get("delivered_at")).isNotNull();
        assertThat(finalDelivery.get("picked_up_at")).isNotNull();

        Long supplierOrderId = jdbc.queryForObject(
                "select supplier_order_id from delivery where id = ?", Long.class, deliveryId);
        String orderStatus = jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, supplierOrderId);
        assertThat(orderStatus).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("LoadShare cancel delegates to LoadshareApiClient")
    void cancelDelegatesToClient() {
        var adapter = registry.adapter("LOADSHARE");
        assertThat(adapter).isNotNull();

        adapter.cancel("LS-ORDER-9999", "Cancelled by ops");

        verify(loadshareApiClient).cancel("LS-ORDER-9999", "Cancelled by ops");
    }
}
