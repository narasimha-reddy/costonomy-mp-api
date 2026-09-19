package com.costonomy.mp.settlement;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commission, settlement and reconciliation. Doc 01 §16–17, doc 03 §13, doc 09 §11.
 *
 * <p>The property this suite exists for is <b>reproducibility</b>: a settlement
 * read after a rate change must show the rate that applied when it was calculated
 * (doc 05 §33). Everything else — the state machine, adjustments, the two-record
 * reconciliation — is in service of that number being defensible months later.
 */
@AutoConfigureMockMvc
class SettlementFlowIT extends AbstractIntegrationTest {

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

    private record Seller(String token, long supplierId, long storeId) {
    }

    private record CompletedOrder(Buyer buyer, Seller seller, long orderId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
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
                "firstStore", Map.of("name", "ABC store", "addressLine1", "Road No 36",
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
        return new Seller(token, supplierId, storeId);
    }

    private JsonNode supplierPost(Seller seller, String path) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /**
     * An order taken all the way to COMPLETED, with its payment captured.
     *
     * <p>Completed, because that is when a supplier becomes settleable — the last
     * moment a shortfall can surface.
     */
    private CompletedOrder completedOrder(int quantity, String unitPrice) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        long productId = TestCatalog.freshProduct(jdbc, "paneer");

        long skuId = api.post(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
        long offerId = jdbc.queryForObject(
                "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                Long.class, skuId);

        // Through the request. D-091 removed the cart, and with it the order
        // acceptance this fixture used to perform -- the supplier commits when
        // they answer, and the order is theirs to work on from the moment it is
        // paid for.
        //
        // SUPPLIER_DELIVERY because this store carries its own, which is what
        // lets the seller report the dispatch below. Under COSTONOMY_DELIVERY
        // that is a courier's to report and the supplier is refused (§23A.38) --
        // a rule the order's mode now decides rather than the store's policy.
        var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                skuId, quantity, quantity, "SUPPLIER_DELIVERY", null, null);
        checkout.pay(buyer.token(), placed.paymentId(), placed.providerOrderId());

        long orderId = placed.orderId();
        // The capture is what reconciliation checks the settlement against.
        paymentJobs.capturePending();

        supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/preparing");
        supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/ready");

        long deliveryId = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + orderId + "/delivery")
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getContentAsString()).at("/data/id").asLong();
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());

        long itemId = jdbc.queryForObject(
                "select id from supplier_order_item where supplier_order_id = ?",
                Long.class, orderId);
        mvc.perform(MockMvcRequestBuilders
                .post("/api/v1/supplier-orders/" + orderId + "/receive")
                .header("Authorization", "Bearer " + buyer.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", List.of(Map.of(
                        "supplierOrderItemId", itemId,
                        "receivedQuantity", String.valueOf(quantity),
                        "damagedQuantity", "0", "missingQuantity", "0"))))));

        return new CompletedOrder(buyer, seller, orderId);
    }

    /**
     * Give one supplier a negotiated rate.
     *
     * <p>Supplier-scoped rather than platform-scoped so a test cannot change the
     * answer for every test that runs after it — the commission table is global
     * mutable state, and the suite shares one database.
     */
    private void setSupplierRate(long supplierId, String ratePercent) {
        jdbc.update("""
                insert into commission_configuration
                    (scope_type, scope_id, rate_percent, config_version, description,
                     effective_from, status, created_at, updated_at, version)
                values ('SUPPLIER', ?, ?, 1, 'Negotiated for this test',
                        now(6), 'ACTIVE', now(6), now(6), 0)
                """, supplierId, new BigDecimal(ratePercent));
    }

    /** Generate over a window wide enough to include everything this test made. */
    private int generate() {
        return settlementService.generate(
                Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(1)));
    }

    private JsonNode settlementFor(Seller seller) throws Exception {
        return api.get(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/settlements")
                .at("/data").get(0);
    }

    // ── Commission ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("commission")
    class Commission {

        @Test
        @DisplayName("one percent of the accepted value, excluding delivery")
        void defaultRateIsApplied() throws Exception {
            // 10 × ₹400 = ₹4,000 at 0% GST. Doc 01 §16's default is 1%.
            var order = completedOrder(10, "400");
            generate();

            var settlement = settlementFor(order.seller());

            assertThat(settlement.get("grossAmount").asDouble()).isEqualTo(4000.00);
            assertThat(settlement.get("commissionAmount").asDouble()).isEqualTo(40.00);
            assertThat(settlement.get("netAmount").asDouble()).isEqualTo(3960.00);
            assertThat(settlement.get("orderCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("a partial acceptance owes commission only on what was supplied")
        void partialAcceptanceReducesTheBase() throws Exception {
            // Doc 01 §16: "partial fulfillment commission uses actual
            // accepted/fulfilled commercial value". The supplier was not paid for
            // the rest, so they do not owe commission on it.
            var buyer = newBuyer();
            var seller = newSeller();
            long productId = TestCatalog.freshProduct(jdbc, "paneer");

            long skuId = api.post(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "P-" + productId,
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            // Ten asked for, six offered. D-091 moved the partial to the request:
            // the supplier says what they can supply, the restaurant orders that,
            // and the order is created for six rather than being an order for ten
            // that was later cut down. The commission base is the same either
            // way, which is what this asserts -- only the route changed.
            var placed = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                    skuId, 10, 6, "SUPPLIER_DELIVERY", null, null);
            checkout.pay(buyer.token(), placed.paymentId(), placed.providerOrderId());

            long orderId = placed.orderId();
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, orderId);
            paymentJobs.capturePending();

            supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/preparing");
            supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/ready");
            long deliveryId = json.readTree(mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + orderId + "/delivery")
                            .header("Authorization", "Bearer " + seller.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getContentAsString()).at("/data/id").asLong();
            api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
            api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());
            mvc.perform(MockMvcRequestBuilders
                    .post("/api/v1/supplier-orders/" + orderId + "/receive")
                    .header("Authorization", "Bearer " + buyer.token())
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "receivedQuantity", "6",
                            "damagedQuantity", "0", "missingQuantity", "0"))))));

            generate();
            var settlement = settlementFor(seller);

            // 6 × ₹400 = ₹2,400, not ₹4,000.
            assertThat(settlement.get("grossAmount").asDouble()).isEqualTo(2400.00);
            assertThat(settlement.get("commissionAmount").asDouble()).isEqualTo(24.00);
        }

        @Test
        @DisplayName("one order is never charged commission twice")
        void calculationIsIdempotent() throws Exception {
            var order = completedOrder(10, "400");

            generate();
            generate();
            generate();

            assertThat(jdbc.queryForObject(
                    "select count(*) from commission_calculation where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);

            var settlement = settlementFor(order.seller());
            assertThat(settlement.get("orderCount").asInt()).isEqualTo(1);
            assertThat(settlement.get("grossAmount").asDouble()).isEqualTo(4000.00);
        }
    }

    // ── Reproducibility ──────────────────────────────────────────────────

    @Nested
    @DisplayName("reproducibility")
    class Reproducibility {

        @Test
        @DisplayName("a settled figure does not move when the rate changes")
        void historicalSettlementIsFrozen() throws Exception {
            var order = completedOrder(10, "400");
            generate();

            var before = settlementFor(order.seller());
            assertThat(before.get("commissionAmount").asDouble()).isEqualTo(40.00);
            assertThat(before.at("/lines/0/ratePercent").asDouble()).isEqualTo(1.0);

            // This supplier renegotiates, to five times the rate. Doc 05 §33:
            // "historical settlement must not depend on current commission
            // configuration", and doc 09 §11 requires settlement to be
            // reproducible — which it is not if last month's number is a function
            // of this morning's table.
            //
            // Scoped to the supplier rather than the platform, deliberately: the
            // commission table is global mutable state, and a test that moves the
            // platform rate changes the answer for every test that runs after it.
            setSupplierRate(order.seller().supplierId(), "5.0000");

            var after = settlementFor(order.seller());
            assertThat(after.get("commissionAmount").asDouble())
                    .describedAs("the settled figure is what it was")
                    .isEqualTo(40.00);
            assertThat(after.at("/lines/0/ratePercent").asDouble())
                    .describedAs("and the line still shows the rate that applied")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("the new rate applies to the next order, not the last one")
        void newRateAppliesGoingForward() throws Exception {
            var platformRate = completedOrder(10, "400");
            var negotiated = completedOrder(10, "400");

            // One supplier negotiates 2%; the other stays on the platform default.
            // This is also the only place scope resolution is exercised — a
            // supplier-scoped row must beat the platform row for that supplier and
            // for nobody else.
            setSupplierRate(negotiated.seller().supplierId(), "2.0000");

            generate();

            assertThat(settlementFor(platformRate.seller()).get("commissionAmount").asDouble())
                    .describedAs("1% of 4000, the platform default")
                    .isEqualTo(40.00);
            assertThat(settlementFor(negotiated.seller()).get("commissionAmount").asDouble())
                    .describedAs("2% of 4000, their negotiated rate")
                    .isEqualTo(80.00);
        }
    }

    // ── The lifecycle, over HTTP ─────────────────────────────────────────

    @Nested
    @DisplayName("settling")
    class Settling {

        @Test
        @DisplayName("calculated, approved, processed, paid")
        void theWholePath() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");

            long settlementId = settlementFor(order.seller()).get("id").asLong();
            assertThat(settlementFor(order.seller()).get("status").asText())
                    .isEqualTo("CALCULATED");

            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/approve",
                    Map.of("note", "Checked against the statement"));
            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/processing",
                    Map.of());
            var paid = api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/paid",
                    Map.of("reference", "UTR20260915001")).at("/data");

            assertThat(paid.get("status").asText()).isEqualTo("PAID");
            assertThat(paid.get("paymentReference").asText()).isEqualTo("UTR20260915001");

            // Money leaving the platform is audited at every step.
            var audit = api.get(finance, "/api/v1/admin/audit?entityType=SETTLEMENT"
                    + "&entityId=" + settlementId).at("/data");
            var actions = new java.util.ArrayList<String>();
            audit.forEach(entry -> actions.add(entry.get("action").asText()));
            assertThat(actions).contains("SETTLEMENT_APPROVED", "SETTLEMENT_PAID");
        }

        @Test
        @DisplayName("approval cannot be skipped")
        void approvalIsMandatory() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            // Doc 03 §13. A settlement is money leaving the platform, and the human
            // step is where a calculation bug gets caught before it pays itself out.
            assertThat(api.postStatus(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/processing", Map.of()))
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("confirming a payment twice does not pay it twice")
        void markPaidIsIdempotent() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/approve", Map.of());
            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/processing",
                    Map.of());
            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/paid",
                    Map.of("reference", "UTR1"));

            var second = api.post(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/paid",
                    Map.of("reference", "UTR1")).at("/data");

            assertThat(second.get("status").asText()).isEqualTo("PAID");
            assertThat(second.get("paymentReference").asText()).isEqualTo("UTR1");
        }

        @Test
        @DisplayName("a supplier sees their own statement and nobody else's")
        void statementsArePrivate() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            var stranger = newSeller();

            var own = settlementFor(order.seller());
            assertThat(own.get("lines")).hasSize(1);
            assertThat(own.at("/lines/0/orderNumber").asText()).startsWith("MP-");

            // A settlement is one supplier's commercial information.
            assertThat(api.getStatus(stranger.token(),
                    "/api/v1/settlements/" + own.get("id").asLong())).isEqualTo(404);
            assertThat(api.getStatus(order.buyer().token(),
                    "/api/v1/settlements/" + own.get("id").asLong())).isEqualTo(404);
        }
    }

    // ── Adjustments ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("adjustments")
    class Adjustments {

        @Test
        @DisplayName("a credit and a debit both land in the net")
        void adjustmentsMoveTheNet() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/adjustments",
                    Map.of("direction", "CREDIT", "amount", "250.00",
                            "reasonCode", "INCENTIVE", "reason", "Onboarding incentive"));
            var adjusted = api.post(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/adjustments",
                    Map.of("direction", "DEBIT", "amount", "75.00",
                            "reasonCode", "DISPUTE_RESOLUTION",
                            "reason", "Agreed credit note on a damaged bag")).at("/data");

            // Gross - Commission ± Adjustments = Net. Doc 01 §17.
            assertThat(adjusted.get("adjustmentAmount").asDouble()).isEqualTo(175.00);
            assertThat(adjusted.get("netAmount").asDouble()).isEqualTo(4135.00);
            assertThat(adjusted.get("adjustments")).hasSize(2);
            // Every adjustment carries its reason — an unexplained deduction from a
            // supplier's payout is what doc 01 §17's audit requirement prevents.
            assertThat(adjusted.at("/adjustments/1/reason").asText())
                    .contains("damaged bag");
        }

        @Test
        @DisplayName("an approved settlement can no longer be adjusted")
        void approvedFiguresAreFrozen() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            api.post(finance, "/api/v1/admin/settlements/" + settlementId + "/approve", Map.of());

            // A correction after approval belongs to the next settlement, with its
            // own reason — silently changing a signed-off number is how two parties
            // end up holding different statements.
            assertThat(api.postStatus(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/adjustments",
                    Map.of("direction", "DEBIT", "amount", "100.00",
                            "reasonCode", "MANUAL_CORRECTION", "reason", "Too late")))
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("an adjustment needs a reason")
        void adjustmentsNeedAReason() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            assertThat(api.postStatus(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/adjustments",
                    Map.of("direction", "DEBIT", "amount", "100.00",
                            "reasonCode", "MANUAL_CORRECTION"))).isEqualTo(400);
        }
    }

    // ── Reconciliation ───────────────────────────────────────────────────

    @Nested
    @DisplayName("reconciliation")
    class Reconciliation {

        @Test
        @DisplayName("a settlement matches what restaurants actually paid")
        void grossMatchesCaptured() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            var result = api.post(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/reconcile", Map.of())
                    .at("/data");

            // Two independent records of the same money: what the order records say
            // is owed, and what the payment records say was captured.
            assertThat(result.get("matched").asBoolean()).isTrue();
            assertThat(result.get("settlementGross").asDouble()).isEqualTo(4000.00);
            assertThat(result.get("capturedGross").asDouble()).isEqualTo(4000.00);
            assertThat(result.get("difference").asDouble()).isZero();
        }

        @Test
        @DisplayName("a refund shows up as a discrepancy rather than being absorbed")
        void refundBreaksTheMatch() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            // Money went back to the restaurant after the settlement was
            // calculated. The supplier's payout no longer matches what was taken.
            jdbc.update("update payment set refunded_amount = 500.00 "
                    + "where supplier_order_id = ?", order.orderId());

            var result = api.post(finance,
                    "/api/v1/admin/settlements/" + settlementId + "/reconcile", Map.of())
                    .at("/data");

            assertThat(result.get("matched").asBoolean()).isFalse();
            assertThat(result.get("capturedGross").asDouble()).isEqualTo(3500.00);
            assertThat(result.get("difference").asDouble()).isEqualTo(500.00);
            assertThat(result.get("note").asText()).contains("unaccounted refund");

            // Recorded, not thrown: a discrepancy needs a human, and refusing would
            // make it block every later run instead of surfacing.
            assertThat(jdbc.queryForObject(
                    "select status from settlement where id = ?",
                    String.class, settlementId)).isEqualTo("CALCULATED");
            // And it is on the audit trail, because this is money.
            var audit = api.get(finance,
                    "/api/v1/admin/audit?action=SETTLEMENT_RECONCILIATION_MISMATCH").at("/data");
            assertThat(audit).isNotEmpty();
        }

        @Test
        @DisplayName("reconciling repeatedly gives the same answer")
        void reconciliationIsIdempotent() throws Exception {
            var order = completedOrder(10, "400");
            generate();
            String finance = operator("OPS_FINANCE");
            long settlementId = settlementFor(order.seller()).get("id").asLong();

            // Doc 03 §13. It recomputes from the same two sources and overwrites
            // its own last answer, so nothing accumulates.
            for (int i = 0; i < 3; i++) {
                var result = api.post(finance,
                        "/api/v1/admin/settlements/" + settlementId + "/reconcile", Map.of())
                        .at("/data");
                assertThat(result.get("matched").asBoolean()).isTrue();
                assertThat(result.get("difference").asDouble()).isZero();
            }
        }
    }
}
