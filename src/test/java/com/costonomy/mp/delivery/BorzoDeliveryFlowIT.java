package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDelivery;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderDeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider.ProviderEvent;
import com.costonomy.mp.delivery.provider.borzo.BorzoApiClient;
import com.costonomy.mp.delivery.provider.borzo.BorzoDeliveryProvider;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies Borzo delivery provider end-to-end integration:
 * 1. Activation in {@link DeliveryProviderRegistry} when both bean and DB row are enabled.
 * 2. Quote and booking delegation to {@link BorzoApiClient}.
 * 3. Status polling via {@link DeliveryJobs} advancing delivery and supplier_order state.
 * 4. Deduplication of polled status events.
 * 5. Cancellation delegation.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.borzo.enabled=true",
        "costonomy.mp.borzo.auth-token=test-token"
})
class BorzoDeliveryFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryJobs deliveryJobs;
    @Autowired private DeliveryProviderRegistry registry;

    @MockBean private BorzoApiClient borzoApiClient;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private Long createSeedDelivery(String providerDeliveryId) throws Exception {
        String buyerPhone = ApiClient.freshPhone();
        String buyerToken = api.login(buyerPhone);
        JsonNode r = api.post(buyerToken, "/api/v1/restaurants", Map.of(
                "name", "Borzo Test Restaurant",
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
                "legalName", "Borzo Supplier " + System.currentTimeMillis() + " Pvt Ltd",
                "displayName", "Borzo Supplier",
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
                        'BORZO', ?, 85.0000, 'INR',
                        'Pickup Point, Indiranagar, Bengaluru', 'Drop Point, Koramangala, Bengaluru',
                        now(6), now(6), now(6), 0)
                """, supplierOrderId, outletId, storeId, providerDeliveryId);

        return jdbc.queryForObject(
                "select id from delivery where provider_delivery_id = ? order by id desc limit 1",
                Long.class, providerDeliveryId);
    }

    @Test
    @DisplayName("Borzo adapter is registered in DeliveryProviderRegistry and active when DB row is enabled")
    void borzoRegistersInRegistry() {
        var adapter = registry.adapter("BORZO");
        assertThat(adapter).isNotNull().isInstanceOf(BorzoDeliveryProvider.class);
        assertThat(adapter.code()).isEqualTo("BORZO");
        assertThat(adapter.supportsTracking()).isTrue();

        // Enable in DB
        jdbc.update("update delivery_provider set enabled = 1 where code = 'BORZO'");

        var enabledProviders = registry.enabled();
        assertThat(enabledProviders.stream().anyMatch(a -> "BORZO".equals(a.record().getCode()))).isTrue();
    }

    @Test
    @DisplayName("Borzo delivery quote and booking delegate accurately to BorzoApiClient")
    void quoteAndBookingDelegateToClient() {
        var adapter = registry.adapter("BORZO");
        assertThat(adapter).isNotNull();

        var quoteRequest = new DeliveryProvider.QuoteRequest(
                101L, new BigDecimal("12.9716"), new BigDecimal("77.5946"),
                new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                new BigDecimal("500.00"), new BigDecimal("2500"), 45,
                "Pickup Address", "Drop Address");

        var expectedQuote = new DeliveryProvider.Quote(
                "borzo_q_123", true, new BigDecimal("85.76"), "INR", 45, 6.0,
                Instant.now().plusSeconds(300), null, VehicleType.TWO_WHEELER);

        when(borzoApiClient.calculateOrder(any())).thenReturn(expectedQuote);

        var quote = adapter.quote(quoteRequest);
        assertThat(quote.serviceable()).isTrue();
        assertThat(quote.amount()).isEqualByComparingTo("85.76");
        verify(borzoApiClient).calculateOrder(quoteRequest);

        var bookingRequest = new DeliveryProvider.BookingRequest(
                101L, "borzo_q_123", "Pickup Address",
                new BigDecimal("12.9716"), new BigDecimal("77.5946"), "Pickup Contact", "+919876500000",
                "Drop Address", new BigDecimal("12.9352"), new BigDecimal("77.6245"),
                "Drop Contact", "+919876500001", "idemp-key-1");

        var expectedBooking = new DeliveryProvider.Booking(
                "331900", new BigDecimal("85.76"), "INR", 45,
                Instant.now().plusSeconds(2700), "https://track.borzo.com/123");

        when(borzoApiClient.createOrder(any())).thenReturn(expectedBooking);

        var booking = adapter.book(bookingRequest);
        assertThat(booking.providerDeliveryId()).isEqualTo("331900");
        assertThat(booking.amount()).isEqualByComparingTo("85.76");
        verify(borzoApiClient).createOrder(bookingRequest);
    }

    @Test
    @DisplayName("DeliveryJobs polls Borzo status and advances delivery to DRIVER_ASSIGNED then DELIVERED with deduplication")
    void statusPollingAdvancesDeliveryAndOrder() throws Exception {
        String providerDeliveryId = "borzo_poll_" + System.currentTimeMillis();
        Long deliveryId = createSeedDelivery(providerDeliveryId);

        // 1. Initial poll returns DRIVER_ASSIGNED with courier details
        var assignedDelivery = new ProviderDelivery(
                providerDeliveryId, ProviderDeliveryStatus.DRIVER_ASSIGNED,
                "Rider Ajay", "+919876543210", "KA-01-EQ-1234",
                30, Instant.now().plusSeconds(1800), null, null,
                List.of(new ProviderEvent(
                        "borzo_" + providerDeliveryId + "_driver_assigned",
                        ProviderDeliveryStatus.DRIVER_ASSIGNED,
                        "Borzo status: active",
                        Instant.now()))
        );

        when(borzoApiClient.getStatus(providerDeliveryId)).thenReturn(assignedDelivery);

        deliveryJobs.pollActiveDeliveries();

        // Verify delivery updated in DB
        Map<String, Object> deliveryRow = jdbc.queryForMap(
                "select status, driver_name, driver_phone, driver_vehicle, assigned_at from delivery where id = ?",
                deliveryId);
        assertThat(deliveryRow.get("status")).isEqualTo("DRIVER_ASSIGNED");
        assertThat(deliveryRow.get("driver_name")).isEqualTo("Rider Ajay");
        assertThat(deliveryRow.get("driver_phone")).isEqualTo("+919876543210");
        assertThat(deliveryRow.get("driver_vehicle")).isEqualTo("KA-01-EQ-1234");
        assertThat(deliveryRow.get("assigned_at")).isNotNull();

        // 2. Poll again with identical status -> Deduplicated on uk_delivery_event_provider
        deliveryJobs.pollActiveDeliveries();

        var events = jdbc.queryForList(
                "select provider_event_id, disposition from delivery_event where delivery_id = ? and provider_event_id is not null",
                deliveryId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("disposition")).isEqualTo("APPLIED");

        // 3. Poll returns DELIVERED
        var completedDelivery = new ProviderDelivery(
                providerDeliveryId, ProviderDeliveryStatus.DELIVERED,
                "Rider Ajay", "+919876543210", "KA-01-EQ-1234",
                0, null, null, null,
                List.of(
                        new ProviderEvent(
                                "borzo_" + providerDeliveryId + "_delivered",
                                ProviderDeliveryStatus.DELIVERED,
                                "Borzo status: completed",
                                Instant.now()),
                        new ProviderEvent(
                                "borzo_" + providerDeliveryId + "_picked_up",
                                ProviderDeliveryStatus.PICKED_UP,
                                "Borzo status: completed",
                                Instant.now()))
        );

        when(borzoApiClient.getStatus(providerDeliveryId)).thenReturn(completedDelivery);

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
    @DisplayName("Borzo cancel delegates to BorzoApiClient")
    void cancelDelegatesToClient() {
        var adapter = registry.adapter("BORZO");
        assertThat(adapter).isNotNull();

        adapter.cancel("331900", "Restaurant requested cancellation");

        verify(borzoApiClient).cancel("331900", "Restaurant requested cancellation");
    }
}
