package com.costonomy.mp.intent;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request → answer → order loop, end to end. D-088.
 *
 * <p>The assertions to care about are the ones about <b>what has not happened
 * yet</b>: that an open request has produced no order, no payment and no money of
 * any kind. That property is the whole point of the architecture, and it is the
 * one a later refactor is most likely to break while every happy-path test keeps
 * passing.
 */
@AutoConfigureMockMvc
class IntentFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private record Buyer(String token, long outletId, long userId) {
    }

    private record Seller(String token, long storeId, long userId) {
    }

    /** A request that has been sent but not answered. */
    private record OpenRequest(Buyer buyer, Seller seller, long intentId, long itemId,
                               long skuId) {
    }

    // ── The loop ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a request answered in full")
    class FullAnswer {

        @Test
        @DisplayName("becomes an order the supplier has already agreed to")
        void wholeLoop() throws Exception {
            var open = sendRequest(20);

            // The supplier finds it waiting.
            var carousel = api.get(open.seller().token(),
                    "/api/v1/supplier-stores/" + open.seller().storeId() + "/intents/carousel");
            assertThat(carousel.at("/data").size()).isEqualTo(1);
            assertThat(carousel.at("/data/0/status").asText()).isEqualTo("OPEN");
            assertThat(carousel.at("/data/0/fulfilment").asText()).isEqualTo("AWAITING");

            // Nothing financial exists yet. This is the assertion the architecture
            // is for: the supplier has seen the request, and no money has moved.
            assertThat(ordersFor(open.buyer().outletId())).isZero();
            assertThat(paymentsFor(open.buyer().outletId())).isZero();

            answer(open, 20);

            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(seen.at("/data/status").asText()).isEqualTo("RESPONSES_RECEIVED");
            assertThat(seen.at("/data/fulfilment").asText()).isEqualTo("FULFILLED");
            assertThat(seen.at("/data/withinOrderWindow").asBoolean()).isTrue();
            // 20 × 410 = 8200, plus 5% = 8610.
            assertThat(seen.at("/data/acceptance/offeredTotal").asDouble()).isEqualTo(8610.00);
            // Still nothing financial: an answer is a quote, not a transaction.
            assertThat(ordersFor(open.buyer().outletId())).isZero();

            var preview = api.post(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/orders/preview", Map.of());
            assertThat(preview.at("/data/creatable").asBoolean()).isTrue();
            assertThat(preview.at("/data/total").asDouble()).isEqualTo(8610.00);
            assertThat(preview.at("/data/blockers").size()).isZero();

            var created = createOrder(open.buyer().token(), open.intentId(), Map.of());
            long orderId = created.at("/data/supplierOrderId").asLong();
            assertThat(created.at("/data/totalAmount").asDouble()).isEqualTo(8610.00);

            // Guardrail 16 still holds: DRAFT until the money is secured.
            assertThat(statusOf(orderId)).isEqualTo("DRAFT");

            pay(open.buyer().token(), created);

            // And now CONFIRMED rather than PENDING_ACCEPTANCE. The supplier
            // already committed; asking them to accept again would be asking
            // twice, and the acceptance countdown would expire an agreed order.
            assertThat(statusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject(
                    "select acceptance_deadline from supplier_order where id = ?",
                    java.sql.Timestamp.class, orderId)).isNull();

            // The order arrives already accepted, line by line.
            var line = jdbc.queryForMap(
                    "select requested_quantity, accepted_quantity, status "
                            + "from supplier_order_item where supplier_order_id = ?", orderId);
            assertThat(((java.math.BigDecimal) line.get("accepted_quantity"))
                    .compareTo(new java.math.BigDecimal("20"))).isZero();
            assertThat(((java.math.BigDecimal) line.get("requested_quantity"))
                    .compareTo(new java.math.BigDecimal("20"))).isZero();
            assertThat(line.get("status")).isEqualTo("ACCEPTED");

            // No cart behind it, and the origin recorded where it belongs.
            assertThat(jdbc.queryForObject(
                    "select procurement_id from supplier_order where id = ?",
                    Long.class, orderId)).isNull();
            assertThat(jdbc.queryForObject(
                    "select count(*) from intent_order_link where intent_id = ? "
                            + "and supplier_order_id = ?",
                    Integer.class, open.intentId(), orderId)).isEqualTo(1);

            var finished = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(finished.at("/data/status").asText()).isEqualTo("ORDERED");
            assertThat(finished.at("/data/supplierOrderId").asLong()).isEqualTo(orderId);
        }

        @Test
        @DisplayName("can only become one order, whatever the client retries")
        void orderedOnce() throws Exception {
            var open = sendRequest(4);
            answer(open, 4);

            var first = createOrder(open.buyer().token(), open.intentId(), Map.of());
            long orderId = first.at("/data/supplierOrderId").asLong();

            // A fresh key, as a client that crashed and retried would send. The
            // idempotency header cannot help here; uk_intent_order_link_intent and
            // the early return are what make this safe.
            var second = createOrder(open.buyer().token(), open.intentId(), Map.of());
            assertThat(second.at("/data/supplierOrderId").asLong()).isEqualTo(orderId);

            assertThat(ordersFor(open.buyer().outletId())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a request answered short")
    class ShortAnswer {

        @Test
        @DisplayName("is partially fulfilled, and the order is for what was offered")
        void partial() throws Exception {
            var open = sendRequest(20);
            answer(open, 8);

            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(seen.at("/data/fulfilment").asText()).isEqualTo("PARTIALLY_FULFILLED");
            assertThat(seen.at("/data/items/0/requestedQuantity").asDouble()).isEqualTo(20);
            assertThat(seen.at("/data/items/0/offeredQuantity").asDouble()).isEqualTo(8);

            // 8 × 410 = 3280, plus 5% = 3444. Note what is *not* here: no order
            // exists for 8610 that then gets reduced. The restaurant is never
            // charged for, or shown, a total the supplier did not agree to.
            var created = createOrder(open.buyer().token(), open.intentId(), Map.of());
            assertThat(created.at("/data/totalAmount").asDouble()).isEqualTo(3444.00);
        }

        @Test
        @DisplayName("declining every line is NOT_FULFILLED and produces no order")
        void declinedOutright() throws Exception {
            var open = sendRequest(20);
            answer(open, 0);

            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(seen.at("/data/fulfilment").asText()).isEqualTo("NOT_FULFILLED");

            var preview = api.post(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/orders/preview", Map.of());
            assertThat(preview.at("/data/creatable").asBoolean()).isFalse();

            assertThat(createOrderStatus(open.buyer().token(), open.intentId(), Map.of()))
                    .isEqualTo(400);
            assertThat(ordersFor(open.buyer().outletId())).isZero();
        }

        @Test
        @DisplayName("the restaurant may take less than was offered, never more")
        void reduceButNotRaise() throws Exception {
            var open = sendRequest(20);
            answer(open, 10);

            // Less: 6 × 410 = 2460, plus 5% = 2583.
            var lessBody = Map.of("lines",
                    List.of(Map.of("intentItemId", open.itemId(), "quantity", 6)));
            var preview = api.post(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/orders/preview", lessBody);
            assertThat(preview.at("/data/total").asDouble()).isEqualTo(2583.00);

            // More than offered is an error, not a silent clamp: the client showed
            // somebody 12, and quietly ordering 10 is how a kitchen ends up short
            // without being told.
            var moreBody = Map.of("lines",
                    List.of(Map.of("intentItemId", open.itemId(), "quantity", 12)));
            assertThat(createOrderStatus(open.buyer().token(), open.intentId(), moreBody))
                    .isEqualTo(422);
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("must cover every line — an omission is not a refusal")
        void everyLineAnswered() throws Exception {
            var open = sendRequestWithTwoLines();

            // One of the two lines answered. Reading the silence as zero would turn
            // a dropped row into a refusal the supplier never made.
            int status = respondStatus(open.seller().token(), open.intentId(),
                    Map.of("lines", List.of(
                            Map.of("intentItemId", open.itemId(), "offeredQuantity", 5))));
            // 400: an incomplete answer is a malformed request, not a rejected
            // commercial one. ErrorCode.VALIDATION_ERROR -> BAD_REQUEST.
            assertThat(status).isEqualTo(400);

            // And nothing was written: the request is still waiting.
            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(seen.at("/data/status").asText()).isEqualTo("OPEN");
            assertThat(seen.at("/data/acceptance").isNull()).isTrue();
        }

        @Test
        @DisplayName("cannot offer more than was asked for")
        void cannotOverOffer() throws Exception {
            var open = sendRequest(5);
            int status = respondStatus(open.seller().token(), open.intentId(),
                    Map.of("lines", List.of(
                            Map.of("intentItemId", open.itemId(), "offeredQuantity", 9))));
            assertThat(status).isEqualTo(422);
        }

        @Test
        @DisplayName("is refused once the request has been answered")
        void onlyOnce() throws Exception {
            var open = sendRequest(5);
            answer(open, 5);

            // A *different* answer under a fresh key. The early return covers a
            // genuine retry; this asserts the supplier cannot revise a commitment
            // the restaurant may already be acting on.
            var second = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(second.at("/data/acceptance/offeredTotal").asDouble())
                    .isEqualTo(2152.50);

            respondStatus(open.seller().token(), open.intentId(),
                    Map.of("lines", List.of(
                            Map.of("intentItemId", open.itemId(), "offeredQuantity", 1))));

            var after = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(after.at("/data/acceptance/offeredTotal").asDouble())
                    .isEqualTo(2152.50);
            assertThat(jdbc.queryForObject(
                    "select count(*) from intent_acceptance where intent_id = ?",
                    Integer.class, open.intentId())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the basket")
    class Basket {

        @Test
        @DisplayName("splits by supplier, so one request is always one supplier")
        void oneDraftPerStore() throws Exception {
            var buyer = newBuyer();
            var first = newSeller("Metro");
            var second = newSeller("Nandini");

            addItem(buyer, listSku(first, "paneer", "410"), 3);
            addItem(buyer, listSku(second, "rice", "120"), 4);

            var drafts = api.get(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/intent-drafts");
            assertThat(drafts.at("/data").size()).isEqualTo(2);
            assertThat(drafts.at("/data/0/supplierStoreId").asLong())
                    .isNotEqualTo(drafts.at("/data/1/supplierStoreId").asLong());
        }

        @Test
        @DisplayName("is never visible to the supplier before it is sent")
        void draftsAreNotVisible() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            addItem(buyer, listSku(seller, "paneer", "410"), 3);

            assertThat(api.get(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/intents")
                    .at("/data").size()).isZero();
            assertThat(api.get(seller.token(),
                    "/api/v1/supplier-stores/" + seller.storeId() + "/intents/carousel")
                    .at("/data").size()).isZero();
        }

        @Test
        @DisplayName("counts a re-added pack up rather than twice")
        void sameSkuAccumulates() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long skuId = listSku(seller, "paneer", "410");

            addItem(buyer, skuId, 3);
            var after = addItem(buyer, skuId, 2);

            assertThat(after.at("/data/items").size()).isEqualTo(1);
            assertThat(after.at("/data/items/0/requestedQuantity").asDouble()).isEqualTo(5);
        }

        @Test
        @DisplayName("drops the draft when its last line goes")
        void emptyingRemovesIt() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long skuId = listSku(seller, "paneer", "410");

            long itemId = addItem(buyer, skuId, 3).at("/data/items/0/id").asLong();
            api.patchStatus(buyer.token(), "/api/v1/intent-items/" + itemId,
                    Map.of("quantity", 0));

            assertThat(api.get(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/intent-drafts")
                    .at("/data").size()).isZero();
        }

        @Test
        @DisplayName("is frozen once sent")
        void sentIsFrozen() throws Exception {
            var open = sendRequest(5);
            // 409: the request is in a state that does not allow the change.
            // ErrorCode.INVALID_STATE_TRANSITION -> CONFLICT.
            assertThat(api.patchStatus(open.buyer().token(),
                    "/api/v1/intent-items/" + open.itemId(), Map.of("quantity", 9)))
                    .isEqualTo(409);
        }
    }

    @Nested
    @DisplayName("the order window")
    class Window {

        @Test
        @DisplayName("closes, and a lapsed answer cannot be ordered from")
        void lapsed() throws Exception {
            var open = sendRequest(5);
            answer(open, 5);

            // Wound back rather than waited out. The deadline is the stored
            // instant, so moving it is the honest way to test the boundary — and
            // it exercises exactly the column the endpoint reads.
            jdbc.update("update intent set order_creation_deadline = date_sub(now(6), "
                    + "interval 1 minute) where id = ?", open.intentId());

            var preview = api.post(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/orders/preview", Map.of());
            assertThat(preview.at("/data/creatable").asBoolean()).isFalse();
            assertThat(preview.at("/data/blockers/0/code").asText())
                    .isEqualTo("SUPPLIER_ORDER_EXPIRED");

            assertThat(createOrderStatus(open.buyer().token(), open.intentId(), Map.of()))
                    .isEqualTo(409);
            assertThat(ordersFor(open.buyer().outletId())).isZero();
        }
    }

    @Nested
    @DisplayName("repeating a request")
    class Cloning {

        @Test
        @DisplayName("copies it into a fresh draft rather than reopening it")
        void clonesToDraft() throws Exception {
            var open = sendRequest(7);
            answer(open, 7);
            createOrder(open.buyer().token(), open.intentId(), Map.of());

            var clone = api.post(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/clone", Map.of());
            assertThat(clone.at("/data/status").asText()).isEqualTo("DRAFT");
            assertThat(clone.at("/data/clonedFromId").asLong()).isEqualTo(open.intentId());
            assertThat(clone.at("/data/items/0/requestedQuantity").asDouble()).isEqualTo(7);
            assertThat(clone.at("/data/id").asLong()).isNotEqualTo(open.intentId());

            // The original is untouched — it records one conversation, and that
            // conversation already happened.
            assertThat(api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId())
                    .at("/data/status").asText()).isEqualTo("ORDERED");
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private Buyer newBuyer() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId, userId);
    }

    private Seller newSeller(String name) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd", "displayName", name,
                "firstStore", Map.of("name", name + " store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");

        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());

        return new Seller(token, created.get("stores").get(0).get("id").asLong(), userId);
    }

    /** List one pack at one price, and return its SKU id. */
    private long listSku(Seller seller, String label, String price) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, label);
        return api.post(seller.token(),
                "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", label + "-" + productId,
                        "name", label, "packSize", 1, "packUnit", "KG",
                        "sellingPrice", price, "gstRate", "5")).at("/data/id").asLong();
    }

    private JsonNode addItem(Buyer buyer, long skuId, int quantity) throws Exception {
        return api.post(buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/intent-items",
                Map.of("supplierSkuId", skuId, "quantity", quantity));
    }

    /** A one-line request for paneer at ₹410, sent and awaiting an answer. */
    private OpenRequest sendRequest(int quantity) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("Metro");
        long skuId = listSku(seller, "paneer", "410");

        var draft = addItem(buyer, skuId, quantity);
        long intentId = draft.at("/data/id").asLong();
        long itemId = draft.at("/data/items/0/id").asLong();

        api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
        return new OpenRequest(buyer, seller, intentId, itemId, skuId);
    }

    /** The same, with a second line, for the "answer every line" case. */
    private OpenRequest sendRequestWithTwoLines() throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("Metro");

        addItem(buyer, listSku(seller, "paneer", "410"), 5);
        var draft = addItem(buyer, listSku(seller, "rice", "120"), 5);

        long intentId = draft.at("/data/id").asLong();
        long itemId = draft.at("/data/items/0/id").asLong();
        api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
        return new OpenRequest(buyer, seller, intentId, itemId, 0);
    }

    /** Answer every line of a one-line request with {@code offered}. */
    private void answer(OpenRequest open, int offered) throws Exception {
        var response = respond(open.seller().token(), open.intentId(),
                Map.of("lines", List.of(
                        Map.of("intentItemId", open.itemId(), "offeredQuantity", offered))));
        assertThat(response.at("/data/status").asText())
                .as("answer was rejected: %s", response.toString())
                .isEqualTo("RESPONSES_RECEIVED");
    }

    // ── Calls that need an idempotency key ───────────────────────────────

    private JsonNode respond(String token, long intentId, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/respond")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }

    private int respondStatus(String token, long intentId, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/respond")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode createOrder(String token, long intentId, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }

    private int createOrderStatus(String token, long intentId, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    /** Complete checkout for the one payment an intent order produces. */
    private void pay(String token, JsonNode createResponse) throws Exception {
        var payment = createResponse.at("/data/payment");
        assertThat(payment.isNull()).as("no payment intent was returned").isFalse();
        var completed = paymentProvider.completeCheckout(
                payment.get("providerOrderId").asText());
        api.post(token, "/api/v1/payments/" + payment.get("paymentId").asLong() + "/confirm",
                Map.of("providerPaymentId", completed.providerPaymentId()));
    }

    // ── Observations ─────────────────────────────────────────────────────

    private int ordersFor(long outletId) {
        return jdbc.queryForObject(
                "select count(*) from supplier_order where outlet_id = ?", Integer.class, outletId);
    }

    private int paymentsFor(long outletId) {
        return jdbc.queryForObject("""
                select count(*) from payment p
                  join supplier_order o on o.id = p.supplier_order_id
                 where o.outlet_id = ?
                """, Integer.class, outletId);
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }
}
