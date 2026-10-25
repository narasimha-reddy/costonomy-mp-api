package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.service.CourierPayableService;
import com.costonomy.mp.delivery.service.SupplierCourierPayableSync;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.DeliveryFixture;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * What finance, support, the supplier and the restaurant can see and do about delivery money (D-204): permissions,
 * the bounded queues, the operator actions and their audit, and the supplier and customer views.
 */
@AutoConfigureMockMvc
class DeliveryFinanceAdminIT extends AbstractIntegrationTest {

    private static final String MANUAL = "FIN_MANUAL";
    private static final String BASE = "/api/v1/admin/delivery-finance";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private SupplierCourierPayableSync sync;
    @Autowired private CourierPayableService payableService;

    private ApiClient api;
    private String finance;
    private String support;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiClient(mvc, json);
        DeliveryFixture.provider(jdbc, MANUAL, "MANUAL");
        finance = operator("OPS_FINANCE");
        support = operator("OPS_SUPPORT");
    }

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    /** A payable on the manual rail that is due. */
    private CourierPayable dueManual(String fee) throws Exception {
        var s = DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, "0.00", "0.00");
        String pdid = "fin-" + UUID.randomUUID();
        jdbc.update("""
                insert into delivery_provider_attempt (delivery_id, provider_code, attempt_number, attempt_type, outcome,
                    provider_delivery_id, quoted_amount, started_at, created_at)
                values (?, ?, 1, 'BOOKING', 'BOOKED', ?, ?, now(6), now(6))
                """, s.deliveryId(), MANUAL, pdid, new BigDecimal(fee));
        jdbc.update("update delivery set status = 'PICKED_UP', provider_code = ?, provider_delivery_id = ?, fee = ?, "
                        + "mode = 'COSTONOMY', picked_up_at = now(6), version = version + 1 where id = ?",
                MANUAL, pdid, new BigDecimal(fee), s.deliveryId());
        sync.sync(s.deliveryId());
        var p = payableService.forDelivery(CourierPayable.SUPPLIER_DELIVERY, s.deliveryId()).get(0);
        assertThat(p.getState()).isEqualTo(CourierPayableState.DUE);
        return p;
    }

    private void state(long id, String state) {
        jdbc.update("update courier_payable set state = ?, version = version + 1 where id = ?", state, id);
    }

    private static Map<String, Object> body(Object... kv) {
        var m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long refund(String status, String source, long sourceId, String amount) {
        String key = "fin-" + UUID.randomUUID();
        jdbc.update("""
                insert into gateway_refund (source_type, source_id, purpose, provider_payment_id, captured_amount, amount,
                    reason, idempotency_key, status, attempt_count, requested_at)
                values (?, ?, 'CANCELLED', ?, ?, ?, 'test', ?, ?, 6, now(6) - interval 3 hour)
                """, source, sourceId, "pay_" + key, new BigDecimal(amount), new BigDecimal(amount), key, status);
        return jdbc.queryForObject("select id from gateway_refund where idempotency_key = ?", Long.class, key);
    }

    // ── permissions ───────────────────────────────────────────────────────

    @Test
    @DisplayName("support can look at the finance queues and nothing else; a restaurant cannot see them at all")
    void roleVisibility() throws Exception {
        var p = dueManual("70.00");
        String restaurant = api.loginFresh();

        assertThat(api.getStatus(support, BASE + "/queues")).isEqualTo(200);
        assertThat(api.getStatus(support, BASE + "/payables/" + p.getId())).isEqualTo(200);
        assertThat(api.getStatus(finance, BASE + "/refunds")).isEqualTo(200);

        assertThat(api.postStatus(support, BASE + "/payables/" + p.getId() + "/mark-paid",
                body("amount", 70, "reference", "UTR-1"))).isEqualTo(403);
        assertThat(api.postStatus(support, BASE + "/payables/" + p.getId() + "/void", body("note", "x"))).isEqualTo(403);

        assertThat(api.getStatus(restaurant, BASE + "/queues")).isIn(403, 404);
        assertThat(api.postStatus(restaurant, BASE + "/payables/" + p.getId() + "/mark-paid",
                body("amount", 70, "reference", "UTR-1"))).isIn(403, 404);
        assertThat(payableService.get(p.getId()).getState()).as("nothing changed").isEqualTo(CourierPayableState.DUE);
    }

    // ── queues ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the queues separate overdue, exhausted, ambiguous and unreconciled payables and refunds, with totals")
    void queuesAndTotals() throws Exception {
        var exhausted = dueManual("11.00");
        var ambiguous = dueManual("12.00");
        var overdue = dueManual("13.00");
        var unreconciled = dueManual("14.00");
        state(exhausted.getId(), "EXHAUSTED");
        state(ambiguous.getId(), "AMBIGUOUS");
        jdbc.update("update courier_payable set due_at = now(6) - interval 30 day where id = ?", overdue.getId());
        jdbc.update("update courier_payable set reconciliation_state = 'REQUIRED' where id = ?", unreconciled.getId());
        long refundExhausted = refund("EXHAUSTED", "FOOD_DELIVERY", 9_001, "40.00");
        long refundAmbiguous = refund("AMBIGUOUS", "FOOD_DELIVERY", 9_002, "41.00");
        long refundStuck = refund("FAILED", "SUPPLIER_DELIVERY_FUNDING", 9_003, "42.00");

        JsonNode q = api.get(finance, BASE + "/queues").path("data");

        assertThat(ids(q.path("exhausted"))).contains(exhausted.getId()).doesNotContain(ambiguous.getId());
        assertThat(ids(q.path("ambiguous"))).contains(ambiguous.getId()).doesNotContain(exhausted.getId());
        assertThat(ids(q.path("overdue"))).contains(overdue.getId());
        assertThat(ids(q.path("unreconciled"))).contains(unreconciled.getId());
        assertThat(ids(q.path("awaitingManualPayment"))).contains(overdue.getId(), unreconciled.getId());
        assertThat(ids(q.path("refundsExhausted"))).contains(refundExhausted);
        assertThat(ids(q.path("refundsAmbiguous"))).contains(refundAmbiguous);
        assertThat(ids(q.path("refundsOverdue"))).as("sent but not done").contains(refundStuck).doesNotContain(refundExhausted);

        JsonNode t = q.path("totals");
        assertThat(t.path("exhaustedPayables").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(t.path("ambiguousPayables").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(t.path("refundsExhausted").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(t.path("courierDue").decimalValue()).as("courier money is its own figure")
                .isGreaterThanOrEqualTo(new BigDecimal("50.00"));
        assertThat(t.path("customerRefundsPending").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("123.00"));
        assertThat(q.path("overdue").size()).isLessThanOrEqualTo(100);
    }

    private static java.util.List<Long> ids(JsonNode arr) {
        var out = new java.util.ArrayList<Long>();
        arr.forEach(n -> out.add(n.path("id").asLong()));
        return out;
    }

    @Test
    @DisplayName("a payable can be searched by state and read with its attempts and events")
    void searchAndDetail() throws Exception {
        var p = dueManual("33.00");

        JsonNode list = api.get(finance, BASE + "/payables?state=DUE&providerCode=" + MANUAL + "&size=5").path("data");
        assertThat(ids(list.path("items"))).contains(p.getId());
        assertThat(list.path("size").asInt()).isEqualTo(5);

        JsonNode detail = api.get(finance, BASE + "/payables/" + p.getId()).path("data");
        assertThat(detail.path("payable").path("amountDue").decimalValue()).isEqualByComparingTo("33.00");
        assertThat(detail.path("events").size()).isGreaterThanOrEqualTo(1);
    }

    // ── payable actions ────────────────────────────────────────────────────

    @Test
    @DisplayName("finance records a bank payment once; a second recording is refused and the audit trail names the person")
    void markPaidOnceAndAudited() throws Exception {
        var p = dueManual("55.00");
        String url = BASE + "/payables/" + p.getId() + "/mark-paid";

        JsonNode paid = api.post(finance, url, body("amount", 55, "reference", "UTR-55", "note", "bank transfer")).path("data");
        assertThat(paid.path("state").asText()).isEqualTo("PAID");
        assertThat(paid.path("amountPaid").decimalValue()).isEqualByComparingTo("55.00");
        assertThat(paid.path("paymentReference").asText()).isEqualTo("UTR-55");

        int again = api.postStatus(finance, url, body("amount", 55, "reference", "UTR-55", "note", "again"));
        assertThat(again).isGreaterThanOrEqualTo(400);
        assertThat(payableService.get(p.getId()).getAmountPaid()).as("not paid twice").isEqualByComparingTo("55.00");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'COURIER_PAYABLE' "
                + "and entity_id = ? and action = 'COURIER_PAYABLE_MARKED_PAID'", Integer.class, p.getId())).isEqualTo(1);
        assertThat(payableService.history(p.getId()).stream().map(e -> e.getActor())
                .anyMatch(a -> a != null && a.startsWith("ops:"))).isTrue();
    }

    @Test
    @DisplayName("an exhausted payable is put back by a person, who must say why")
    void retryNeedsAReason() throws Exception {
        var p = dueManual("22.00");
        state(p.getId(), "EXHAUSTED");
        String url = BASE + "/payables/" + p.getId() + "/retry";

        assertThat(api.postStatus(finance, url, body("note", ""))).isEqualTo(400);
        assertThat(payableService.get(p.getId()).getState()).isEqualTo(CourierPayableState.EXHAUSTED);

        JsonNode back = api.post(finance, url, body("note", "provider fixed their account")).path("data");
        assertThat(back.path("state").asText()).isIn("DUE", "FAILED");
    }

    @Test
    @DisplayName("a cancellation fee and a provider credit change what is due, and an ambiguous payment is decided by a person")
    void adjustmentsAndAmbiguity() throws Exception {
        var p = dueManual("60.00");
        String base = BASE + "/payables/" + p.getId();

        JsonNode fee = api.post(finance, base + "/cancellation-fee", body("amount", 15, "note", "invoice 77")).path("data");
        assertThat(fee.path("cancellationFee").decimalValue()).isEqualByComparingTo("15.00");
        JsonNode credit = api.post(finance, base + "/provider-credit", body("amount", 5, "note", "credit note 3")).path("data");
        assertThat(credit.path("providerCredit").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(credit.path("amountDue").decimalValue()).isEqualByComparingTo(
                fee.path("amountDue").decimalValue().subtract(new BigDecimal("5.00")));

        state(p.getId(), "AMBIGUOUS");
        JsonNode resolved = api.post(finance, base + "/resolve-ambiguous",
                body("paid", true, "reference", "UTR-AMB", "note", "seen on statement")).path("data");
        assertThat(resolved.path("state").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("an unreconciled flag is closed only with a note on how")
    void reconciliationNeedsANote() throws Exception {
        var p = dueManual("44.00");
        jdbc.update("update courier_payable set reconciliation_state = 'REQUIRED' where id = ?", p.getId());
        String url = BASE + "/payables/" + p.getId() + "/resolve-reconciliation";

        assertThat(api.postStatus(finance, url, body("note", " "))).isEqualTo(400);
        JsonNode done = api.post(finance, url, body("note", "matched to invoice 12")).path("data");
        assertThat(done.path("reconciliationState").asText()).isEqualTo("RESOLVED");
    }

    // ── gateway refund actions ────────────────────────────────────────────

    @Test
    @DisplayName("an exhausted gateway refund is requeued, and one made in the gateway's dashboard is recorded, never both")
    void gatewayRefundActions() throws Exception {
        long exhausted = refund("EXHAUSTED", "FOOD_DELIVERY", 9_101, "30.00");
        long manual = refund("AMBIGUOUS", "FOOD_DELIVERY", 9_102, "31.00");

        JsonNode requeued = api.post(finance, BASE + "/refunds/" + exhausted + "/retry", body("note", "gateway back")).path("data");
        assertThat(requeued.path("status").asText()).isEqualTo("REQUESTED");
        assertThat(api.postStatus(support, BASE + "/refunds/" + exhausted + "/retry", body("note", "x"))).isEqualTo(403);

        JsonNode done = api.post(finance, BASE + "/refunds/" + manual + "/resolve",
                body("providerRefundId", "rfnd_dash_1", "note", "seen in dashboard")).path("data");
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("providerRefundId").asText()).isEqualTo("rfnd_dash_1");
        assertThat(api.postStatus(finance, BASE + "/refunds/" + manual + "/resolve",
                body("providerRefundId", "rfnd_dash_2", "note", "again"))).isGreaterThanOrEqualTo(400);
        assertThat(jdbc.queryForObject("select provider_refund_id from gateway_refund where id = ?", String.class, manual))
                .isEqualTo("rfnd_dash_1");
    }

    // ── the supplier and the restaurant ───────────────────────────────────

    @Test
    @DisplayName("a supplier sees every delivery-funding line in every state, in separate buckets, and never a courier payment")
    void supplierView() throws Exception {
        var s = DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, "0.00", "40.00");
        // A delivery carries one funding line, so each state sits on its own delivery; all are put on the first store.
        var orders = new java.util.ArrayList<Long>();
        orders.add(s.orderId());
        jdbc.update("delete from supplier_deduction where supplier_order_id = ? and kind = 'DELIVERY_FUNDING'", s.orderId());
        insertDeduction(s, "PENDING", "40.00");
        for (String[] other : new String[][]{{"PAID_DIRECTLY", "25.00"}, {"APPLIED", "15.00"}, {"WAIVED", "10.00"}}) {
            var o = DeliveryFixture.create(mvc, json, api, jdbc, paymentProvider, "0.00", "10.00");
            jdbc.update("delete from supplier_deduction where supplier_order_id = ? and kind = 'DELIVERY_FUNDING'", o.orderId());
            insertDeduction(o, other[0], other[1]);
            jdbc.update("update supplier_deduction set supplier_store_id = ? where supplier_order_id = ?", s.storeId(), o.orderId());
            orders.add(o.orderId());
        }
        String url = "/api/v1/supplier-stores/" + s.storeId() + "/delivery-funding";

        JsonNode sum = api.get(s.supplierToken(), url + "/summary").path("data");
        assertThat(sum.path("pending").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(sum.path("collectedDirectly").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(sum.path("appliedToSettlement").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(sum.path("waived").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(sum.has("courierDue")).isFalse();

        JsonNode lines = api.get(s.supplierToken(), url + "/history").path("data");
        assertThat(lines.size()).isEqualTo(4);
        var outcomes = new java.util.HashSet<String>();
        lines.forEach(n -> outcomes.add(n.path("outcome").asText()));
        assertThat(outcomes).containsExactlyInAnyOrder("OWED", "PAID_DIRECTLY", "TAKEN_FROM_SETTLEMENT", "WAIVED");
        lines.forEach(n -> {
            assertThat(orders).contains(n.path("supplierOrderId").asLong());
            if ("OWED".equals(n.path("outcome").asText())) {
                assertThat(n.path("nextAction").asText()).startsWith("PAY_DIRECTLY");
            } else {
                assertThat(n.path("nextAction").asText()).isEqualTo("NONE");
            }
        });
        assertThat(api.get(s.supplierToken(), url + "/history?status=WAIVED").path("data").size()).isEqualTo(1);

        String otherSupplier = api.loginFresh();
        assertThat(api.getStatus(otherSupplier, url + "/history")).isIn(403, 404);
        assertThat(api.getStatus(otherSupplier, url + "/summary")).isIn(403, 404);
    }

    private void insertDeduction(DeliveryFixture.Setup s, String status, String amount) {
        jdbc.update("""
                insert into supplier_deduction (supplier_store_id, supplier_order_id, dispute_refund_id, amount, reason,
                    status, kind, delivery_id, created_at, updated_at, version)
                values (?, ?, null, ?, 'Costonomy delivery fee', ?, ?, ?, now(6), now(6), 0)
                """, s.storeId(), s.orderId(), new BigDecimal(amount), status, "DELIVERY_FUNDING", s.deliveryId());
    }
}
