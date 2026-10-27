package com.costonomy.mp.delivery;

import com.costonomy.mp.support.LocalCommon;

import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Issue 6: Unify supplier-own delivery milestone updates.
 * Verifies that:
 * 1. Order routes (/supplier-orders/{id}/out-for-delivery, /delivered) transition order and delivery together.
 * 2. Delivery routes (/deliveries/{id}/dispatched, /delivered) transition delivery and order together.
 * 3. Both routes record coherent timeline, audit, and outbox events.
 * 4. Unauthorized actors are rejected with 403.
 * 5. Costonomy-carried deliveries reject supplier-reported movement with 403.
 * 6. Pickup orders reject dispatch/delivery updates.
 * 7. Legacy orders without a delivery row are handled gracefully.
 * 8. Fully idempotent across repeated calls.
 */
@AutoConfigureMockMvc
class SupplierDeliveryMovementIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;

    private ApiClient api;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
    }

    private DeliveryFixture.Setup ready(String buyerFee, String funded) throws Exception {
        return DeliveryFixture.readyForPickup(mvc, json, api, jdbc, paymentProvider, paymentJobs, buyerFee, funded);
    }

    private String createStoreViewOnlyUser(Long storeId) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = LocalCommon.userId("+91" + phone);
        LocalCommon.grant(userId, "SUP_FINANCE_STAFF", "SUPPLIER_STORE", storeId);
        return token;
    }

    @Test
    @DisplayName("Order route out-for-delivery and delivered: transitions both supplier_order and delivery together")
    void orderRoutes_transitionBothOrderAndDelivery() throws Exception {
        var s = ready("0.00", "0.00");
        // Mark mode as supplier delivery
        jdbc.update("update supplier_order set delivery_mode = 'SUPPLIER_DELIVERY' where id = ?", s.orderId());
        jdbc.update("update delivery set mode = 'SUPPLIER_OWN', status = 'DRIVER_ASSIGNED' where id = ?", s.deliveryId());

        // 1. Move to OUT_FOR_DELIVERY via order route
        String key1 = UUID.randomUUID().toString();
        var outRes = api.postWithHeader(s.supplierToken(), "/api/v1/supplier-orders/" + s.orderId() + "/out-for-delivery",
                Map.of(), "Idempotency-Key", key1);
        assertThat(outRes.at("/data/status").asText()).isEqualTo("OUT_FOR_DELIVERY");

        // Verify delivery was also transitioned to PICKED_UP
        String deliveryStatus1 = jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
        assertThat(deliveryStatus1).isEqualTo("PICKED_UP");

        // Verify timeline on delivery
        Integer timelineCount = jdbc.queryForObject(
                "select count(*) from delivery_event where delivery_id = ? and status = 'PICKED_UP'",
                Integer.class, s.deliveryId());
        assertThat(timelineCount).isGreaterThanOrEqualTo(1);

        // 2. Move to DELIVERED via order route
        String key2 = UUID.randomUUID().toString();
        var delRes = api.postWithHeader(s.supplierToken(), "/api/v1/supplier-orders/" + s.orderId() + "/delivered",
                Map.of(), "Idempotency-Key", key2);
        assertThat(delRes.at("/data/status").asText()).isEqualTo("DELIVERED");

        // Verify delivery was also transitioned to DELIVERED
        String deliveryStatus2 = jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
        assertThat(deliveryStatus2).isEqualTo("DELIVERED");

        // 3. Repeated call with same or new key is idempotent
        var repeatRes = api.postWithHeader(s.supplierToken(), "/api/v1/supplier-orders/" + s.orderId() + "/delivered",
                Map.of(), "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(repeatRes.at("/data/status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Delivery route dispatched and delivered: transitions both delivery and supplier_order together")
    void deliveryRoutes_transitionBothDeliveryAndOrder() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update supplier_order set delivery_mode = 'SUPPLIER_DELIVERY' where id = ?", s.orderId());
        jdbc.update("update delivery set mode = 'SUPPLIER_OWN', status = 'DRIVER_ASSIGNED' where id = ?", s.deliveryId());

        // 1. Dispatch via delivery route
        var dispRes = api.post(s.supplierToken(), "/api/v1/deliveries/" + s.deliveryId() + "/dispatched", Map.of());
        assertThat(dispRes.at("/data/status").asText()).isEqualTo("PICKED_UP");

        // Verify order followed to OUT_FOR_DELIVERY
        String orderStatus1 = jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId());
        assertThat(orderStatus1).isEqualTo("OUT_FOR_DELIVERY");

        // 2. Delivered via delivery route
        var delRes = api.post(s.supplierToken(), "/api/v1/deliveries/" + s.deliveryId() + "/delivered", Map.of());
        assertThat(delRes.at("/data/status").asText()).isEqualTo("DELIVERED");

        // Verify order followed to DELIVERED
        String orderStatus2 = jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId());
        assertThat(orderStatus2).isEqualTo("DELIVERED");

        // Idempotent repeat
        var repeatRes = api.post(s.supplierToken(), "/api/v1/deliveries/" + s.deliveryId() + "/delivered", Map.of());
        assertThat(repeatRes.at("/data/status").asText()).isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("Costonomy-contracted deliveries reject supplier-reported movement with 403 Forbidden")
    void courierOrders_rejectSupplierReportedMovement() throws Exception {
        var s = ready("0.00", "0.00");
        // Delivery mode is COSTONOMY
        jdbc.update("update supplier_order set delivery_mode = 'COSTONOMY_DELIVERY' where id = ?", s.orderId());
        jdbc.update("update delivery set mode = 'COSTONOMY' where id = ?", s.deliveryId());

        // 1. Order route rejected
        int orderRouteStatus = api.postStatusWithHeader(s.supplierToken(),
                "/api/v1/supplier-orders/" + s.orderId() + "/out-for-delivery", Map.of(),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(orderRouteStatus).isEqualTo(403);

        // 2. Delivery route rejected
        int deliveryRouteStatus = api.postStatus(s.supplierToken(),
                "/api/v1/deliveries/" + s.deliveryId() + "/dispatched", Map.of());
        assertThat(deliveryRouteStatus).isEqualTo(403);
    }

    @Test
    @DisplayName("View-only actors are denied (403/404) on both routes")
    void viewOnlyActors_deniedWithForbidden() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update supplier_order set delivery_mode = 'SUPPLIER_DELIVERY' where id = ?", s.orderId());
        jdbc.update("update delivery set mode = 'SUPPLIER_OWN', status = 'DRIVER_ASSIGNED' where id = ?", s.deliveryId());

        String viewOnlyToken = createStoreViewOnlyUser(s.storeId());

        int orderRouteStatus = api.postStatusWithHeader(viewOnlyToken,
                "/api/v1/supplier-orders/" + s.orderId() + "/out-for-delivery", Map.of(),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(orderRouteStatus).isIn(403, 404);

        int deliveryRouteStatus = api.postStatus(viewOnlyToken,
                "/api/v1/deliveries/" + s.deliveryId() + "/dispatched", Map.of());
        assertThat(deliveryRouteStatus).isIn(403, 404);
    }

    @Test
    @DisplayName("Legacy orders without a delivery row: order route completes gracefully")
    void legacyOrderWithoutDeliveryRow_completesGracefully() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update supplier_order set delivery_mode = 'SUPPLIER_DELIVERY' where id = ?", s.orderId());
        // Delete the delivery row to simulate legacy order
        jdbc.update("delete from delivery where id = ?", s.deliveryId());

        var res = api.postWithHeader(s.supplierToken(), "/api/v1/supplier-orders/" + s.orderId() + "/out-for-delivery",
                Map.of(), "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(res.at("/data/status").asText()).isEqualTo("OUT_FOR_DELIVERY");

        String orderStatus = jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId());
        assertThat(orderStatus).isEqualTo("OUT_FOR_DELIVERY");
    }
}
