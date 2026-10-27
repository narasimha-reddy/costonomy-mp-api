package com.costonomy.mp.delivery;

import com.costonomy.mp.support.LocalCommon;

import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.AdminDeliveryService;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A provider that reported a failed hand-over and later confirms the delivery (D-206): the delivery ends delivered, the
 * stale failure and the reconciliation flag are cleared, the history stays, and the operator's view says it is over.
 */
@AutoConfigureMockMvc
class DeliveryLateDeliveredIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private DeliveryEventService events;
    @Autowired private DeliveryRepository deliveries;
    @Autowired private AdminDeliveryService admin;
    @Autowired private SupplierCourierPayableSync payableSync;

    private DeliveryFixture.Setup s;
    private long opsUserId;

    @BeforeEach
    void setUp() throws Exception {
        var api = new ApiClient(mvc, json);
        s = DeliveryFixture.readyForPickup(mvc, json, api, jdbc, paymentProvider, paymentJobs, "0.00", "0.00");
        String phone = ApiClient.freshPhone();
        api.login(phone);
        opsUserId = LocalCommon.userId("+91" + phone);
        LocalCommon.grantPlatform(opsUserId, "OPS_ADMIN");
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, 'MOCK_EXPRESS', 1, 'BOOKING', 'BOOKED', 'late-1', 80, now(6), now(6))
                """, s.deliveryId());
        jdbc.update("update delivery set status = 'DRIVER_ASSIGNED', mode = 'COSTONOMY', provider_code = 'MOCK_EXPRESS', "
                + "provider_delivery_id = 'late-1', fee = 80, attempt_count = 1 where id = ?", s.deliveryId());
    }

    private void event(String id, DeliveryProvider.ProviderDeliveryStatus status, String why) {
        events.apply(deliveries.findById(s.deliveryId()).orElseThrow(),
                new DeliveryProvider.ProviderEvent(id, status, why, Instant.now()));
    }

    private String one(String sql) {
        return jdbc.queryForObject(sql, String.class, s.deliveryId());
    }

    @Test
    @DisplayName("a failed hand-over later confirmed delivered ends delivered, with the failure and the flag cleared and the history kept")
    void failureThenDelivered() throws Exception {
        event("l-1", DeliveryProvider.ProviderDeliveryStatus.PICKED_UP, "Picked up");
        event("l-2", DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Customer unreachable");

        var tenantView = new ApiClient(mvc, json).get(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId()).at("/data");
        assertThat(tenantView.get("underReview").asBoolean()).as("tenants are told, without the internal reason").isTrue();
        assertThat(tenantView.toString()).doesNotContain("reconciliation");

        var failed = admin.inspectSupplierDelivery(opsUserId, s.deliveryId());
        assertThat(failed.reconciliationRequired()).isTrue();
        assertThat(failed.nextAction()).isEqualTo("OPERATOR_RECONCILIATION");
        assertThat(one("select status from delivery where id = ?")).isEqualTo("DELIVERY_FAILED");
        assertThat(one("select failure_code from delivery where id = ?")).isEqualTo("DELIVERY_FAILED");

        event("l-3", DeliveryProvider.ProviderDeliveryStatus.DELIVERED, "Handed over after all");

        assertThat(new ApiClient(mvc, json).get(s.buyerToken(), "/api/v1/deliveries/" + s.deliveryId())
                .at("/data/underReview").asBoolean()).as("and told again when it is settled").isFalse();
        var delivered = admin.inspectSupplierDelivery(opsUserId, s.deliveryId());
        assertThat(one("select status from delivery where id = ?")).isEqualTo("DELIVERED");
        assertThat(delivered.reconciliationRequired()).as("the flag described a state that no longer holds").isFalse();
        assertThat(delivered.reconciliationReason()).isNull();
        assertThat(delivered.nextAction()).as("and the operator's view says it is over").isEqualTo("TERMINAL");
        assertThat(delivered.delivery().failureCode()).isNull();
        assertThat(delivered.delivery().failureReason()).isNull();
        assertThat(one("select supplier_order.status from supplier_order join delivery d on d.supplier_order_id = supplier_order.id "
                + "where d.id = ?")).as("the order followed").isEqualTo("DELIVERED");

        // Nothing is deleted: the failure, the delivery and the supersession are all still on record.
        assertThat(jdbc.queryForList("select status from delivery_event where delivery_id = ? and provider_event_id like 'l-%' "
                + "order by id", String.class, s.deliveryId())).containsExactly("PICKED_UP", "DELIVERY_FAILED", "DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_FAILURE_SUPERSEDED'", Integer.class, s.deliveryId())).isEqualTo(1);
        assertThat(one("select ended_cause from delivery_provider_attempt where delivery_id = ?")).isEqualTo("COMPLETED");

        // The courier is owed for a delivery that was made, not for a failure.
        payableSync.sync(s.deliveryId());
        var payable = jdbc.queryForMap("select state, due_reason from courier_payable where source_type = 'SUPPLIER_DELIVERY' "
                + "and delivery_id = ?", s.deliveryId());
        assertThat(payable).containsEntry("state", "DUE").containsEntry("due_reason", "DELIVERED");
    }

    @Test
    @DisplayName("after delivery, a repeated delivered event or a stale failure does not bring the failure back")
    void theFailureDoesNotComeBack() {
        event("m-1", DeliveryProvider.ProviderDeliveryStatus.PICKED_UP, "Picked up");
        event("m-2", DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Customer unreachable");
        event("m-3", DeliveryProvider.ProviderDeliveryStatus.DELIVERED, "Delivered");

        event("m-4", DeliveryProvider.ProviderDeliveryStatus.DELIVERED, "Delivered (again)");
        event("m-5", DeliveryProvider.ProviderDeliveryStatus.DELIVERY_FAILED, "Stale failure");

        assertThat(one("select status from delivery where id = ?")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select reconciliation_required from delivery where id = ?", Boolean.class,
                s.deliveryId())).isFalse();
        assertThat(one("select coalesce(failure_code, 'none') from delivery where id = ?")).isEqualTo("none");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_FAILURE_SUPERSEDED'", Integer.class, s.deliveryId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a delivery that never failed is delivered as before, with nothing superseded")
    void anOrdinaryDeliveryIsUnchanged() {
        event("n-1", DeliveryProvider.ProviderDeliveryStatus.PICKED_UP, "Picked up");
        event("n-2", DeliveryProvider.ProviderDeliveryStatus.DELIVERED, "Delivered");

        assertThat(one("select status from delivery where id = ?")).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'DELIVERY' and entity_id = ? "
                + "and action = 'DELIVERY_FAILURE_SUPERSEDED'", Integer.class, s.deliveryId())).isZero();
    }

    @Test
    @DisplayName("while a booking's outcome is unknown the supplier cannot take over, and the response says so")
    void takeoverIsRefusedWhileUnderReview() throws Exception {
        jdbc.update("update delivery set status = 'PROVIDER_UNAVAILABLE', provider_delivery_id = null, "
                + "no_partner_since = utc_timestamp(6) - interval 50 minute, own_delivery_offered_at = utc_timestamp(6), "
                + "reconciliation_required = true, reconciliation_reason = 'Booking outcome ambiguous' where id = ?", s.deliveryId());
        var api = new ApiClient(mvc, json);

        var view = api.get(s.supplierToken(), "/api/v1/deliveries/" + s.deliveryId()).at("/data");
        assertThat(view.get("underReview").asBoolean()).isTrue();
        assertThat(view.get("canSwitchToOwn").asBoolean()).as("no takeover offered").isFalse();

        int refused = api.postStatusWithHeader(s.supplierToken(), "/api/v1/deliveries/" + s.deliveryId() + "/switch-to-own",
                java.util.Map.of(), "Idempotency-Key", java.util.UUID.randomUUID().toString());
        assertThat(refused).isEqualTo(409);
        assertThat(one("select mode from delivery where id = ?")).isEqualTo("COSTONOMY");
    }
}
