package com.costonomy.mp.delivery;

import com.costonomy.mp.support.LocalCommon;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.DeliveryResolutionService;
import com.costonomy.mp.delivery.service.DeliveryResolutionService.Outcome;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.costonomy.mp.wallet.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exits of a delivery that cannot finish by itself (D-204): cancelled with a refund, or confirmed delivered. Each ends
 * the delivery, the order and the money together, and is safe to repeat.
 */
@AutoConfigureMockMvc
class DeliveryResolutionIT extends AbstractIntegrationTest {

    private static final String API = "PAYEE_API";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private DeliveryResolutionService resolution;
    @Autowired private SupplierCourierPayableSync payableSync;
    @Autowired private CourierPayableService payables;
    @Autowired private WalletService wallet;
    @Autowired private com.costonomy.mp.delivery.service.AdminDeliveryService adminDeliveries;

    private ApiClient api;
    private String ops;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, API, "MOCK_API");
        ops = operator("OPS_ADMIN");
    }

    private long opsUserId;

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        opsUserId = LocalCommon.userId("+91" + phone);
        LocalCommon.grantPlatform(opsUserId, roleCode);
        return token;
    }

    private DeliveryFixture.Setup ready(String buyerFee, String funded) throws Exception {
        return DeliveryFixture.readyForPickup(mvc, json, api, jdbc, paymentProvider, paymentJobs, buyerFee, funded);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from supplier_order where id = ?", String.class, orderId);
    }

    private String deliveryStatus(long deliveryId) {
        return jdbc.queryForObject("select status from delivery where id = ?", String.class, deliveryId);
    }

    private BigDecimal paid(long orderId) {
        return jdbc.queryForObject("select total_amount from supplier_order where id = ?", BigDecimal.class, orderId);
    }

    private long actor() {
        return opsUserId;
    }

    private void booked(long deliveryId, String providerDeliveryId, String quoted, String status, boolean pickedUp) {
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, ?, 1, 'BOOKING', 'BOOKED', ?, ?, now(6), now(6))
                """, deliveryId, API, providerDeliveryId, new BigDecimal(quoted));
        jdbc.update("update delivery set status = ?, provider_code = ?, provider_delivery_id = ?, fee = ?, "
                        + "picked_up_at = ?, version = version + 1 where id = ?",
                status, API, providerDeliveryId, new BigDecimal(quoted),
                pickedUp ? java.sql.Timestamp.from(java.time.Instant.now()) : null, deliveryId);
    }

    @Test
    @DisplayName("a delivery nobody will take, on an order stuck at ready, is cancelled with the order and the money goes back, once")
    void noCourierAndNoTakeoverEndsWithARefund() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'QUOTE_FAILED', no_partner_since = now(6) - interval 3 hour where id = ?",
                s.deliveryId());
        assertThat(orderStatus(s.orderId())).isEqualTo("READY_FOR_PICKUP");
        BigDecimal before = wallet.balanceOf(s.outletId());

        resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "No courier, supplier did not take over");
        resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "Again");

        assertThat(deliveryStatus(s.deliveryId())).isEqualTo("CANCELLED");
        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select cancelled_by from supplier_order where id = ?", String.class, s.orderId()))
                .isEqualTo("SYSTEM");
        assertThat(jdbc.queryForObject("select cancellation_reason from supplier_order where id = ?", String.class, s.orderId()))
                .contains("Cancelled by operations");
        assertThat(wallet.balanceOf(s.outletId()).subtract(before)).as("the whole payment, once")
                .isEqualByComparingTo(paid(s.orderId()));
    }

    @Test
    @DisplayName("an order that sits at ready with a dead delivery is listed as an exception, with what to do, until it is resolved")
    void aStuckOrderIsListedUntilResolved() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'QUOTE_FAILED', mode = 'COSTONOMY' where id = ?", s.deliveryId());
        jdbc.update("update supplier_order set updated_at = now(6) - interval 2 hour where id = ?", s.orderId());

        var listed = adminDeliveries.journeyExceptions(actor());
        assertThat(listed).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("READY_WITHOUT_COURIER");
            assertThat(e.supplierOrderId()).isEqualTo(s.orderId());
            assertThat(e.deliveryId()).isEqualTo(s.deliveryId());
            assertThat(e.ageMinutes()).isGreaterThanOrEqualTo(60);
            assertThat(e.action()).contains("resolve");
        });
        assertThat(api.getStatus(api.loginFresh(), "/api/v1/admin/deliveries/exceptions")).isIn(403, 404);

        resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "No courier");

        assertThat(adminDeliveries.journeyExceptions(actor()))
                .noneMatch(e -> e.supplierOrderId().equals(s.orderId()));
    }

    @Test
    @DisplayName("force-waterfall tells the operator when it cannot move the delivery, and audits who tried and what happened")
    void forceWaterfallReportsFailureAndAuditsTheOperator() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'DELIVERY_FAILED', picked_up_at = now(6) - interval 1 hour, "
                + "provider_code = 'MOCK_EXPRESS', provider_delivery_id = 'gone-1' where id = ?", s.deliveryId());

        int status = api.postStatus(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/force-waterfall",
                Map.of("reason", "Try another"));

        assertThat(status).as("the failure reaches the operator").isGreaterThanOrEqualTo(400);
        assertThat(deliveryStatus(s.deliveryId())).isEqualTo("DELIVERY_FAILED");
        var audit = jdbc.queryForList("select actor_id, new_state from audit_log where entity_type = 'DELIVERY' "
                + "and entity_id = ? and action = 'DELIVERY_WATERFALL_MANUAL_FAILED'", s.deliveryId());
        assertThat(audit).hasSize(1);
        assertThat(((Number) audit.get(0).get("actor_id")).longValue()).isEqualTo(opsUserId);
    }

    @Test
    @DisplayName("a delivery no partner would take is listed only once the own-delivery offer is due, not while the retries are still trying")
    void noPartnerIsListedAfterTheRetriesNotDuring() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'QUOTE_FAILED', mode = 'COSTONOMY', no_partner_since = "
                + "utc_timestamp(6) - interval 10 minute where id = ?", s.deliveryId());
        jdbc.update("update supplier_order set updated_at = utc_timestamp(6) - interval 3 hour where id = ?", s.orderId());
        assertThat(adminDeliveries.journeyExceptions(actor()))
                .as("inside the retry window").noneMatch(e -> (e.deliveryId() != null && e.deliveryId() == s.deliveryId())
                        && "READY_WITHOUT_COURIER".equals(e.kind()));

        jdbc.update("update delivery set no_partner_since = utc_timestamp(6) - interval 50 minute where id = ?", s.deliveryId());
        assertThat(adminDeliveries.journeyExceptions(actor()))
                .as("once the offer is due").anyMatch(e -> (e.deliveryId() != null && e.deliveryId() == s.deliveryId())
                        && "READY_WITHOUT_COURIER".equals(e.kind()));
    }

    // ── An uncollected pickup order (gap 17) ──

    private DeliveryFixture.Setup uncollectedPickup() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("delete from delivery where id = ?", s.deliveryId());
        jdbc.update("update supplier_order set delivery_mode = 'PICKUP', updated_at = utc_timestamp(6) - interval 2 day "
                + "where id = ?", s.orderId());
        return s;
    }

    @Test
    @DisplayName("an uncollected pickup order is listed with its exit, and operations can cancel and refund it, once")
    void anUncollectedPickupCanBeClosedByOperations() throws Exception {
        var s = uncollectedPickup();
        BigDecimal before = wallet.balanceOf(s.outletId());

        assertThat(adminDeliveries.journeyExceptions(actor())).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo("PICKUP_NOT_COLLECTED");
            assertThat(e.supplierOrderId()).isEqualTo(s.orderId());
            assertThat(e.action()).contains("close-pickup");
        });

        String url = "/api/v1/admin/deliveries/orders/" + s.orderId() + "/close-pickup";
        assertThat(api.postStatus(ops, url, Map.of("reason", "Restaurant never came"))).isEqualTo(200);
        assertThat(api.postStatus(ops, url, Map.of("reason", "Again"))).isEqualTo(200);

        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(s.outletId()).subtract(before)).as("the whole payment, once")
                .isEqualByComparingTo(paid(s.orderId()));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'SUPPLIER_ORDER' and entity_id = ? "
                + "and action = 'PICKUP_ORDER_CLOSED_BY_OPERATIONS'", Integer.class, s.orderId())).isEqualTo(1);
        assertThat(adminDeliveries.journeyExceptions(actor())).noneMatch(e -> "PICKUP_NOT_COLLECTED".equals(e.kind())
                && (e.supplierOrderId() != null && e.supplierOrderId() == s.orderId()));
    }

    @Test
    @DisplayName("the pickup exit refuses an order that has a delivery, a reason-less call, and anyone but operations")
    void thePickupExitIsGuarded() throws Exception {
        var withDelivery = ready("0.00", "0.00");
        jdbc.update("update supplier_order set delivery_mode = 'PICKUP' where id = ?", withDelivery.orderId());
        assertThat(api.postStatus(ops, "/api/v1/admin/deliveries/orders/" + withDelivery.orderId() + "/close-pickup",
                Map.of("reason", "x"))).isEqualTo(409);

        var s = uncollectedPickup();
        String url = "/api/v1/admin/deliveries/orders/" + s.orderId() + "/close-pickup";
        assertThat(api.postStatus(ops, url, Map.of("reason", " "))).isEqualTo(400);
        assertThat(api.postStatus(api.loginFresh(), url, Map.of("reason", "I am nobody"))).isIn(403, 404);
        assertThat(orderStatus(s.orderId())).isEqualTo("READY_FOR_PICKUP");
    }

    @Test
    @DisplayName("a courier that never picked the goods up is stood down, owes nothing, and the order is refunded")
    void courierBookedButNeverCameIsVoid() throws Exception {
        var s = ready("20.00", "30.00");
        booked(s.deliveryId(), "res-1", "80.00", "DRIVER_ASSIGNED", false);
        payableSync.sync(s.deliveryId());

        resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "Rider unreachable");

        assertThat(deliveryStatus(s.deliveryId())).isEqualTo("CANCELLED");
        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select outcome from delivery_provider_attempt where delivery_id = ?", String.class,
                s.deliveryId())).isEqualTo("CANCELLED");
        payableSync.sync(s.deliveryId());
        var p = payables.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId()).get(0);
        assertThat(p.getState()).isEqualTo("VOID");
    }

    @Test
    @DisplayName("a delivery that failed after pickup is cancelled and refunded in full; the courier is still owed, and it is the platform's cost")
    void failureAfterPickupRefundsTheCustomersAndLeavesTheCourierOwed() throws Exception {
        var s = ready("20.00", "30.00");
        jdbc.update("update supplier_order set status = 'OUT_FOR_DELIVERY' where id = ?", s.orderId());
        booked(s.deliveryId(), "fail-1", "80.00", "DELIVERY_FAILED", true);
        // The supplier's share was taken at pickup.
        jdbc.update("""
                insert into supplier_deduction (supplier_store_id, supplier_order_id, dispute_refund_id, amount, reason,
                                                status, kind, delivery_id)
                values (?, ?, null, 30.00, 'Costonomy delivery', 'PENDING', 'DELIVERY_FUNDING', ?)
                """, s.storeId(), s.orderId(), s.deliveryId());
        BigDecimal before = wallet.balanceOf(s.outletId());

        resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "Goods returned to the supplier");

        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(s.outletId()).subtract(before)).isEqualByComparingTo(paid(s.orderId()));

        payableSync.sync(s.deliveryId());
        var p = payables.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId()).get(0);
        assertThat(p.getState()).isEqualTo("DUE");
        assertThat(p.getDueReason()).isEqualTo("POST_PICKUP_FAILURE");
        assertThat(p.getAmountDue()).as("the courier ran").isEqualByComparingTo("80.00");
        assertThat(p.getBuyerContribution()).as("the restaurant was refunded").isEqualByComparingTo("0");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("0");
        assertThat(p.getPlatformFunded()).isEqualByComparingTo("80.00");
        assertThat(jdbc.queryForObject("select status from supplier_deduction where delivery_id = ?", String.class,
                s.deliveryId())).as("the supplier's share is given back too").isEqualTo("WAIVED");
    }

    @Test
    @DisplayName("a delivery that did arrive is marked delivered, the order follows, and the supplier's share is charged")
    void markDeliveredMovesTheOrderAndChargesTheShare() throws Exception {
        var s = ready("0.00", "30.00");
        jdbc.update("update supplier_order set status = 'OUT_FOR_DELIVERY' where id = ?", s.orderId());
        booked(s.deliveryId(), "arr-1", "80.00", "DELIVERY_FAILED", true);

        resolution.resolve(actor(), s.deliveryId(), Outcome.MARK_DELIVERED, "Customer confirmed by phone");
        resolution.resolve(actor(), s.deliveryId(), Outcome.MARK_DELIVERED, "Again");

        assertThat(deliveryStatus(s.deliveryId())).isEqualTo("DELIVERED");
        assertThat(orderStatus(s.orderId())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select count(*) from supplier_deduction where delivery_id = ?", Integer.class,
                s.deliveryId())).isEqualTo(1);
        payableSync.sync(s.deliveryId());
        var p = payables.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId()).get(0);
        assertThat(p.getDueReason()).isEqualTo("DELIVERED");
        assertThat(p.getSupplierContribution()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("a delivery cannot be closed while a courier is being booked, or marked delivered when no courier had the goods")
    void guards() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    started_at, created_at) values (?, ?, 1, 'BOOKING', 'PENDING', now(6), now(6))
                """, s.deliveryId(), API);

        assertThatThrownBy(() -> resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("being booked");
        assertThatThrownBy(() -> resolution.resolve(actor(), s.deliveryId(), Outcome.MARK_DELIVERED, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot be marked delivered");
        assertThatThrownBy(() -> resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, " "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("why");
        assertThat(orderStatus(s.orderId())).isEqualTo("READY_FOR_PICKUP");
    }

    @Test
    @DisplayName("a delivered order cannot be cancelled from here, and only operations may close a delivery")
    void deliveredAndPermissions() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'DELIVERED', delivered_at = now(6) where id = ?", s.deliveryId());
        assertThatThrownBy(() -> resolution.resolve(actor(), s.deliveryId(), Outcome.CANCEL_AND_REFUND, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("delivered");

        // A supplier's own token is not operations'.
        int status = api.postStatus(s.supplierToken(), "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "CANCEL_AND_REFUND", "reason", "x"));
        assertThat(status).isIn(403, 404);
        assertThat(orderStatus(s.orderId())).isEqualTo("READY_FOR_PICKUP");
    }

    @Test
    @DisplayName("the admin endpoint closes a delivery and rejects an unknown outcome")
    void endpoint() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("update delivery set status = 'PROVIDER_UNAVAILABLE' where id = ?", s.deliveryId());

        int bad = api.postStatus(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "SHRUG", "reason", "x"));
        var ok = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "CANCEL_AND_REFUND", "reason", UUID.randomUUID().toString()));

        assertThat(bad).isBetween(400, 422);
        assertThat(ok.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("ambiguous booking timeout: visible in exceptions, fails closed on reassign, and resolves via ASSOCIATE_CONSIGNMENT")
    void ambiguousBookingTimeout_associatesConsignmentSafely() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("""
                update delivery
                   set reconciliation_required = true,
                       reconciliation_reason = 'Booking outcome ambiguous with PIDGE; operator review required.',
                       status = 'PROVIDER_SELECTED'
                 where id = ?
                """, s.deliveryId());
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    failure_code, failure_reason, ended_cause, started_at, created_at)
                values (?, 'PIDGE', 1, 'BOOKING', 'FAILED', 'AMBIGUOUS_PROVIDER_OUTCOME', 'Ambiguous outcome', 'AMBIGUOUS_PROVIDER_OUTCOME', now(6), now(6))
                """, s.deliveryId());

        // 1. Appears in journey exceptions operations queue
        var exceptions = adminDeliveries.journeyExceptions(actor());
        assertThat(exceptions.stream().anyMatch(e -> "RECONCILIATION_REQUIRED".equals(e.kind()) && e.deliveryId().equals(s.deliveryId()))).isTrue();

        // 2. Fails closed: normal reassign is blocked while ambiguous
        int reassignStatus = api.postStatusWithHeader(ops, "/api/v1/deliveries/" + s.deliveryId() + "/reassign",
                Map.of("reason", "Try next"), "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(reassignStatus).isEqualTo(409);

        // 3. Operator resolves by associating discovered consignment
        var res = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "ASSOCIATE_CONSIGNMENT", "providerDeliveryId", "pidge-discovered-99", "reason", "Verified via Pidge dashboard"));

        assertThat(res.at("/data/status").asText()).isEqualTo("DRIVER_ASSIGNED");

        // Verify DB state: reconciliation flags cleared and provider delivery id recorded
        var deliveryPid = jdbc.queryForObject("select provider_delivery_id from delivery where id = ?", String.class, s.deliveryId());
        assertThat(deliveryPid).isEqualTo("pidge-discovered-99");

        var reconRequired = jdbc.queryForObject("select reconciliation_required from delivery where id = ?", Boolean.class, s.deliveryId());
        var reconReason = jdbc.queryForObject("select reconciliation_reason from delivery where id = ?", String.class, s.deliveryId());
        assertThat(reconRequired).isFalse();
        assertThat(reconReason).isNull();

        var attemptOutcome = jdbc.queryForObject("select outcome from delivery_provider_attempt where delivery_id = ? order by id desc limit 1", String.class, s.deliveryId());
        var attemptPid = jdbc.queryForObject("select provider_delivery_id from delivery_provider_attempt where delivery_id = ? order by id desc limit 1", String.class, s.deliveryId());
        assertThat(attemptOutcome).isEqualTo("BOOKED");
        assertThat(attemptPid).isEqualTo("pidge-discovered-99");

        // 4. Repeated call is idempotent
        var repeated = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "ASSOCIATE_CONSIGNMENT", "providerDeliveryId", "pidge-discovered-99", "reason", "Repeated call"));
        assertThat(repeated.at("/data/status").asText()).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    @DisplayName("ambiguous booking timeout: resolves via RESUME_BOOKING when courier confirmed non-existent")
    void ambiguousBookingTimeout_resumesBookingSafely() throws Exception {
        var s = ready("0.00", "0.00");
        jdbc.update("""
                update delivery
                   set reconciliation_required = true,
                       reconciliation_reason = 'Booking outcome ambiguous with PIDGE; operator review required.',
                       status = 'PROVIDER_UNAVAILABLE'
                 where id = ?
                """, s.deliveryId());
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    failure_code, failure_reason, ended_cause, started_at, created_at)
                values (?, 'PIDGE', 1, 'BOOKING', 'FAILED', 'AMBIGUOUS_PROVIDER_OUTCOME', 'Ambiguous outcome', 'AMBIGUOUS_PROVIDER_OUTCOME', now(6), now(6))
                """, s.deliveryId());

        var res = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "RESUME_BOOKING", "reason", "Confirmed with Pidge support that no booking exists"));

        var reconRequired = jdbc.queryForObject("select reconciliation_required from delivery where id = ?", Boolean.class, s.deliveryId());
        assertThat(reconRequired).isFalse();

        var endedCause = jdbc.queryForObject("select ended_cause from delivery_provider_attempt where delivery_id = ? order by id desc limit 1", String.class, s.deliveryId());
        assertThat(endedCause).isEqualTo("CONFIRMED_NOT_BOOKED");

        // Repeated call is idempotent
        var repeated = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "RESUME_BOOKING", "reason", "Again"));
        assertThat(repeated.at("/data/id").asLong()).isEqualTo(s.deliveryId());
    }

    @Test
    @DisplayName("post-pickup delivery failure: resolves via CANCEL_AND_REFUND, clearing reconciliation and preserving courier payable")
    void postPickupDeliveryFailure_resolvesCancelAndRefundPreservingPayable() throws Exception {
        var s = ready("0.00", "30.00");
        jdbc.update("update supplier_order set status = 'OUT_FOR_DELIVERY' where id = ?", s.orderId());
        booked(s.deliveryId(), "fail-after-pickup-1", "80.00", "DELIVERY_FAILED", true);
        jdbc.update("""
                update delivery
                   set status = 'DELIVERY_FAILED',
                       reconciliation_required = true,
                       reconciliation_reason = 'Delivery failed after pickup: vehicle broke down',
                       picked_up_at = now(6)
                 where id = ?
                """, s.deliveryId());

        var res = api.post(ops, "/api/v1/admin/deliveries/" + s.deliveryId() + "/resolve",
                Map.of("outcome", "CANCEL_AND_REFUND", "reason", "Goods lost in accident"));

        assertThat(res.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(orderStatus(s.orderId())).isEqualTo("CANCELLED");

        var reconRequired = jdbc.queryForObject("select reconciliation_required from delivery where id = ?", Boolean.class, s.deliveryId());
        assertThat(reconRequired).isFalse();

        payableSync.sync(s.deliveryId());
        var payablesList = payables.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId());
        assertThat(payablesList).isNotEmpty();
        var p = payablesList.get(0);
        // Courier picked up the goods, so payable is preserved as POST_PICKUP_FAILURE
        assertThat(p.getDueReason()).isEqualTo("POST_PICKUP_FAILURE");
    }
}
