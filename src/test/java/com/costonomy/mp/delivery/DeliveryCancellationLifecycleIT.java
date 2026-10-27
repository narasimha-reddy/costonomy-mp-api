package com.costonomy.mp.delivery;

import com.costonomy.mp.support.LocalCommon;

import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Issue 5:
 * 1. View-only actors are denied (403).
 * 2. Authorized actors succeed.
 * 3. Provider cancellation is retried durably on provider failure.
 * 4. Order and delivery do not become inconsistent (coordinated cancellation and refunds).
 * 5. Cancellation of delivery only vs cancelling whole order.
 * 6. Cannot cancel once goods have been picked up.
 * 7. Fully idempotent.
 */
@AutoConfigureMockMvc
class DeliveryCancellationLifecycleIT extends AbstractIntegrationTest {

    private static final String API = "CANCEL_API";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private CourierPayableService payables;
    @Autowired private DeliveryProviderRegistry registry;
    @Autowired @Qualifier("mockSaverDeliveryProvider") private MockDeliveryProvider saver;

    private ApiClient api;
    private String opsToken;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, API, "MOCK_API");
        opsToken = createPlatformUser("OPS_ADMIN");
    }

    private String createPlatformUser(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = LocalCommon.userId("+91" + phone);
        LocalCommon.grantPlatform(userId, roleCode);
        return token;
    }

    private String createOutletViewOnlyUser(Long outletId) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = LocalCommon.userId("+91" + phone);
        // A restaurant's own role that sees the outlet and its orders and may not cancel them.
        LocalCommon.customRole("REST_VIEW_ONLY", "RESTAURANT", "View only", "OUTLET_VIEW", "ORDER_VIEW");
        LocalCommon.grant(userId, "REST_VIEW_ONLY", "OUTLET", outletId);
        return token;
    }

    private DeliveryFixture.Setup ready(String buyerFee, String funded) throws Exception {
        return DeliveryFixture.readyForPickup(mvc, json, api, jdbc, paymentProvider, paymentJobs, buyerFee, funded);
    }

    @Test
    @DisplayName("View-only actors are denied (403), authorized actors succeed")
    void viewOnlyDenied_authorizedSucceeds() throws Exception {
        var s = ready("0.00", "0.00");
        String viewOnlyToken = createOutletViewOnlyUser(s.outletId());

        // 1. View-only user gets 403 Forbidden
        int viewOnlyStatus = api.postStatus(viewOnlyToken, "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "I want to cancel"));
        assertThat(viewOnlyStatus).isEqualTo(403);

        // 2. Authorized actor (outlet admin with ORDER_CANCEL) succeeds
        int authorizedStatus = api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Cancelled by restaurant"));
        assertThat(authorizedStatus).isEqualTo(200);

        // Verify delivery and order were coordinated and cancelled together
        String deliveryStatus = jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
        String orderStatus = jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId());
        assertThat(deliveryStatus).isEqualTo(DeliveryStatus.CANCELLED.name());
        assertThat(orderStatus).isEqualTo(SupplierOrderStatus.CANCELLED.name());

        // Repeated cancellation is idempotent
        int repeatStatus = api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Repeat cancel"));
        assertThat(repeatStatus).isEqualTo(200);
    }

    @Test
    @DisplayName("Cancelling the delivery alone is refused while the order is active: nothing changes and the order can still go on")
    void cancelDeliveryOnly_isRefusedWhileTheOrderIsActive() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'PROVIDER_SELECTED', provider_code = 'MOCK_SAVER', "
                + "provider_delivery_id = 'keep-1', mode = 'COSTONOMY' where id = ?", s.deliveryId());

        int refused = api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Switching to supplier delivery", "cancelOrder", false));

        assertThat(refused).isBetween(400, 422);
        assertThat(jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId()))
                .as("the delivery is untouched").isEqualTo("PROVIDER_SELECTED");
        assertThat(jdbc.queryForObject("select provider_delivery_id from delivery where id = ?", String.class, s.deliveryId()))
                .isEqualTo("keep-1");
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId()))
                .isEqualTo(SupplierOrderStatus.READY_FOR_PICKUP.name());
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_CANCELLED'", Integer.class, s.deliveryId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from delivery_recovery_task where flow = 'SUPPLIER' and delivery_id = ?",
                Integer.class, s.deliveryId())).as("no courier was stood down").isZero();

        // The order is not stuck: another courier can still be asked for, and cancelling both still works.
        assertThat(api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Cancel it all", "cancelOrder", true))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId()))
                .isEqualTo(SupplierOrderStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("A delivery left cancelled beside a ready order (from before) is repaired by cancelling the order, and delivery-only stays refused")
    void anOrderAlreadyStuckCanBeRepaired() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'CANCELLED', cancelled_at = now(6) where id = ?", s.deliveryId());
        String url = "/api/v1/deliveries/" + s.deliveryId() + "/cancel";

        assertThat(api.postStatus(s.buyerToken(), url, Map.of("reason", "again", "cancelOrder", false))).isBetween(400, 422);
        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId()))
                .isEqualTo(SupplierOrderStatus.READY_FOR_PICKUP.name());

        assertThat(api.postStatus(s.buyerToken(), url, Map.of("reason", "Repair the stuck order"))).isEqualTo(200);

        assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, s.orderId()))
                .isEqualTo(SupplierOrderStatus.CANCELLED.name());
        // Once the order has ended, the same request has nothing left to protect and is accepted, idempotently.
        assertThat(api.postStatus(s.buyerToken(), url, Map.of("reason", "again", "cancelOrder", false))).isEqualTo(200);
    }

    @Test
    @DisplayName("The refusal comes after the permission check: a view-only user still gets 403")
    void permissionIsCheckedBeforeTheStateRefusal() throws Exception {
        var s = ready("0.00", "0.00");
        String viewOnly = createOutletViewOnlyUser(s.outletId());

        assertThat(api.postStatus(viewOnly, "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "x", "cancelOrder", false))).isEqualTo(403);
    }

    @Test
    @DisplayName("Cannot cancel delivery once goods have been picked up")
    void cannotCancelAfterPickup() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'PICKED_UP', picked_up_at = now(6) where id = ?", s.deliveryId());

        int status = api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Try cancel"));
        assertThat(status).isBetween(400, 422);

        String deliveryStatus = jdbc.queryForObject("select status from delivery where id = ?", String.class, s.deliveryId());
        assertThat(deliveryStatus).isEqualTo("PICKED_UP");
    }

    @Test
    @DisplayName("Provider cancellation failure is scheduled for durable recovery")
    void providerCancellationFailure_scheduledForRecovery() throws Exception {
        var s = ready("0.00", "0.00");
        // Mark delivery with booked provider
        jdbc.update("update delivery set provider_code = 'MOCK_SAVER', provider_delivery_id = 'fail-prov-1' where id = ?",
                s.deliveryId());

        // Force provider adapter to throw on cancel
        saver.arm(MockDeliveryProvider.Failure.CANCEL_FAILS);

        int ok = api.postStatus(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId() + "/cancel",
                Map.of("reason", "Courier not needed"));
        assertThat(ok).isEqualTo(200);

        // Verify recovery task was scheduled
        Integer tasks = jdbc.queryForObject(
                "select count(*) from delivery_recovery_task where delivery_id = ? and task_type = 'PROVIDER_CANCEL'",
                Integer.class, s.deliveryId());
        assertThat(tasks).isGreaterThanOrEqualTo(1);
    }
}
