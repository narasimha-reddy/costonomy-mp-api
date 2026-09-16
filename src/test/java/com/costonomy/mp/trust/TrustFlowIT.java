package com.costonomy.mp.trust;

import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestCheckout;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receiving, disputes and ratings end to end. Doc 01 §22–24, doc 04 §15–17.
 *
 * <p>Three properties carry this suite. <b>Receiving adds to the order and never
 * rewrites it</b>: the accepted quantities stay as the supplier committed to them,
 * which is what every dispute is argued from. <b>A dispute does not change order
 * status</b> — doc 01 §22, and §23A.26 requires the app to say so. And <b>a rating
 * is one per order, published on write and removable by moderation</b>, with
 * hiding actually removing it from the average.
 */
@AutoConfigureMockMvc
class TrustFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;

    private ApiClient api;
    private TestCheckout checkout;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(paymentProvider, api);
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    /** An order delivered and waiting to be checked in, with its lines. */
    private record DeliveredOrder(Buyer buyer, Seller seller, long orderId, long itemId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

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

    private JsonNode supplierPost(Seller seller, String path) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    /**
     * An order taken all the way to DELIVERED.
     *
     * <p>Own delivery, so the supplier reports the movement themselves and the test
     * does not need a courier simulation to reach the state it is actually about.
     */
    private DeliveredOrder deliveredOrder(int quantity) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();

        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, seller.storeId());

        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        long skuId = api.post(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
        long offerId = jdbc.queryForObject(
                "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                Long.class, skuId);

        long procurementId = api.post(buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                Map.of("supplierOfferId", offerId, "quantity", quantity)).at("/data/id").asLong();
        api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                Map.of("acceptPriceChanges", false));

        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/procurements/" + procurementId + "/submit")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        JsonNode submitted = json.readTree(body);
        checkout.payAll(buyer.token(), submitted);

        long orderId = submitted.at("/data/supplierOrders/0/id").asLong();
        supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/accept");
        supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/preparing");
        supplierPost(seller, "/api/v1/supplier-orders/" + orderId + "/ready");

        long deliveryId = mvcPostDelivery(seller, orderId);
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
        api.post(seller.token(), "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());

        long itemId = jdbc.queryForObject(
                "select id from supplier_order_item where supplier_order_id = ?",
                Long.class, orderId);

        return new DeliveredOrder(buyer, seller, orderId, itemId);
    }

    private long mvcPostDelivery(Seller seller, long orderId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + orderId + "/delivery")
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data/id").asLong();
    }

    private JsonNode receive(DeliveredOrder order, Object items, String notes) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + order.orderId() + "/receive")
                        .header("Authorization", "Bearer " + order.buyer().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("items", items, "notes", notes == null ? "" : notes))))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int receiveStatus(DeliveredOrder order, Object items) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + order.orderId() + "/receive")
                        .header("Authorization", "Bearer " + order.buyer().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("items", items))))
                .andReturn().getResponse().getStatus();
    }

    private Map<String, Object> countedLine(long itemId, String received,
                                            String damaged, String missing) {
        return Map.of("supplierOrderItemId", itemId,
                "receivedQuantity", received,
                "damagedQuantity", damaged,
                "missingQuantity", missing);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }

    // ── Receiving ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("receiving")
    class Receiving {

        @Test
        @DisplayName("a clean delivery completes the order")
        void receivingInFullCompletes() throws Exception {
            var order = deliveredOrder(10);

            var received = receive(order,
                    List.of(countedLine(order.itemId(), "10", "0", "0")), null).at("/data");

            assertThat(received.get("hasDiscrepancy").asBoolean()).isFalse();
            assertThat(received.get("totalReceivedQuantity").asDouble()).isEqualTo(10.0);
            // DELIVERED → COMPLETED. The restaurant is the only party that can know
            // the goods are in.
            assertThat(orderStatus(order.orderId())).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("damaged and missing are recorded separately")
        void discrepanciesAreItemised() throws Exception {
            var order = deliveredOrder(10);

            // Doc 04 §15's own example.
            var received = receive(order,
                    List.of(countedLine(order.itemId(), "8", "1", "1")),
                    "One damaged bag").at("/data");

            assertThat(received.get("hasDiscrepancy").asBoolean()).isTrue();
            assertThat(received.get("totalDamagedQuantity").asDouble()).isEqualTo(1.0);
            assertThat(received.get("totalMissingQuantity").asDouble()).isEqualTo(1.0);
            assertThat(received.at("/items/0/receivedQuantity").asDouble()).isEqualTo(8.0);

            // Doc 03 §11: receiving does not rewrite the order. The commitment is
            // what a dispute is argued from, and what was paid for.
            var line = jdbc.queryForMap(
                    "select accepted_quantity, fulfilled_quantity from supplier_order_item "
                            + "where id = ?", order.itemId());
            assertThat((java.math.BigDecimal) line.get("accepted_quantity"))
                    .isEqualByComparingTo("10");
            assertThat((java.math.BigDecimal) line.get("fulfilled_quantity"))
                    .isEqualByComparingTo("8");
            // A discrepancy still completes the order — the goods are in, and what
            // is wrong with them is a dispute, not an incomplete delivery.
            assertThat(orderStatus(order.orderId())).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("numbers that don't add up are refused with the arithmetic")
        void quantitiesMustReconcile() throws Exception {
            var order = deliveredOrder(10);

            // 8 + 1 + 0 = 9, not 10. The usual cause is a typo, so the message says
            // what was expected rather than just refusing.
            assertThat(receiveStatus(order,
                    List.of(countedLine(order.itemId(), "8", "1", "0")))).isEqualTo(400);

            // Over-delivery too: the restaurant paid for ten, and quietly recording
            // twelve would put stock on the books nobody priced.
            assertThat(receiveStatus(order,
                    List.of(countedLine(order.itemId(), "12", "0", "0")))).isEqualTo(400);

            assertThat(orderStatus(order.orderId()))
                    .describedAs("a refused receiving completes nothing")
                    .isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("every line must be answered")
        void noBlindComplete() throws Exception {
            var order = deliveredOrder(10);

            // §23A.22. An unanswered line is ambiguous between "arrived fine" and
            // "nobody looked", and those are very different facts.
            assertThat(receiveStatus(order, List.of())).isEqualTo(400);
        }

        @Test
        @DisplayName("receiving twice records one delivery")
        void receivingIsIdempotent() throws Exception {
            var order = deliveredOrder(10);
            var line = List.of(countedLine(order.itemId(), "10", "0", "0"));

            long first = receive(order, line, null).at("/data/id").asLong();
            long second = receive(order, line, null).at("/data/id").asLong();

            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject(
                    "select count(*) from receiving where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("an order that hasn't arrived can't be received")
        void cannotReceiveBeforeDelivery() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller();
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long skuId = api.post(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "P-" + productId,
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "400", "gstRate", "0")).at("/data/id").asLong();
            long offerId = jdbc.queryForObject(
                    "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                    Long.class, skuId);
            long procurementId = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                    Map.of("supplierOfferId", offerId, "quantity", 5)).at("/data/id").asLong();
            api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                    Map.of("acceptPriceChanges", false));
            String body = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/procurements/" + procurementId + "/submit")
                            .header("Authorization", "Bearer " + buyer.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();
            long orderId = json.readTree(body).at("/data/supplierOrders/0/id").asLong();
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, orderId);

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + orderId + "/receive")
                            .header("Authorization", "Bearer " + buyer.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("items",
                                    List.of(countedLine(itemId, "5", "0", "0"))))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(409);
        }

        @Test
        @DisplayName("the supplier can see what was received")
        void supplierSeesTheReceiving() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "8", "1", "1")), null);

            // They are being measured by it, so they are entitled to read it.
            var seen = api.get(order.seller().token(),
                    "/api/v1/supplier-orders/" + order.orderId() + "/receiving").at("/data");
            assertThat(seen.get("hasDiscrepancy").asBoolean()).isTrue();
        }
    }

    // ── Disputes ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("disputes")
    class Disputes {

        private JsonNode raise(DeliveredOrder order, String category, String description)
                throws Exception {
            String body = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + order.orderId() + "/disputes")
                            .header("Authorization", "Bearer " + order.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "category", category,
                                    "description", description,
                                    "claimedAmount", "400.00",
                                    "items", List.of(Map.of(
                                            "supplierOrderItemId", order.itemId(),
                                            "disputedQuantity", "1",
                                            "reason", "Bag split")),
                                    "evidence", List.of(Map.of(
                                            "evidenceType", "IMAGE",
                                            "reference", "s3://evidence/photo.jpg",
                                            "caption", "Split bag"))))))
                    .andReturn().getResponse().getContentAsString();
            return json.readTree(body).at("/data");
        }

        @Test
        @DisplayName("raising a dispute leaves the order exactly where it was")
        void disputeDoesNotChangeTheOrder() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "9", "1", "0")), null);
            assertThat(orderStatus(order.orderId())).isEqualTo("COMPLETED");

            var dispute = raise(order, "DAMAGED", "One bag arrived split.");

            assertThat(dispute.get("status").asText()).isEqualTo("OPEN");
            assertThat(dispute.get("disputeNumber").asText()).startsWith("DSP-");
            // Doc 01 §22. And §23A.26: the response carries the order status so the
            // app can tell the restaurant it is unchanged.
            assertThat(orderStatus(order.orderId())).isEqualTo("COMPLETED");
            assertThat(dispute.get("supplierOrderStatus").asText()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("one order can carry several disputes")
        void severalDisputesPerOrder() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "8", "1", "1")), null);

            // A delivery can be both short and damaged. One dispute per order would
            // force the restaurant to pick which problem to report.
            raise(order, "DAMAGED", "One bag split.");
            raise(order, "SHORT_QUANTITY", "One bag never arrived.");

            var all = api.get(order.buyer().token(),
                    "/api/v1/supplier-orders/" + order.orderId() + "/disputes").at("/data");
            assertThat(all).hasSize(2);
        }

        @Test
        @DisplayName("the supplier answers, the restaurant closes")
        void theConversation() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "9", "1", "0")), null);
            long disputeId = raise(order, "DAMAGED", "One bag split.").get("id").asLong();

            var responded = api.post(order.seller().token(),
                    "/api/v1/disputes/" + disputeId + "/response",
                    Map.of("message", "Sorry — we'll replace it on your next order.",
                            "resolutionType", "REPLACEMENT",
                            "resolution", "Replacement on next delivery")).at("/data");

            assertThat(responded.get("status").asText()).isEqualTo("RESPONDED");
            assertThat(responded.get("messages")).hasSize(2);

            // A supplier proposing an answer does not close it — that would end a
            // conversation the other party has not agreed to.
            assertThat(responded.get("resolvedAt").isNull()).isTrue();

            var resolved = api.post(order.buyer().token(),
                    "/api/v1/disputes/" + disputeId + "/resolve",
                    Map.of("resolutionType", "REPLACEMENT",
                            "resolution", "Accepted the replacement",
                            "message", "That works, thanks.")).at("/data");

            assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
            assertThat(resolved.get("resolvedAt").isNull()).isFalse();
            // Still no change to the order, at any point.
            assertThat(orderStatus(order.orderId())).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("a closed dispute stays closed")
        void terminalIsTerminal() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);
            long disputeId = raise(order, "QUALITY", "Not fresh.").get("id").asLong();

            api.post(order.seller().token(), "/api/v1/disputes/" + disputeId + "/reject",
                    Map.of("resolutionType", "NO_ACTION", "resolution", "Delivered as ordered"));

            assertThat(api.postStatus(order.seller().token(),
                    "/api/v1/disputes/" + disputeId + "/response",
                    Map.of("message", "Actually, here's a credit."))).isEqualTo(409);
        }

        @Test
        @DisplayName("evidence and the thread come back with the dispute")
        void evidenceIsKept() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "9", "1", "0")), null);
            long disputeId = raise(order, "DAMAGED", "One bag split.").get("id").asLong();

            var seen = api.get(order.seller().token(), "/api/v1/disputes/" + disputeId).at("/data");
            assertThat(seen.get("evidence")).hasSize(1);
            assertThat(seen.at("/evidence/0/reference").asText())
                    .isEqualTo("s3://evidence/photo.jpg");
            assertThat(seen.get("items")).hasSize(1);
        }

        @Test
        @DisplayName("an unrelated restaurant sees nothing")
        void strangersSeeNothing() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);
            long disputeId = raise(order, "QUALITY", "Not fresh.").get("id").asLong();

            var stranger = newBuyer();
            assertThat(api.getStatus(stranger.token(), "/api/v1/disputes/" + disputeId))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a supplier can't raise a dispute against themselves")
        void supplierCannotRaise() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);

            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + order.orderId() + "/disputes")
                            .header("Authorization", "Bearer " + order.seller().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "category", "QUALITY", "description", "All fine really"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(404);
        }
    }

    // ── Ratings ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ratings")
    class Ratings {

        private JsonNode rate(DeliveredOrder order, int overall, String comment)
                throws Exception {
            return api.post(order.buyer().token(),
                    "/api/v1/supplier-orders/" + order.orderId() + "/rating",
                    Map.of("overall", overall, "productQuality", overall,
                            "quantityAccuracy", overall, "packaging", overall,
                            "delivery", overall, "comment", comment)).at("/data");
        }

        @Test
        @DisplayName("a completed order can be rated once")
        void rateOnce() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);

            long first = rate(order, 5, "Spot on").get("id").asLong();
            // §23A.23: prevent duplicate submission. A double tap is how it happens,
            // so the second returns the original rather than erroring.
            long second = rate(order, 1, "Changed my mind").get("id").asLong();

            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject(
                    "select count(*) from rating where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("an order can't be rated before it is received")
        void ratingNeedsCompletion() throws Exception {
            var order = deliveredOrder(10);

            // "After completion" (§23A.23). Rating an order still in flight rates
            // something that has not finished happening.
            assertThat(api.postStatus(order.buyer().token(),
                    "/api/v1/supplier-orders/" + order.orderId() + "/rating",
                    Map.of("overall", 5))).isEqualTo(409);
        }

        @Test
        @DisplayName("a store with no ratings has no average, not a middling one")
        void absentRatingIsAbsent() throws Exception {
            var seller = newSeller();
            var buyer = newBuyer();

            var summary = api.get(buyer.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/ratings").at("/data");

            // Doc 07 §4. A default of three would make an unrated supplier
            // indistinguishable from a mediocre one.
            assertThat(summary.get("ratingCount").asInt()).isZero();
            assertThat(summary.get("averageOverall").isNull()).isTrue();
        }

        @Test
        @DisplayName("hiding a rating removes it from the average")
        void moderationRemovesFromTheAverage() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);
            long ratingId = rate(order, 1, "Terrible, and also defamatory").get("id").asLong();

            var before = api.get(order.buyer().token(),
                    "/api/v1/supplier-stores/" + order.seller().storeId() + "/ratings").at("/data");
            assertThat(before.get("ratingCount").asInt()).isEqualTo(1);
            assertThat(before.get("averageOverall").asDouble()).isEqualTo(1.0);

            api.post(moderator(), "/api/v1/internal/ratings/" + ratingId + "/moderate",
                    Map.of("hide", true, "reason", "Defamatory content"));

            var after = api.get(order.buyer().token(),
                    "/api/v1/supplier-stores/" + order.seller().storeId() + "/ratings").at("/data");
            // This is what makes moderation mean something rather than being
            // cosmetic — the rating leaves the average and the ranking.
            assertThat(after.get("ratingCount").asInt()).isZero();
            assertThat(after.get("averageOverall").isNull()).isTrue();
        }

        @Test
        @DisplayName("only a moderator can hide a rating")
        void moderationIsProtected() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "10", "0", "0")), null);
            long ratingId = rate(order, 1, "Not great").get("id").asLong();

            // The supplier the rating is about is exactly who must not be able to
            // remove it.
            for (String token : new String[] {order.seller().token(), order.buyer().token()}) {
                assertThat(api.postStatus(token,
                        "/api/v1/internal/ratings/" + ratingId + "/moderate",
                        Map.of("hide", true, "reason", "I don't like it"))).isEqualTo(403);
            }

            assertThat(jdbc.queryForObject(
                    "select moderation_status from rating where id = ?",
                    String.class, ratingId)).isEqualTo("PUBLISHED");
        }

        private String moderator() throws Exception {
            String phone = ApiClient.freshPhone();
            String token = api.login(phone);
            Long userId = jdbc.queryForObject(
                    "select id from users where phone = ?", Long.class, "+91" + phone);
            jdbc.update("""
                    insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                           granted_at, created_at, updated_at, version)
                    select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                      from role r where r.code = 'OPS_MODERATION'
                    """, userId);
            return token;
        }
    }

    // ── What this unlocks for ranking ────────────────────────────────────

    @Nested
    @DisplayName("supplier performance")
    class Performance {

        @Test
        @DisplayName("receiving and rating make fill rate and rating real")
        void signalsBecomeMeasurable() throws Exception {
            var order = deliveredOrder(10);
            receive(order, List.of(countedLine(order.itemId(), "8", "1", "1")), null);

            api.post(order.buyer().token(),
                    "/api/v1/supplier-orders/" + order.orderId() + "/rating",
                    Map.of("overall", 4));

            // D-019 left fill rate and rating empty because the data did not exist.
            // It does now, and BestValueScorer needed no change — which is what
            // D-014's weight redistribution was for.
            var performance = jdbc.queryForMap("""
                    select (select sum(i.fulfilled_quantity) / sum(i.accepted_quantity)
                              from supplier_order_item i
                              join supplier_order o on o.id = i.supplier_order_id
                             where o.supplier_store_id = ?
                               and i.fulfilled_quantity is not null)            as fill_rate,
                           (select avg(overall_rating) from rating
                             where supplier_store_id = ?
                               and moderation_status = 'PUBLISHED')             as avg_rating
                    """, order.seller().storeId(), order.seller().storeId());

            assertThat(((java.math.BigDecimal) performance.get("fill_rate")).doubleValue())
                    .isEqualTo(0.8);
            assertThat(((java.math.BigDecimal) performance.get("avg_rating")).doubleValue())
                    .isEqualTo(4.0);
        }
    }
}
