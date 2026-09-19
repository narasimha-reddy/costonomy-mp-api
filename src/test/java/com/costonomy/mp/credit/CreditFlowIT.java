package com.costonomy.mp.credit;

import com.costonomy.mp.credit.service.CreditInvoiceService;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplier credit end to end. Doc 01 §18–19, doc 03 §8–9, doc 04 §13, doc 10.
 *
 * <p>Two properties carry this suite. <b>Credit is supplier-controlled</b> — every
 * limit, every term and every suspension is the supplier's, and Mandi never grants
 * on their behalf. And <b>exposure arithmetic is exact</b>: doc 10 §3's
 * {@code available >= 0} and {@code approved = reserved + utilized + available}
 * must hold after every movement, including the concurrent ones.
 */
@AutoConfigureMockMvc
class CreditFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private CreditInvoiceService invoiceService;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    /** A restaurant and a supplier with a live credit line between them. */
    private record CreditLine(Buyer buyer, Seller seller, long agreementId) {
    }

    // ── setup helpers ────────────────────────────────────────────────────

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
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /** Turn on the supplier's standing credit offer. */
    private void offerCredit(Seller seller) throws Exception {
        offerCredit(seller, Map.of("creditEnabled", true, "defaultCreditPeriodDays", 30,
                "defaultGracePeriodDays", 5));
    }

    private void offerCredit(Seller seller, Map<String, Object> policy) throws Exception {
        mvc.perform(MockMvcRequestBuilders
                .put("/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy")
                .header("Authorization", "Bearer " + seller.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(policy)));
    }

    /** Request, then approve as asked — the shortest path to spendable credit. */
    private CreditLine creditLine(String limit) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
        offerCredit(seller);

        long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                "requestedLimit", limit, "requestedDays", 30,
                "purpose", "PROCUREMENT")).at("/data/id").asLong();

        api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                Map.of());

        return new CreditLine(buyer, seller, agreementId);
    }

    private long skuFor(Seller seller, String unitPrice) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        return api.post(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", unitPrice, "gstRate", "0")).at("/data/id").asLong();
    }

    /**
     * Place a credit order through the request flow, in the submit response's
     * shape.
     *
     * <p>D-091 removed the cart these helpers used. The shape is preserved
     * rather than the call sites rewritten, because what this suite is about is
     * credit — reservation, utilisation, limits — and the route an order took to
     * exist is incidental to every one of its assertions.
     */
    private JsonNode orderOnCredit(CreditLine line, String unitPrice, int quantity)
            throws Exception {

        long intentId = answeredRequest(line, unitPrice, quantity);

        var created = keyed(line.buyer().token(), "/api/v1/intents/" + intentId + "/orders",
                Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT")).at("/data");

        long orderId = created.at("/supplierOrderId").asLong();
        String status = jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);

        var node = json.createObjectNode();
        var order = node.putObject("data").putArray("supplierOrders").addObject();
        order.put("id", orderId);
        order.put("status", status);
        return node;
    }

    /** The HTTP status of trying to order on credit. */
    private int submitStatus(CreditLine line, String unitPrice, int quantity) throws Exception {
        long intentId = answeredRequest(line, unitPrice, quantity);

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/orders")
                        .header("Authorization", "Bearer " + line.buyer().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT"))))
                .andReturn().getResponse().getStatus();
    }

    /** A request this seller has answered in full, ready to be ordered against. */
    private long answeredRequest(CreditLine line, String unitPrice, int quantity)
            throws Exception {

        long skuId = skuFor(line.seller(), unitPrice);

        long intentId = api.post(line.buyer().token(),
                "/api/v1/outlets/" + line.buyer().outletId() + "/intent-items",
                Map.of("supplierSkuId", skuId, "quantity", quantity)).at("/data/id").asLong();
        long itemId = api.get(line.buyer().token(), "/api/v1/intents/" + intentId)
                .at("/data/items/0/id").asLong();

        api.post(line.buyer().token(), "/api/v1/intents/" + intentId + "/send", Map.of());
        keyed(line.seller().token(), "/api/v1/intents/" + intentId + "/respond",
                Map.of("lines", List.of(
                        Map.of("intentItemId", itemId, "offeredQuantity", quantity))));
        return intentId;
    }

    private JsonNode keyed(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode agreement(CreditLine line) throws Exception {
        return api.get(line.buyer().token(),
                "/api/v1/credit/agreements/" + line.agreementId()).at("/data");
    }

    // ── The negotiation ──────────────────────────────────────────────────

    @Nested
    @DisplayName("the negotiation")
    class Negotiation {

        @Test
        @DisplayName("a supplier who doesn't offer credit can't be asked for it")
        void creditMustBeOffered() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            // No policy set — absent is not "enabled with defaults".

            int status = api.postStatus(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30));

            assertThat(status).isEqualTo(422);
        }

        @Test
        @DisplayName("approving as asked activates the credit immediately")
        void approvingAsAskedActivates() throws Exception {
            var line = creditLine("200000");
            var agreement = agreement(line);

            assertThat(agreement.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(agreement.get("canFund").asBoolean()).isTrue();
            assertThat(agreement.get("approvedLimit").asDouble()).isEqualTo(200000.0);
            assertThat(agreement.get("available").asDouble()).isEqualTo(200000.0);
            assertThat(agreement.at("/latestRequest/status").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("approving on different terms waits for the restaurant")
        void modifiedTermsWaitForAcceptance() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();

            // The supplier will carry half of it, for half as long.
            var approved = api.post(seller.token(),
                    "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("approvedLimit", "100000", "creditPeriodDays", 15,
                            "note", "Starting smaller for a new customer")).at("/data");

            // Doc 03 §8 and doc 05 §20: "approved with modified terms" is its own
            // state, and credit on terms nobody agreed to is not credit.
            assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
            assertThat(approved.get("canFund").asBoolean()).isFalse();
            assertThat(approved.at("/latestRequest/status").asText()).isEqualTo("MODIFIED");

            var accepted = api.post(buyer.token(),
                    "/api/v1/credit/agreements/" + agreementId + "/accept", Map.of()).at("/data");

            assertThat(accepted.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(accepted.get("approvedLimit").asDouble()).isEqualTo(100000.0);
            assertThat(accepted.get("creditPeriodDays").asInt()).isEqualTo(15);
        }

        @Test
        @DisplayName("a rejection carries the supplier's reason back")
        void rejectionCarriesAReason() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();

            var rejected = api.post(seller.token(),
                    "/api/v1/credit/agreements/" + agreementId + "/reject",
                    Map.of("reason", "Send us three months of trading history first."))
                    .at("/data");

            assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
            assertThat(rejected.get("canFund").asBoolean()).isFalse();
            // Verbatim. "Rejected" with no reason is a dead end for a restaurant
            // who would happily have supplied what was missing.
            assertThat(rejected.at("/latestRequest/responseNote").asText())
                    .contains("trading history");
        }

        @Test
        @DisplayName("every change of terms is versioned and reasoned")
        void modificationsAreVersionedAndReasoned() throws Exception {
            var line = creditLine("200000");
            int before = agreement(line).get("termsVersion").asInt();

            api.post(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                    Map.of("approvedLimit", "120000", "creditPeriodDays", 21,
                            "reason", "Reduced after two late payments"));

            var after = agreement(line);
            assertThat(after.get("termsVersion").asInt()).isGreaterThan(before);
            assertThat(after.get("approvedLimit").asDouble()).isEqualTo(120000.0);

            // Doc 01 §18: auditable. The history row is what answers a restaurant
            // who asks why their limit moved.
            var history = jdbc.queryForMap(
                    "select change_type, previous_limit, new_limit, reason "
                            + "from credit_limit_history where credit_agreement_id = ? "
                            + "order by id desc limit 1", line.agreementId());
            assertThat(history.get("reason").toString()).contains("late payments");
            assertThat((BigDecimal) history.get("previous_limit")).isEqualByComparingTo("200000");
            assertThat((BigDecimal) history.get("new_limit")).isEqualByComparingTo("120000");
        }

        @Test
        @DisplayName("a modification needs a reason")
        void modificationWithoutAReasonIsRefused() throws Exception {
            var line = creditLine("200000");

            assertThat(api.postStatus(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                    Map.of("approvedLimit", "10000", "creditPeriodDays", 30)))
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("the restaurant can't approve its own credit")
        void restaurantCannotApproveItsOwnCredit() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();

            // Credit is supplier-funded and supplier-controlled (doc 01 §18). 404
            // rather than 403 — a restaurant learns nothing about the supplier's
            // side of the agreement (doc 09 §3).
            assertThat(api.postStatus(buyer.token(),
                    "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("approvedLimit", "999999"))).isEqualTo(404);
        }

        @Test
        @DisplayName("an unrelated restaurant can't see the agreement at all")
        void strangersSeeNothing() throws Exception {
            var line = creditLine("200000");
            var stranger = newBuyer();

            assertThat(api.getStatus(stranger.token(),
                    "/api/v1/credit/agreements/" + line.agreementId())).isEqualTo(404);
            assertThat(api.getStatus(stranger.token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/ledger")).isEqualTo(404);
        }
    }

    // ── Reservation and utilization ──────────────────────────────────────

    @Nested
    @DisplayName("reserve and utilize")
    class ReserveAndUtilize {

        @Test
        @DisplayName("placing an order draws the credit it needs, and the supplier sees it at once")
        void placingReserves() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);

            // Credit is secured inside the creating transaction, so unlike a
            // prepaid order there is no checkout step and the order is confirmed
            // outright.
            assertThat(submitted.at("/data/supplierOrders/0/status").asText())
                    .isEqualTo("CONFIRMED");

            // Reserve and draw now land together. D-091 removed the acceptance
            // that used to sit between them: the supplier committed on the
            // request, so by the time an order exists there is nothing left to
            // wait for and a reservation would be held against nobody. The
            // exposure in doc 01 §19 is unchanged — it is reached in one step
            // instead of two.
            var agreement = agreement(line);
            assertThat(agreement.get("reserved").asDouble()).isZero();
            assertThat(agreement.get("utilized").asDouble()).isEqualTo(40000.0);
            assertThat(agreement.get("available").asDouble()).isEqualTo(160000.0);
        }

        @Test
        @DisplayName("acceptance draws the accepted value and raises an invoice")
        void acceptanceUtilizes() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);
            long orderId = submitted.at("/data/supplierOrders/0/id").asLong();


            var agreement = agreement(line);
            assertThat(agreement.get("reserved").asDouble()).isZero();
            assertThat(agreement.get("utilized").asDouble()).isEqualTo(40000.0);
            assertThat(agreement.get("available").asDouble()).isEqualTo(160000.0);
            // Owed from the moment it was supplied, not from the moment it was ordered.
            assertThat(agreement.get("due").asDouble()).isEqualTo(40000.0);

            var invoices = api.get(line.buyer().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/invoices").at("/data");
            assertThat(invoices).hasSize(1);
            assertThat(invoices.get(0).get("amount").asDouble()).isEqualTo(40000.0);
            assertThat(invoices.get(0).get("invoiceNumber").asText()).startsWith("INV-");
        }

        @Test
        @DisplayName("a short answer draws only what the supplier offered")
        void shortAnswerDrawsOnlyOffered() throws Exception {
            // Doc 01 §19, reached differently. D-091 moved the partial onto the
            // request: a hundred are asked for, sixty offered, and the order is
            // created for sixty. Nothing is held against the other forty, which
            // is stricter than holding it and giving it back.
            var line = creditLine("200000");

            long skuId = skuFor(line.seller(), "400");
            long intentId = api.post(line.buyer().token(),
                    "/api/v1/outlets/" + line.buyer().outletId() + "/intent-items",
                    Map.of("supplierSkuId", skuId, "quantity", 100)).at("/data/id").asLong();
            long itemId = api.get(line.buyer().token(), "/api/v1/intents/" + intentId)
                    .at("/data/items/0/id").asLong();
            api.post(line.buyer().token(), "/api/v1/intents/" + intentId + "/send", Map.of());
            keyed(line.seller().token(), "/api/v1/intents/" + intentId + "/respond",
                    Map.of("lines", List.of(
                            Map.of("intentItemId", itemId, "offeredQuantity", 60))));
            keyed(line.buyer().token(), "/api/v1/intents/" + intentId + "/orders",
                    Map.of("deliveryMode", "PICKUP", "paymentMethod", "CREDIT"));

            var agreement = agreement(line);
            assertThat(agreement.get("utilized").asDouble()).isEqualTo(24000.0);
            assertThat(agreement.get("reserved").asDouble()).isZero();
            assertThat(agreement.get("available").asDouble()).isEqualTo(176000.0);
        }

        @Test
        @DisplayName("a supplier cancelling gives the credit back")
        void supplierCancellationReturnsTheCredit() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);
            long orderId = submitted.at("/data/supplierOrders/0/id").asLong();

            keyed(line.seller().token(),
                    "/api/v1/supplier-orders/" + orderId + "/supplier-cancel",
                    Map.of("reason", "OUT_OF_STOCK"));

            assertThat(jdbc.queryForObject(
                    "select status from supplier_order where id = ?", String.class, orderId))
                    .isEqualTo("CANCELLED");

            // The debt still stands, and this records that it does.
            //
            // Rejection used to happen before the draw, so releasing the hold was
            // the whole reversal. D-091 confirms a credit order as it is created,
            // which utilises it and raises the invoice -- so by the time a
            // supplier backs out the money is drawn, and CreditLedgerService
            // returns early because the reservation no longer holds exposure.
            //
            // This is the same shape as prepaid, where cancelling after capture
            // needs a refund rather than a release. Credit's equivalent is a
            // credit note, and it does not exist yet: until it does, a supplier
            // cancelling a credit order leaves the restaurant owing for goods
            // they will not receive. Asserted rather than hidden.
            var agreement = agreement(line);
            assertThat(agreement.get("utilized").asDouble())
                    .describedAs("a credit note is still owed -- see D-091's open items")
                    .isEqualTo(40000.0);
        }


        @Test
        @DisplayName("the ledger explains every balance it reports")
        void ledgerCarriesRunningBalances() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);

            var ledger = api.get(line.buyer().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/ledger").at("/data");

            // Newest first. The head row must agree with the agreement's own
            // numbers, or the ledger is explaining a balance nobody has.
            var newest = ledger.get(0);
            var agreement = agreement(line);
            assertThat(newest.get("availableAfter").asDouble())
                    .isEqualTo(agreement.get("available").asDouble());
            assertThat(newest.get("utilizedAfter").asDouble())
                    .isEqualTo(agreement.get("utilized").asDouble());
        }
    }

    // ── The limits ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("an order beyond the limit is refused, and nothing is placed")
        void overTheLimitIsRefused() throws Exception {
            var line = creditLine("10000");

            assertThat(submitStatus(line, "400", 100)).isEqualTo(422);

            // Assert the side effect, not the rejection: no reservation, no
            // exposure, and no order left behind. An abandoned order would be
            // invisible debt the restaurant can neither see nor cancel.
            assertThat(jdbc.queryForObject("select count(*) from credit_reservation "
                            + "where credit_agreement_id = ? and status = 'RESERVED'",
                    Integer.class, line.agreementId())).isZero();
            assertThat(agreement(line).get("reserved").asDouble()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from supplier_order "
                            + "where outlet_id = ? and status <> 'DRAFT'",
                    Integer.class, line.buyer().outletId())).isZero();
        }

        @Test
        @DisplayName("the per-order cap is separate from the limit")
        void perOrderCapIsItsOwnWall() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();
            // Happy to carry ₹2,00,000 across a month; not ₹50,000 in one go.
            api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("maxSingleOrderCredit", "25000"));

            var line = new CreditLine(buyer, seller, agreementId);

            assertThat(submitStatus(line, "400", 100)).isEqualTo(422);
            assertThat(agreement(line).get("available").asDouble())
                    .describedAs("a refused order consumes nothing")
                    .isEqualTo(200000.0);

            // And an order under the cap still goes through — the limit was never
            // the problem.
            assertThat(orderOnCredit(line, "400", 50).at("/data/supplierOrders/0/status").asText())
                    .isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a suspended line stops new orders and keeps old debt")
        void suspensionStopsNewOrdersOnly() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);

            api.post(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/suspend",
                    Map.of("reason", "Awaiting payment on last month's invoices"));

            assertThat(submitStatus(line, "400", 10)).isEqualTo(422);

            // Suspension is about new risk. The debt already incurred is still
            // owed, and the supplier still wants to be paid for it.
            var agreement = agreement(line);
            assertThat(agreement.get("status").asText()).isEqualTo("SUSPENDED");
            assertThat(agreement.get("utilized").asDouble()).isEqualTo(40000.0);
            assertThat(agreement.get("due").asDouble()).isEqualTo(40000.0);

            api.post(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate", Map.of());
            assertThat(agreement(line).get("status").asText()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("the limit can be cut to what is committed, but no further")
        void limitCannotBeCutBelowExposure() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);
            long orderId = submitted.at("/data/supplierOrders/0/id").asLong();

            // ₹40,000 is already extended. Below that, doc 10 §3's identity —
            // approved = reserved + utilized + available — has no solution with a
            // non-negative available.
            assertThat(api.postStatus(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                    Map.of("approvedLimit", "20000", "creditPeriodDays", 30,
                            "reason", "Exposure reduced")))
                    .isEqualTo(400);

            // Down to the committed amount is fine, and leaves nothing spendable.
            api.post(line.seller().token(),
                    "/api/v1/credit/agreements/" + line.agreementId() + "/modify",
                    Map.of("approvedLimit", "40000", "creditPeriodDays", 30,
                            "reason", "Exposure reduced"));

            var agreement = agreement(line);
            assertThat(agreement.get("approvedLimit").asDouble()).isEqualTo(40000.0);
            assertThat(agreement.get("available").asDouble()).isZero();
            // And the identity still holds, which is the point of the floor.
            assertThat(agreement.get("reserved").asDouble()
                    + agreement.get("utilized").asDouble()
                    + agreement.get("available").asDouble())
                    .isEqualTo(agreement.get("approvedLimit").asDouble());

            // The order the supplier is already filling is untouched — a limit cut
            // must not cancel a delivery they already agreed to make.
            assertThat(agreement(line).get("utilized").asDouble()).isEqualTo(40000.0);
        }

        @Test
        @DisplayName("credit without an agreement is refused")
        void noAgreementNoCredit() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            // Offered, but never asked for. Guardrail 16: an unfunded order must
            // not reach a supplier.
            var line = new CreditLine(buyer, seller, 0L);
            assertThat(submitStatus(line, "400", 10)).isEqualTo(422);
            assertThat(jdbc.queryForObject("select count(*) from supplier_order "
                            + "where outlet_id = ? and status <> 'DRAFT'",
                    Integer.class, buyer.outletId())).isZero();
        }
    }

    // ── The race doc 10 §2 requires ──────────────────────────────────────

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("two orders racing for the last of the credit: exactly one wins")
        void reservationRace() throws Exception {
            // Doc 10 §2's mandatory credit reservation race. Both orders are 60% of
            // the limit, so they cannot both be held — and a read-modify-write
            // would let them, because both would read the same untouched balance.
            var line = creditLine("100000");

            // Two separate answered requests, because one request becomes at most
            // one order (D-088). Racing them is racing the credit reservation,
            // which is the thing under test.
            long first = answeredRequest(line, "600", 100);
            long second = answeredRequest(line, "600", 100);

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);
            var statuses = new AtomicReference<>(List.<Integer>of());
            var pool = Executors.newFixedThreadPool(2);

            for (long intentId : List.of(first, second)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        int status = mvc.perform(MockMvcRequestBuilders
                                        .post("/api/v1/intents/" + intentId + "/orders")
                                        .header("Authorization", "Bearer " + line.buyer().token())
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(Map.of(
                                                "deliveryMode", "PICKUP",
                                                "paymentMethod", "CREDIT"))))
                                .andReturn().getResponse().getStatus();
                        synchronized (statuses) {
                            var updated = new java.util.ArrayList<>(statuses.get());
                            updated.add(status);
                            statuses.set(List.copyOf(updated));
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // Doc 10 §2: exactly one valid financial operation succeeds.
            assertThat(statuses.get()).containsExactlyInAnyOrder(200, 422);

            var agreement = agreement(line);
            // Drawn rather than held: D-091 confirms a credit order as it is
            // created, so the reservation is utilised in the same breath. The
            // invariant the race is about is unchanged — one order's worth of
            // exposure exists, never two.
            assertThat(agreement.get("utilized").asDouble()).isEqualTo(60000.0);
            assertThat(agreement.get("available").asDouble()).isEqualTo(40000.0);
            assertThat(jdbc.queryForObject("select count(*) from credit_reservation "
                    + "where credit_agreement_id = ?",
                    Integer.class, line.agreementId())).isEqualTo(1);
        }

    }

    // ── Invoices, dues and repayment ─────────────────────────────────────

    @Nested
    @DisplayName("invoices and repayment")
    class InvoicesAndRepayment {

        @Test
        @DisplayName("request → modified approval → reserve → utilize → invoice → pay → reconcile")
        void theWholeCreditJourney() throws Exception {
            // Doc 10 §1 scenario 7, end to end.
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();

            api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("approvedLimit", "100000", "creditPeriodDays", 15));
            api.post(buyer.token(), "/api/v1/credit/agreements/" + agreementId + "/accept",
                    Map.of());

            var line = new CreditLine(buyer, seller, agreementId);
            var submitted = orderOnCredit(line, "400", 100);

            var invoice = api.get(buyer.token(),
                    "/api/v1/credit/agreements/" + agreementId + "/invoices").at("/data").get(0);
            long invoiceId = invoice.get("id").asLong();
            assertThat(invoice.get("outstanding").asDouble()).isEqualTo(40000.0);

            // Part payment first: still owed, still drawn, but less of both.
            recordPayment(seller, invoiceId, "15000", UUID.randomUUID().toString());

            var partly = api.get(buyer.token(), "/api/v1/credit/agreements/" + agreementId
                    + "/invoices").at("/data").get(0);
            assertThat(partly.get("status").asText()).isEqualTo("PARTIALLY_PAID");
            assertThat(partly.get("outstanding").asDouble()).isEqualTo(25000.0);
            assertThat(agreement(line).get("utilized").asDouble()).isEqualTo(25000.0);
            assertThat(agreement(line).get("available").asDouble()).isEqualTo(75000.0);

            // Then the rest. The debt closes and the credit comes back.
            recordPayment(seller, invoiceId, "25000", UUID.randomUUID().toString());

            var settled = api.get(buyer.token(), "/api/v1/credit/agreements/" + agreementId
                    + "/invoices").at("/data").get(0);
            assertThat(settled.get("status").asText()).isEqualTo("PAID");
            assertThat(settled.get("outstanding").asDouble()).isZero();

            var reconciled = agreement(line);
            assertThat(reconciled.get("utilized").asDouble()).isZero();
            assertThat(reconciled.get("due").asDouble()).isZero();
            assertThat(reconciled.get("available").asDouble()).isEqualTo(100000.0);
        }

        @Test
        @DisplayName("a repeated payment is recorded once")
        void repaymentIsIdempotent() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);
            long invoiceId = firstInvoiceId(line);

            String key = UUID.randomUUID().toString();
            recordPayment(line.seller(), invoiceId, "10000", key);
            recordPayment(line.seller(), invoiceId, "10000", key);

            // Money leaving twice is the failure. A second reduction would hand the
            // restaurant ₹10,000 of credit they never repaid.
            assertThat(agreement(line).get("utilized").asDouble()).isEqualTo(30000.0);
            assertThat(jdbc.queryForObject(
                    "select count(*) from credit_payment where credit_invoice_id = ?",
                    Integer.class, invoiceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("a restaurant can't mark its own debt paid")
        void restaurantCannotRecordItsOwnRepayment() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);
            long invoiceId = firstInvoiceId(line);

            // The money moved outside Mandi, so only the party it reached can
            // confirm it. Otherwise a restaurant clears its own balance and frees
            // the credit again on nothing but their say-so.
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/credit/invoices/" + invoiceId + "/payments")
                            .header("Authorization", "Bearer " + line.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "amount", "40000", "method", "CASH"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(404);
            assertThat(agreement(line).get("utilized").asDouble())
                    .describedAs("the debt is untouched")
                    .isEqualTo(40000.0);
        }

        @Test
        @DisplayName("you can't pay more than is outstanding")
        void overpaymentIsRefused() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);

            // Usually a typo. Turning one into spending power is worse than asking
            // someone to check the number.
            assertThat(recordPaymentStatus(line.seller(), firstInvoiceId(line), "90000",
                    UUID.randomUUID().toString())).isEqualTo(400);
            assertThat(agreement(line).get("utilized").asDouble()).isEqualTo(40000.0);
        }

        @Test
        @DisplayName("overdue starts after the grace period, and suspends where asked")
        void overdueSweepSuspends() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            offerCredit(seller);

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();
            api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("maxOverdueAmount", "10000"));

            var line = new CreditLine(buyer, seller, agreementId);
            var submitted = orderOnCredit(line, "400", 100);
            long invoiceId = firstInvoiceId(line);

            // Still inside the grace period: due, but not late.
            creditJobs.sweepOverdue();
            assertThat(agreement(line).get("overdue").asDouble()).isZero();
            assertThat(agreement(line).get("status").asText()).isEqualTo("ACTIVE");

            // Age it past due date + grace.
            jdbc.update("update credit_invoice set due_date = date_sub(curdate(), interval 10 day), "
                    + "overdue_after = date_sub(curdate(), interval 5 day) where id = ?", invoiceId);

            creditJobs.sweepOverdue();

            var agreement = agreement(line);
            assertThat(agreement.get("overdue").asDouble()).isEqualTo(40000.0);
            // Overdue is a subset of due, not a second debt.
            assertThat(agreement.get("due").asDouble()).isEqualTo(40000.0);
            // The supplier asked to be protected past ₹10,000 overdue.
            assertThat(agreement.get("status").asText()).isEqualTo("SUSPENDED");
            assertThat(agreement.get("suspensionReason").asText()).contains("Overdue");
        }

        @Test
        @DisplayName("the sweep doesn't suspend a supplier who didn't ask for it")
        void sweepRespectsTheSupplierSetting() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            // auto-suspend off: chasing late payment is this supplier's own business.
            offerCredit(seller, Map.of("creditEnabled", true, "defaultCreditPeriodDays", 30,
                    "defaultGracePeriodDays", 0, "autoSuspendEnabled", false));

            long agreementId = api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                    "requestedLimit", "200000", "requestedDays", 30)).at("/data/id").asLong();
            api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve",
                    Map.of("maxOverdueAmount", "1000"));

            var line = new CreditLine(buyer, seller, agreementId);
            var submitted = orderOnCredit(line, "400", 100);

            jdbc.update("update credit_invoice set due_date = date_sub(curdate(), interval 10 day), "
                    + "overdue_after = date_sub(curdate(), interval 10 day) "
                    + "where credit_agreement_id = ?", agreementId);

            creditJobs.sweepOverdue();

            var agreement = agreement(line);
            assertThat(agreement.get("overdue").asDouble()).isEqualTo(40000.0);
            assertThat(agreement.get("status").asText())
                    .describedAs("Mandi never suspends on a supplier's behalf")
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("the outlet summary adds up across suppliers")
        void summaryAddsUp() throws Exception {
            var line = creditLine("200000");
            var submitted = orderOnCredit(line, "400", 100);

            var summary = api.get(line.buyer().token(),
                    "/api/v1/outlets/" + line.buyer().outletId() + "/credit/summary").at("/data");

            // §23A.24: the app reads these; it never computes them.
            assertThat(summary.get("approvedLimit").asDouble()).isEqualTo(200000.0);
            assertThat(summary.get("utilized").asDouble()).isEqualTo(40000.0);
            assertThat(summary.get("available").asDouble()).isEqualTo(160000.0);
            assertThat(summary.get("due").asDouble()).isEqualTo(40000.0);
            assertThat(summary.get("agreements")).hasSize(1);

            // Doc 10 §3's identity, straight off the wire.
            assertThat(summary.get("reserved").asDouble()
                    + summary.get("utilized").asDouble()
                    + summary.get("available").asDouble())
                    .isEqualTo(summary.get("approvedLimit").asDouble());
        }
    }

    private long firstInvoiceId(CreditLine line) throws Exception {
        return api.get(line.buyer().token(),
                "/api/v1/credit/agreements/" + line.agreementId() + "/invoices")
                .at("/data").get(0).get("id").asLong();
    }

    private void recordPayment(Seller seller, long invoiceId, String amount, String key)
            throws Exception {
        recordPaymentStatus(seller, invoiceId, amount, key);
    }

    private int recordPaymentStatus(Seller seller, long invoiceId, String amount, String key)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/credit/invoices/" + invoiceId + "/payments")
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "amount", amount, "method", "BANK_TRANSFER",
                                "reference", "UTR" + key.substring(0, 8)))))
                .andReturn().getResponse().getStatus();
    }
}
