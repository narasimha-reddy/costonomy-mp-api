package com.costonomy.mp.trust;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.payment.service.PaymentJobs;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestCheckout;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refunds asked for on a dispute, decided by the supplier or operations, paid to
 * the wallet and charged to the supplier's payout. D-104.
 *
 * <p>The property this suite exists for: <b>Costonomy never funds a refund.</b>
 * Every approved rupee is taken from the supplier's payout for the order, a refund
 * the payout cannot cover is refused, and a payout cannot be approved while a
 * refund on it is undecided — so there is never a refund with nothing left to
 * take it from.
 */
@AutoConfigureMockMvc
class DisputeRefundFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;
    @Autowired private PaymentJobs paymentJobs;
    @Autowired private SettlementService settlementService;

    private ApiClient api;
    private TestCheckout checkout;
    private TestOrder orders;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(paymentProvider, api);
        orders = new TestOrder(mvc, json, api);
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    /** An order with a dispute raised on it. 10 × ₹400: ₹4,000, commission ₹40, payout ₹3,960. */
    private record Disputed(Buyer buyer, Seller seller, long orderId, long disputeId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    private Seller newSeller() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "ABC Foods Pvt Ltd", "displayName", "ABC Foods",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "ABC store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long supplierId = created.get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);
        long storeId = created.get("stores").get(0).get("id").asLong();
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);
        return new Seller(token, storeId);
    }

    private MockHttpServletResponse post(String token, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body == null ? Map.of() : body)))
                .andReturn().getResponse();
    }

    private JsonNode ok(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).get("data");
    }

    /**
     * An order paid, captured, delivered and — if {@code received} — checked in,
     * with a dispute raised on it.
     */
    private Disputed disputed(String paymentMethod, boolean received) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();

        if ("WALLET".equals(paymentMethod)) {
            ok(post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up",
                    Map.of("amount", "5000.00")));
        }
        var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                skuId, 10, 10, "SUPPLIER_DELIVERY", paymentMethod, null);
        if (!"WALLET".equals(paymentMethod)) {
            checkout.pay(buyer.token(), placed.paymentId(), placed.providerOrderId());
        }
        long orderId = placed.orderId();

        ok(post(seller.token(), "/api/v1/supplier-orders/" + orderId + "/preparing", null));
        ok(post(seller.token(), "/api/v1/supplier-orders/" + orderId + "/ready", null));
        paymentJobs.capturePending();
        long deliveryId = ok(post(seller.token(), "/api/v1/supplier-orders/" + orderId + "/delivery", null))
                .get("id").asLong();
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());

        if (received) {
            long itemId = jdbc.queryForObject("select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, orderId);
            ok(post(buyer.token(), "/api/v1/supplier-orders/" + orderId + "/receive",
                    Map.of("items", List.of(Map.of("supplierOrderItemId", itemId, "receivedQuantity", "10",
                            "damagedQuantity", "0", "missingQuantity", "0")))));
        }

        long disputeId = ok(post(buyer.token(), "/api/v1/supplier-orders/" + orderId + "/disputes",
                Map.of("category", "QUALITY", "description", "Two packs were sour."))).get("id").asLong();
        return new Disputed(buyer, seller, orderId, disputeId);
    }

    private Disputed disputed() throws Exception {
        return disputed("PREPAID", true);
    }

    private MockHttpServletResponse ask(Disputed d, String amount) throws Exception {
        return post(d.buyer().token(), "/api/v1/disputes/" + d.disputeId() + "/refund-request",
                Map.of("amount", amount, "reason", "Sour paneer"));
    }

    private long askOk(Disputed d, String amount) throws Exception {
        return ok(ask(d, amount)).get("id").asLong();
    }

    private MockHttpServletResponse supplier(Disputed d, long requestId, String decision, String note)
            throws Exception {
        return post(d.seller().token(), "/api/v1/dispute-refunds/" + requestId + "/" + decision,
                note == null ? Map.of() : Map.of("note", note));
    }

    private MockHttpServletResponse ops(String token, long requestId, String decision) throws Exception {
        return post(token, "/api/v1/admin/dispute-refunds/" + requestId + "/" + decision,
                Map.of("note", "Checked the photos"));
    }

    private void pastSupplierWindow(long requestId) {
        jdbc.update("update dispute_refund set created_at = now(6) - interval 49 hour where id = ?", requestId);
    }

    private int generate() {
        return settlementService.generate(Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(1)));
    }

    private long settlementOf(Disputed d) {
        return jdbc.queryForObject("select settlement_id from commission_calculation where supplier_order_id = ?",
                Long.class, d.orderId());
    }

    private BigDecimal net(long settlementId) {
        return jdbc.queryForObject("select net_amount from settlement where id = ?", BigDecimal.class, settlementId);
    }

    private BigDecimal wallet(Disputed d) {
        return jdbc.queryForObject("select coalesce((select balance from wallet where outlet_id = ?), 0)",
                BigDecimal.class, d.buyer().outletId());
    }

    private String status(long requestId) {
        return jdbc.queryForObject("select status from dispute_refund where id = ?", String.class, requestId);
    }

    private int deductions(Disputed d) {
        return jdbc.queryForObject("select count(*) from supplier_deduction where supplier_order_id = ?",
                Integer.class, d.orderId());
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).at("/error/code").asText();
    }

    // ── The path ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("asked, approved, paid to the wallet, charged to the supplier")
    class ThePath {

        @Test
        @DisplayName("an approved refund credits the wallet and comes out of the supplier's next payout")
        void supplierApproves() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "500.00");

            var approved = ok(supplier(d, requestId, "approve", "Sorry about that"));

            assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
            assertThat(wallet(d)).isEqualByComparingTo("500.00");
            assertThat(jdbc.queryForMap("select destination, reason, status from refund where id = ?",
                    approved.get("refundId").asLong()))
                    .containsEntry("destination", "WALLET").containsEntry("reason", "DISPUTE_RESOLVED")
                    .containsEntry("status", "COMPLETED");
            // Not settled yet: the deduction waits for the settlement that picks the order up.
            assertThat(jdbc.queryForObject("select status from supplier_deduction where dispute_refund_id = ?",
                    String.class, requestId)).isEqualTo("PENDING");

            generate();

            long settlementId = settlementOf(d);
            // ₹4,000 − ₹40 commission − ₹500 refund. Costonomy keeps its commission.
            assertThat(net(settlementId)).isEqualByComparingTo("3460.00");
            assertThat(jdbc.queryForMap("select direction, amount, reason_code from settlement_adjustment "
                    + "where settlement_id = ? and supplier_order_id = ?", settlementId, d.orderId()))
                    .containsEntry("direction", "DEBIT").containsEntry("reason_code", "REFUND");
            assertThat(jdbc.queryForObject("select status from supplier_deduction where dispute_refund_id = ?",
                    String.class, requestId)).isEqualTo("APPLIED");
        }

        @Test
        @DisplayName("approved while the order's settlement is open, it is taken from it at once")
        void appliedToAnOpenSettlement() throws Exception {
            var d = disputed();
            generate();
            long settlementId = settlementOf(d);
            assertThat(net(settlementId)).isEqualByComparingTo("3960.00");

            ok(supplier(d, askOk(d, "960.00"), "approve", null));

            assertThat(net(settlementId)).isEqualByComparingTo("3000.00");
        }

        @Test
        @DisplayName("the dispute shows its refund request, in the thread and in both sides' lists")
        void visibleOnTheDispute() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "200.00");

            var dispute = api.get(d.buyer().token(), "/api/v1/disputes/" + d.disputeId()).get("data");
            assertThat(dispute.at("/refundRequest/id").asLong()).isEqualTo(requestId);
            assertThat(dispute.at("/refundRequest/status").asText()).isEqualTo("REQUESTED");
            assertThat(dispute.at("/refundRequest/supplierAnswerBy").isNull()).isFalse();
            // Read from the table: MockMvc decodes a response without a charset as
            // ISO-8859-1, which would garble the ₹ in the test, not in the app.
            assertThat(jdbc.queryForList("select message from dispute_message where dispute_id = ?",
                    String.class, d.disputeId())).anyMatch(m -> m.startsWith("Asked for a refund of ₹200.00"));
            assertThat(dispute.get("messages")).hasSize(2);

            var outletList = api.get(d.buyer().token(), "/api/v1/outlets/" + d.buyer().outletId() + "/disputes");
            assertThat(outletList.at("/data/0/refundRequest/id").asLong()).isEqualTo(requestId);
            var storeList = api.get(d.seller().token(), "/api/v1/supplier-stores/" + d.seller().storeId() + "/disputes");
            assertThat(storeList.at("/data/0/refundRequest/id").asLong()).isEqualTo(requestId);
            // And the supplier is told, with its clock.
            assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = "
                    + "'DisputeRefundRequested' and aggregate_id = ?", Integer.class, d.disputeId())).isEqualTo(1);
        }
    }

    // ── Costonomy never funds a refund ───────────────────────────────────

    @Nested
    @DisplayName("Costonomy never funds a refund")
    class NoLoss {

        @Test
        @DisplayName("capped at the supplier's payout for the order, not at what the restaurant paid")
        void cappedAtThePayout() throws Exception {
            var d = disputed();

            var limit = api.get(d.buyer().token(), "/api/v1/disputes/" + d.disputeId() + "/refund-limit").get("data");
            // ₹4,000 paid, but the supplier is paid ₹3,960: the ₹40 commission is not theirs to give back.
            assertThat(limit.get("maxAmount").decimalValue()).isEqualByComparingTo("3960.00");
            assertThat(ask(d, "3960.01").getStatus()).isEqualTo(400);
            assertThat(ask(d, "3960.00").getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("refused once the supplier's payout for the order is approved")
        void refusedAfterThePayout() throws Exception {
            var d = disputed();
            generate();
            String finance = operator("OPS_FINANCE");
            ok(post(finance, "/api/v1/admin/settlements/" + settlementOf(d) + "/approve", Map.of("note", "ok")));

            var refused = ask(d, "100.00");

            assertThat(refused.getStatus()).isEqualTo(409);
            assertThat(refused.getContentAsString()).contains("already been paid");
            assertThat(api.get(d.buyer().token(), "/api/v1/disputes/" + d.disputeId() + "/refund-limit")
                    .at("/data/refusal").asText()).contains("already been paid");
        }

        @Test
        @DisplayName("an undecided refund holds the payout, declined or not, until someone decides")
        void undecidedHoldsThePayout() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "300.00");
            generate();
            String finance = operator("OPS_FINANCE");
            String approve = "/api/v1/admin/settlements/" + settlementOf(d) + "/approve";

            assertThat(post(finance, approve, Map.of("note", "ok")).getStatus()).isEqualTo(409);

            ok(supplier(d, requestId, "decline", "The paneer was fine"));
            // Declined by the supplier is not the last word: operations may still approve it.
            assertThat(post(finance, approve, Map.of("note", "ok")).getStatus()).isEqualTo(409);

            ok(ops(finance, requestId, "approve"));
            var approved = ok(post(finance, approve, Map.of("note", "ok")));

            // Taken out before the figure was signed off.
            assertThat(approved.get("netAmount").decimalValue()).isEqualByComparingTo("3660.00");
            assertThat(wallet(d)).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("two approvals at once pay once, five races in a row")
        void concurrentDecisionsPayOnce() throws Exception {
            String finance = operator("OPS_FINANCE");
            for (int race = 0; race < 5; race++) {
                var d = disputed();
                long requestId = askOk(d, "250.00");
                pastSupplierWindow(requestId);

                var start = new CountDownLatch(1);
                var pool = Executors.newFixedThreadPool(2);
                var bySupplier = pool.submit(() -> { start.await(); return supplier(d, requestId, "approve", null).getStatus(); });
                var byOps = pool.submit(() -> { start.await(); return ops(finance, requestId, "approve").getStatus(); });
                start.countDown();
                var outcomes = List.of(bySupplier.get(30, TimeUnit.SECONDS), byOps.get(30, TimeUnit.SECONDS));
                pool.shutdown();

                assertThat(outcomes).contains(200);
                assertThat(wallet(d)).isEqualByComparingTo("250.00");
                assertThat(deductions(d)).isEqualTo(1);
                assertThat(jdbc.queryForObject("select count(*) from refund where supplier_order_id = ?",
                        Integer.class, d.orderId())).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("an approval sent twice pays once")
        void approvalIsIdempotent() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "100.00");

            ok(supplier(d, requestId, "approve", null));
            ok(supplier(d, requestId, "approve", null));

            assertThat(wallet(d)).isEqualByComparingTo("100.00");
            assertThat(deductions(d)).isEqualTo(1);
        }
    }

    // ── Who decides ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("who decides")
    class WhoDecides {

        @Test
        @DisplayName("operations waits 48 hours for the supplier, then may decide")
        void opsWaitsForTheSupplier() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "400.00");
            String finance = operator("OPS_FINANCE");

            var tooSoon = ops(finance, requestId, "approve");
            assertThat(tooSoon.getStatus()).isEqualTo(409);
            assertThat(tooSoon.getContentAsString()).contains("has until");
            assertThat(api.get(finance, "/api/v1/admin/dispute-refunds").get("data").toString())
                    .doesNotContain("\"id\":" + requestId + ",");

            pastSupplierWindow(requestId);
            assertThat(api.get(finance, "/api/v1/admin/dispute-refunds").get("data").toString())
                    .contains("\"id\":" + requestId + ",");
            assertThat(ok(ops(finance, requestId, "approve")).get("status").asText()).isEqualTo("OPS_APPROVED");
            assertThat(wallet(d)).isEqualByComparingTo("400.00");
            // The supplier pays, even for an operations approval.
            assertThat(deductions(d)).isEqualTo(1);
        }

        @Test
        @DisplayName("declined by both, nothing moves")
        void declinedByBoth() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "400.00");
            String finance = operator("OPS_FINANCE");

            assertThat(supplier(d, requestId, "decline", null).getStatus()).isEqualTo(400);
            ok(supplier(d, requestId, "decline", "Delivered as ordered"));
            ok(ops(finance, requestId, "decline"));

            assertThat(status(requestId)).isEqualTo("OPS_DECLINED");
            assertThat(wallet(d)).isEqualByComparingTo("0");
            assertThat(deductions(d)).isZero();
            // Decided: the payout is free to go.
            generate();
            ok(post(finance, "/api/v1/admin/settlements/" + settlementOf(d) + "/approve", Map.of("note", "ok")));
        }

        @Test
        @DisplayName("the restaurant cannot approve its own request, and another supplier cannot see it")
        void onlyTheSupplier() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "100.00");
            var stranger = newSeller();

            assertThat(post(d.buyer().token(), "/api/v1/dispute-refunds/" + requestId + "/approve", null)
                    .getStatus()).isEqualTo(404);
            assertThat(post(stranger.token(), "/api/v1/dispute-refunds/" + requestId + "/approve", null)
                    .getStatus()).isEqualTo(404);
            assertThat(wallet(d)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("support can see the queue but not decide")
        void supportCannotDecide() throws Exception {
            var d = disputed();
            long requestId = askOk(d, "100.00");
            pastSupplierWindow(requestId);
            String support = operator("OPS_SUPPORT");

            assertThat(api.getStatus(support, "/api/v1/admin/dispute-refunds")).isEqualTo(200);
            assertThat(ops(support, requestId, "approve").getStatus()).isEqualTo(403);
            assertThat(wallet(d)).isEqualByComparingTo("0");
        }
    }

    // ── Asking ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("asking")
    class Asking {

        @Test
        @DisplayName("once per dispute")
        void oncePerDispute() throws Exception {
            var d = disputed();
            askOk(d, "100.00");

            var again = ask(d, "50.00");
            assertThat(again.getStatus()).isEqualTo(409);
            assertThat(errorCode(again)).isEqualTo("REFUND_ALREADY_REQUESTED");
        }

        @Test
        @DisplayName("only once the goods have arrived")
        void onlyAfterDelivery() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                    skuId, 10, 10, "SUPPLIER_DELIVERY", null, null);
            checkout.pay(buyer.token(), placed.paymentId(), placed.providerOrderId());
            long disputeId = ok(post(buyer.token(), "/api/v1/supplier-orders/" + placed.orderId() + "/disputes",
                    Map.of("category", "OTHER", "description", "Not here yet"))).get("id").asLong();

            var refused = post(buyer.token(), "/api/v1/disputes/" + disputeId + "/refund-request",
                    Map.of("amount", "100.00"));

            // Before delivery a problem is a cancellation, which releases the hold.
            assertThat(refused.getStatus()).isEqualTo(409);
            assertThat(refused.getContentAsString()).contains("once the order has been delivered");
        }

        @Test
        @DisplayName("delivered but not yet checked in is enough, and the payout waits for it")
        void deliveredNotReceived() throws Exception {
            var d = disputed("PREPAID", false);
            long requestId = askOk(d, "500.00");
            ok(supplier(d, requestId, "approve", null));

            assertThat(wallet(d)).isEqualByComparingTo("500.00");
            assertThat(jdbc.queryForObject("select status from supplier_deduction where dispute_refund_id = ?",
                    String.class, requestId)).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("a wallet-paid order's refund goes back into the wallet, spendable but not withdrawable")
        void walletPaidOrder() throws Exception {
            var d = disputed("WALLET", true);
            assertThat(wallet(d)).isEqualByComparingTo("1000.00");

            ok(supplier(d, askOk(d, "700.00"), "approve", null));

            assertThat(wallet(d)).isEqualByComparingTo("1700.00");
            assertThat(jdbc.queryForObject("select kind from wallet_transaction where reference like 'dispute-refund-%' "
                    + "and supplier_order_id = ?", String.class, d.orderId())).isEqualTo("DISPUTE_REFUND");
            assertThat(deductions(d)).isEqualTo(1);
            // No card behind it.
            assertThat(post(d.buyer().token(), "/api/v1/outlets/" + d.buyer().outletId() + "/wallet/withdraw",
                    Map.of("amount", "700.00")).getStatus()).isEqualTo(400);
        }
    }
}
