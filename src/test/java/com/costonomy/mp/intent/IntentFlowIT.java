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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    @Autowired private com.costonomy.mp.intent.service.IntentExpiryJob expiryJob;

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
            // And with the checkout it may never have seen (D-102): returned
            // without it, the app read "nothing to pay" and the order stayed
            // unpayable. The same provider order — the retry creates nothing.
            assertThat(second.at("/data/payment/providerOrderId").asText())
                    .isNotBlank()
                    .isEqualTo(first.at("/data/payment/providerOrderId").asText());

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

        /**
         * The figure a supplier watches while deciding.
         *
         * <p>Worth a test of its own because the app is not allowed to compute
         * it: if this endpoint is wrong, the supplier accepts against a number
         * nobody checked.
         */
        @Test
        @DisplayName("prices a reply before it is sent, and clamps rather than refusing")
        void previewFollowsTheQuantity() throws Exception {
            var open = sendRequest(3);

            // Paneer at 410: 3 -> 1291.50, 2 -> 861.00, 0 -> nothing.
            assertThat(previewTotal(open, 3)).isEqualTo(1291.50);
            assertThat(previewTotal(open, 2)).isEqualTo(861.00);
            assertThat(previewTotal(open, 0)).isEqualTo(0.00);

            // Above what was asked, the preview clamps instead of erroring —
            // a supplier dragging a stepper needs a number back, and `respond`
            // is where the same input is properly refused.
            assertThat(previewTotal(open, 9)).isEqualTo(1291.50);
            assertThat(respondStatus(open.seller().token(), open.intentId(),
                    Map.of("lines", List.of(Map.of(
                            "intentItemId", open.itemId(), "offeredQuantity", 9)))))
                    .isEqualTo(422);
        }

        private double previewTotal(OpenRequest open, int offered) throws Exception {
            return api.post(open.seller().token(),
                    "/api/v1/intents/" + open.intentId() + "/respond/preview",
                    Map.of("lines", List.of(Map.of(
                            "intentItemId", open.itemId(), "offeredQuantity", offered))))
                    .at("/data/offeredTotal").asDouble();
        }

        /**
         * The supplier committing to a list that moved under them.
         *
         * <p>A restaurant may change quantities while a request is open, so the
         * list on a supplier's screen can go stale. Accepting 2 KG of something
         * cut to 1 an instant earlier commits stock nobody asked for, and the
         * supplier finds out at delivery.
         */
        @Test
        @DisplayName("an acceptance against a stale revision is refused")
        void staleAcceptanceRefused() throws Exception {
            var open = sendRequest(3);
            long seen = api.get(open.seller().token(), "/api/v1/intents/" + open.intentId())
                    .at("/data/revision").asLong();

            // The restaurant changes its mind while the supplier is reading.
            api.patchStatus(open.buyer().token(), "/api/v1/intent-items/" + open.itemId(),
                    Map.of("quantity", 5));

            assertThat(respondStatus(open.seller().token(), open.intentId(),
                    Map.of("expectedRevision", seen,
                            "lines", List.of(Map.of(
                                    "intentItemId", open.itemId(), "offeredQuantity", 3)))))
                    .isEqualTo(409);

            // And nothing was written: the request is still waiting.
            assertThat(api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId())
                    .at("/data/status").asText()).isEqualTo("OPEN");
        }

        /**
         * The revision an edit hands back has to be usable straight away.
         *
         * <p>The forced version bump lands at commit, so the response is built
         * from a stale number unless it is corrected — and a restaurant's own
         * edit would then hand its supplier a revision that is refused on sight.
         * Asserting the property rather than the integer, because the integer is
         * an implementation detail and the usability is the requirement.
         */
        @Test
        @DisplayName("the revision an edit returns is accepted immediately")
        void editReturnsAUsableRevision() throws Exception {
            var open = sendRequest(3);

            long afterEdit = patchQuantity(open, 4).at("/data/revision").asLong();

            var response = respond(open.seller().token(), open.intentId(),
                    Map.of("expectedRevision", afterEdit,
                            "lines", List.of(Map.of(
                                    "intentItemId", open.itemId(), "offeredQuantity", 4))));
            assertThat(response.at("/data/status").asText())
                    .as("revision from the edit was rejected: %s", response)
                    .isEqualTo("RESPONSES_RECEIVED");
        }

        private com.fasterxml.jackson.databind.JsonNode patchQuantity(
                OpenRequest open, int quantity) throws Exception {
            return json.readTree(mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/intent-items/" + open.itemId())
                            .header("Authorization", "Bearer " + open.buyer().token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("quantity", quantity))))
                    .andReturn().getResponse().getContentAsString());
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
            assertThat(drafts.at("/data/requests").size()).isEqualTo(2);
            assertThat(drafts.at("/data/supplierCount").asInt()).isEqualTo(2);
            assertThat(drafts.at("/data/requests/0/supplierStoreId").asLong())
                    .isNotEqualTo(drafts.at("/data/requests/1/supplierStoreId").asLong());

            // The basket's estimate is the sum of the cards', and it is the
            // server that adds it up — 3 × 410 and 4 × 120, each plus 5%.
            assertThat(drafts.at("/data/agreedTotal").asDouble()).isEqualTo(1795.50);
            assertThat(drafts.at("/data/pricedComplete").asBoolean()).isTrue();
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
            // Priced from the live offer: 5 × 410 = 2050, plus 5% = 2152.50.
            assertThat(after.at("/data/items/0/agreedLineTotal").asDouble())
                    .isEqualTo(2152.50);
            assertThat(after.at("/data/agreedTotal").asDouble()).isEqualTo(2152.50);
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
                    .at("/data/requests").size()).isZero();
        }

        @Test
        @DisplayName("a request is immediate unless the buyer names a day, and the supplier sees which (D-140)")
        void immediateOrScheduled() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long paneer = listSku(seller, "paneer", "410");
            long intentId = addItem(buyer, paneer, 2).at("/data/id").asLong();

            var sent = api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
            assertThat(sent.at("/data/status").asText()).isEqualTo("OPEN");
            assertThat(sent.at("/data/preferredDeliveryDate").isNull()).isTrue();

            var other = newBuyer();
            long second = addItem(other, paneer, 1).at("/data/id").asLong();
            String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
            var scheduled = api.post(other.token(), "/api/v1/intents/" + second + "/send",
                    Map.of("preferredDeliveryDate", day));
            assertThat(scheduled.at("/data/preferredDeliveryDate").asText()).isEqualTo(day);

            var seen = api.get(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/intents");
            var days = new java.util.ArrayList<String>();
            seen.at("/data").forEach(i -> days.add(i.at("/preferredDeliveryDate").asText("immediate")));
            assertThat(days).contains(day, "immediate");
        }

        @Test
        @DisplayName("sending the whole basket applies one day to every request")
        void basketDayAppliesToEveryRequest() throws Exception {
            var buyer = newBuyer();
            var first = newSeller("Metro");
            var second = newSeller("Nandini");
            addItem(buyer, listSku(first, "paneer", "410"), 1);
            addItem(buyer, listSku(second, "rice", "120"), 1);
            String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(2).toString();

            var response = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/intent-drafts/send",
                    Map.of("acceptPriceChanges", true, "preferredDeliveryDate", day));

            assertThat(response.at("/data/sent").size()).isEqualTo(2);
            response.at("/data/sent").forEach(i ->
                    assertThat(i.at("/preferredDeliveryDate").asText()).isEqualTo(day));
        }

        @Test
        @DisplayName("refuses a day in the past or more than a month away, and sends nothing")
        void refusesASillyDay() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long intentId = addItem(buyer, listSku(seller, "paneer", "410"), 1).at("/data/id").asLong();
            var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));

            for (var day : List.of(today.minusDays(1), today.plusDays(31))) {
                var refused = api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send",
                        Map.of("preferredDeliveryDate", day.toString()));
                assertThat(refused.at("/error/code").asText()).as(day.toString()).isEqualTo("VALIDATION_ERROR");
            }
            assertThat(jdbc.queryForObject("select status from intent where id = ?", String.class, intentId))
                    .isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("carries the price it was sent at, before any reply")
        void sentCarriesItsPrice() throws Exception {
            var open = sendRequest(3);
            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());

            // No acceptance yet, and still a real figure: this is what the
            // supplier will confirm, which is why the basket can show it
            // without hedging.
            assertThat(seen.at("/data/acceptance").isNull()).isTrue();
            assertThat(seen.at("/data/items/0/agreedUnitPrice").asDouble()).isEqualTo(410.00);
            assertThat(seen.at("/data/agreedTotal").asDouble()).isEqualTo(1291.50);
        }

        @Test
        @DisplayName("takes a new quantity while it is still open")
        void quantityChangesWhileOpen() throws Exception {
            var open = sendRequest(5);

            // Nothing is committed while a request is open — no answer, no held
            // stock, no price — so a kitchen may still correct what it asked for.
            assertThat(api.patchStatus(open.buyer().token(),
                    "/api/v1/intent-items/" + open.itemId(), Map.of("quantity", 9)))
                    .isEqualTo(200);

            var seen = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(seen.at("/data/items/0/requestedQuantity").asDouble()).isEqualTo(9.0);
            assertThat(seen.at("/data/status").asText()).isEqualTo("OPEN");
            assertThat(seen.at("/data/quantityEditable").asBoolean()).isTrue();
            // Still not editable in shape: the two flags differ by a state.
            assertThat(seen.at("/data/editable").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("keeps the supplier's original deadline when a quantity changes")
        void editDoesNotExtendTheSupplierClock() throws Exception {
            var open = sendRequest(5);
            var before = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId())
                    .at("/data/responseDeadline").asText();

            api.patchStatus(open.buyer().token(),
                    "/api/v1/intent-items/" + open.itemId(), Map.of("quantity", 9));

            // Extending it on edit would let a restaurant hold a supplier
            // indefinitely by editing in a loop.
            assertThat(api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId())
                    .at("/data/responseDeadline").asText())
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("cannot be emptied by stepping a line to zero")
        void sentRequestCannotBeEmptied() throws Exception {
            var open = sendRequest(5);
            // Zero means "remove the line" in a basket. On a live request that
            // would leave a supplier holding an empty list with a clock still
            // running — withdrawing is what says so properly.
            // ErrorCode.VALIDATION_ERROR -> BAD_REQUEST.
            assertThat(api.patchStatus(open.buyer().token(),
                    "/api/v1/intent-items/" + open.itemId(), Map.of("quantity", 0)))
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("is frozen in shape once sent")
        void sentIsFrozenInShape() throws Exception {
            var open = sendRequest(5);
            // Quantities moved to isQuantityEditable(); everything structural
            // still checks isEditable(), which remains DRAFT-only.
            // ErrorCode.INVALID_STATE_TRANSITION -> CONFLICT.
            assertThat(api.postStatus(open.buyer().token(),
                    "/api/v1/intents/" + open.intentId() + "/send", Map.of()))
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("is frozen in quantity once the supplier has answered")
        void frozenOnceAnswered() throws Exception {
            var open = sendRequest(5);
            answer(open, 5);
            // RESPONSES_RECEIVED is where this enum always said the freeze
            // belonged: the supplier has now priced a specific list.
            assertThat(api.patchStatus(open.buyer().token(),
                    "/api/v1/intent-items/" + open.itemId(), Map.of("quantity", 9)))
                    .isEqualTo(409);
        }
    }

    @Nested
    @DisplayName("what the supplier offers for delivery (D-141)")
    class DeliveryOffer {

        private void policy(Seller seller, int own, int costonomy) {
            jdbc.update("""
                    insert into supplier_delivery_policy
                        (supplier_store_id, own_delivery_enabled, costonomy_delivery_enabled,
                         own_delivery_fee, created_at, updated_at, version)
                    values (?, ?, ?, 30, now(6), now(6), 0)
                    on duplicate key update own_delivery_enabled = ?, costonomy_delivery_enabled = ?,
                         own_delivery_fee = 30
                    """, seller.storeId(), own, costonomy, own, costonomy);
        }

        /** A sent request for 6 paneer, answered in full with the given delivery offer (null leaves it out). */
        private OpenRequest answered(String offer, int own, int costonomy) throws Exception {
            return answered(offer, null, own, costonomy);
        }

        private OpenRequest answered(String offer, String fee, int own, int costonomy) throws Exception {
            var open = sendRequest(6);
            policy(open.seller(), own, costonomy);
            var body = new java.util.HashMap<String, Object>();
            body.put("lines", List.of(Map.of("intentItemId", open.itemId(), "offeredQuantity", 6)));
            if (offer != null) {
                body.put("deliveryOffer", offer);
            }
            if (fee != null) {
                body.put("deliveryFee", fee);
            }
            var response = respond(open.seller().token(), open.intentId(), body);
            assertThat(response.at("/error").isMissingNode() || response.at("/error").isNull())
                    .as(response.toString()).isTrue();
            return open;
        }

        private JsonNode orderWith(OpenRequest open, String mode) throws Exception {
            return createOrder(open.buyer().token(), open.intentId(), Map.of("deliveryMode", mode));
        }

        @Test
        @DisplayName("free delivery is stated as free and charged as nothing")
        void freeDelivery() throws Exception {
            var open = answered("SELF_FREE", 1, 1);

            var shown = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(shown.at("/data/acceptance/deliveryOffer").asText()).isEqualTo("SELF_FREE");
            assertThat(shown.at("/data/acceptance/deliveryFee").asDouble()).isZero();
            assertThat(shown.at("/data/acceptance/deliveryModes").asText()).isEqualTo("PICKUP,SUPPLIER_DELIVERY");

            var created = orderWith(open, "SUPPLIER_DELIVERY");
            long orderId = created.at("/data/supplierOrderId").asLong();
            assertThat(orderId).as(created.toString()).isPositive();
            assertThat(jdbc.queryForObject("select delivery_fee from supplier_order where id = ?",
                    java.math.BigDecimal.class, orderId)).isEqualByComparingTo("0");
            assertThat(jdbc.queryForObject("select delivery_mode from supplier_order where id = ?",
                    String.class, orderId)).isEqualTo("SUPPLIER_DELIVERY");
        }

        @Test
        @DisplayName("the supplier's own delivery at their fee is shown and charged at that fee")
        void paidSelfDelivery() throws Exception {
            var open = answered("SELF", 1, 1);

            var shown = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(shown.at("/data/acceptance/deliveryFee").asDouble()).isEqualTo(30.0);

            long orderId = orderWith(open, "SUPPLIER_DELIVERY").at("/data/supplierOrderId").asLong();
            assertThat(jdbc.queryForObject("select delivery_fee from supplier_order where id = ?",
                    java.math.BigDecimal.class, orderId)).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("a supplier can charge less than their store fee for one order, and that is what the buyer pays")
        void lowerChargeForOneOrder() throws Exception {
            var open = answered("SELF", "20", 1, 1);

            var shown = api.get(open.buyer().token(), "/api/v1/intents/" + open.intentId());
            assertThat(shown.at("/data/acceptance/deliveryFee").asDouble()).isEqualTo(20.0);

            long orderId = orderWith(open, "SUPPLIER_DELIVERY").at("/data/supplierOrderId").asLong();
            assertThat(jdbc.queryForObject("select delivery_fee from supplier_order where id = ?",
                    java.math.BigDecimal.class, orderId)).isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("a supplier cannot charge more than their store's delivery fee")
        void chargeIsCapped() throws Exception {
            var open = sendRequest(6);
            policy(open.seller(), 1, 1);

            var refused = respond(open.seller().token(), open.intentId(), Map.of(
                    "lines", List.of(Map.of("intentItemId", open.itemId(), "offeredQuantity", 6)),
                    "deliveryOffer", "SELF", "deliveryFee", "31"));

            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
            assertThat(jdbc.queryForObject("select status from intent where id = ?", String.class, open.intentId()))
                    .isEqualTo("OPEN");
        }

        @Test
        @DisplayName("the buyer can choose only what was offered")
        void onlyWhatWasOffered() throws Exception {
            var selfOffered = answered("SELF_FREE", 1, 1);
            var refused = orderWith(selfOffered, "COSTONOMY_DELIVERY");
            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");

            var ridersOffered = answered("COSTONOMY", 1, 1);
            var alsoRefused = orderWith(ridersOffered, "SUPPLIER_DELIVERY");
            assertThat(alsoRefused.at("/error/code").asText()).as(alsoRefused.toString())
                    .isEqualTo("VALIDATION_ERROR");
            // Pickup is always there.
            assertThat(orderWith(ridersOffered, "PICKUP").at("/data/supplierOrderId").asLong()).isPositive();
        }

        @Test
        @DisplayName("a supplier can offer to deliver without any standing delivery setting, free")
        void deliversWithoutAStandingSetting() throws Exception {
            // Own delivery is off and no fee is configured: the supplier still says "I will deliver this one".
            var open = answered("SELF_FREE", 0, 1);

            long orderId = orderWith(open, "SUPPLIER_DELIVERY").at("/data/supplierOrderId").asLong();
            assertThat(orderId).isPositive();
            assertThat(jdbc.queryForObject("select delivery_fee from supplier_order where id = ?",
                    java.math.BigDecimal.class, orderId)).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("without a store fee the charge for own delivery can only be nothing")
        void noStoreFeeMeansNoCharge() throws Exception {
            var open = sendRequest(6);
            policy(open.seller(), 0, 1);
            jdbc.update("update supplier_delivery_policy set own_delivery_fee = 0 where supplier_store_id = ?",
                    open.seller().storeId());

            var refused = respond(open.seller().token(), open.intentId(), Map.of(
                    "lines", List.of(Map.of("intentItemId", open.itemId(), "offeredQuantity", 6)),
                    "deliveryOffer", "SELF", "deliveryFee", "10"));

            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("a supplier cannot offer Costonomy delivery if their store has turned it off")
        void costonomyBounded() throws Exception {
            var open = sendRequest(6);
            policy(open.seller(), 1, 0);

            var refused = respond(open.seller().token(), open.intentId(), Map.of(
                    "lines", List.of(Map.of("intentItemId", open.itemId(), "offeredQuantity", 6)),
                    "deliveryOffer", "COSTONOMY"));

            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
            assertThat(jdbc.queryForObject("select status from intent where id = ?", String.class, open.intentId()))
                    .isEqualTo("OPEN");
        }
    }

    @Nested
    @DisplayName("deliver or collect, asked per supplier's request (D-143)")
    class DeliveryPreference {

        private JsonNode prefer(String token, long intentId, String preference) throws Exception {
            return json.readTree(mvc.perform(MockMvcRequestBuilders
                            .put("/api/v1/intents/" + intentId + "/delivery-preference")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("preference", preference))))
                    .andReturn().getResponse().getContentAsString());
        }

        /** A draft for 6 paneer with the given preference, sent, and the supplier's delivery set up. */
        private OpenRequest sentWith(String preference) throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long skuId = listSku(seller, "paneer", "410");
            jdbc.update("""
                    insert into supplier_delivery_policy
                        (supplier_store_id, own_delivery_enabled, costonomy_delivery_enabled,
                         own_delivery_fee, created_at, updated_at, version)
                    values (?, 1, 1, 30, now(6), now(6), 0)
                    on duplicate key update own_delivery_enabled = 1, costonomy_delivery_enabled = 1
                    """, seller.storeId());
            var draft = addItem(buyer, skuId, 6);
            long intentId = draft.at("/data/id").asLong();
            long itemId = draft.at("/data/items/0/id").asLong();
            if (preference != null) {
                var set = prefer(buyer.token(), intentId, preference);
                assertThat(set.at("/data/deliveryPreference").asText()).as(set.toString()).isEqualTo(preference);
            }
            api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
            return new OpenRequest(buyer, seller, intentId, itemId, skuId);
        }

        private JsonNode answerWith(OpenRequest open, String offer) throws Exception {
            var body = new java.util.HashMap<String, Object>();
            body.put("lines", List.of(Map.of("intentItemId", open.itemId(), "offeredQuantity", 6)));
            if (offer != null) {
                body.put("deliveryOffer", offer);
            }
            return respond(open.seller().token(), open.intentId(), body);
        }

        @Test
        @DisplayName("a request is for delivery unless the restaurant says they will collect, and the supplier sees which")
        void defaultsToDelivery() throws Exception {
            var delivery = sentWith(null);
            var collect = sentWith("PICKUP");

            assertThat(api.get(delivery.seller().token(), "/api/v1/intents/" + delivery.intentId())
                    .at("/data/deliveryPreference").asText()).isEqualTo("DELIVERY");
            assertThat(api.get(collect.seller().token(), "/api/v1/intents/" + collect.intentId())
                    .at("/data/deliveryPreference").asText()).isEqualTo("PICKUP");
        }

        @Test
        @DisplayName("it cannot be changed once the request has been sent")
        void lockedOnceSent() throws Exception {
            var open = sentWith("PICKUP");

            var refused = prefer(open.buyer().token(), open.intentId(), "DELIVERY");

            assertThat(refused.at("/error/code").asText()).as(refused.toString())
                    .isEqualTo("INVALID_STATE_TRANSITION");
        }

        @Test
        @DisplayName("an unknown choice is refused")
        void unknownChoice() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            long intentId = addItem(buyer, listSku(seller, "paneer", "410"), 1).at("/data/id").asLong();

            var refused = prefer(buyer.token(), intentId, "DRONE");

            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        @DisplayName("a pickup request is answered with pickup only, whatever delivery the supplier tries to offer")
        void pickupOffersNoDelivery() throws Exception {
            var open = sentWith("PICKUP");

            var answered = answerWith(open, "SELF_FREE");
            assertThat(answered.at("/data/acceptance/deliveryOffer").asText()).as(answered.toString())
                    .isEqualTo("NONE");
            assertThat(answered.at("/data/acceptance/deliveryModes").asText()).isEqualTo("PICKUP");

            var refused = createOrder(open.buyer().token(), open.intentId(), Map.of("deliveryMode", "SUPPLIER_DELIVERY"));
            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
            assertThat(refused.at("/error/message").asText()).contains("not delivering this order");
            assertThat(createOrder(open.buyer().token(), open.intentId(), Map.of("deliveryMode", "PICKUP"))
                    .at("/data/supplierOrderId").asLong()).isPositive();
        }

        @Test
        @DisplayName("a supplier who cannot deliver this one can say so, and the buyer can only collect")
        void cannotDeliver() throws Exception {
            var open = sentWith("DELIVERY");

            var answered = answerWith(open, "NONE");
            assertThat(answered.at("/data/acceptance/deliveryOffer").asText()).as(answered.toString())
                    .isEqualTo("NONE");

            var refused = createOrder(open.buyer().token(), open.intentId(),
                    Map.of("deliveryMode", "COSTONOMY_DELIVERY"));
            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
            // Refused because the supplier is not delivering, not for some other reason (such as a missing quote).
            assertThat(refused.at("/error/message").asText()).contains("not delivering this order");
            assertThat(createOrder(open.buyer().token(), open.intentId(), Map.of("deliveryMode", "PICKUP"))
                    .at("/data/supplierOrderId").asLong()).isPositive();
        }
    }

    @Nested
    @DisplayName("delivery slots and as soon as possible (D-142)")
    class DeliverySlots {

        private long slot(Seller seller, String name, String start, String end) throws Exception {
            var created = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/delivery-slots",
                    Map.of("slotName", name, "startTime", start, "endTime", end,
                            "orderCutoffTime", "23:59:59", "maxOrdersPerDay", 5));
            assertThat(created.at("/error").isMissingNode() || created.at("/error").isNull())
                    .as(created.toString()).isTrue();
            return created.at("/data/id").asLong();
        }

        private OpenRequest answeredRequest() throws Exception {
            var open = sendRequest(6);
            answer(open, 6);
            return open;
        }

        private String day(int plus) {
            return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(plus).toString();
        }

        @Test
        @DisplayName("no slot and no day is as soon as possible, and is accepted")
        void asap() throws Exception {
            var open = answeredRequest();

            var created = createOrder(open.buyer().token(), open.intentId(), Map.of());

            long orderId = created.at("/data/supplierOrderId").asLong();
            assertThat(orderId).as(created.toString()).isPositive();
            assertThat(jdbc.queryForObject("select delivery_slot_id from supplier_order where id = ?",
                    Long.class, orderId)).isNull();
            assertThat(jdbc.queryForObject("select scheduled_delivery_date from supplier_order where id = ?",
                    java.sql.Date.class, orderId)).isNull();
        }

        @Test
        @DisplayName("a slot that has already started today is not offered, and cannot be booked; tomorrow's can")
        void startedSlot() throws Exception {
            var open = answeredRequest();
            long early = slot(open.seller(), "Early", "00:00:01", "23:59:58");

            var today = api.get(open.buyer().token(), "/api/v1/supplier-stores/" + open.seller().storeId()
                    + "/available-slots?date=" + day(0));
            assertThat(today.at("/data/0/available").asBoolean()).isFalse();
            assertThat(today.at("/data/0/unavailableReason").asText()).contains("already started");

            var refused = createOrder(open.buyer().token(), open.intentId(),
                    Map.of("deliverySlotId", early, "scheduledDeliveryDate", day(0)));
            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");

            var booked = createOrder(open.buyer().token(), open.intentId(),
                    Map.of("deliverySlotId", early, "scheduledDeliveryDate", day(1)));
            assertThat(booked.at("/data/supplierOrderId").asLong()).as(booked.toString()).isPositive();
        }

        @Test
        @DisplayName("another store's slot cannot be booked")
        void otherStoresSlot() throws Exception {
            var open = answeredRequest();
            var elsewhere = newSeller("Nandini");
            long foreign = slot(elsewhere, "Morning", "06:00:00", "07:00:00");

            var refused = createOrder(open.buyer().token(), open.intentId(),
                    Map.of("deliverySlotId", foreign, "scheduledDeliveryDate", day(1)));

            assertThat(refused.at("/error/code").asText()).as(refused.toString()).isEqualTo("VALIDATION_ERROR");
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

    @Nested
    @DisplayName("two things happening at once")
    class Concurrency {

        /**
         * The money path, and doc 10 §2's mandatory case.
         *
         * <p>Two order creations on one request, released together. Exactly one
         * order must exist afterwards — a second would be a second real charge
         * against a supplier who committed stock once.
         *
         * <p>Both calls carry <b>different</b> idempotency keys on purpose. A
         * client that crashes and retries usually generates a fresh one, so the
         * header cannot be what makes this safe; {@code uk_intent_order_link_intent}
         * is. Testing it with one shared key would test the idempotency table and
         * call it a concurrency test.
         */
        @Test
        @DisplayName("two order creations produce one order, and both callers are told which (five races)")
        void duplicateOrderCreation() throws Exception {
            for (int round = 1; round <= 5; round++) {
                var open = sendRequest(6);
                answer(open, 6);

                var first = new AtomicReference<JsonNode>();
                var second = new AtomicReference<JsonNode>();

                race(
                    () -> first.set(orderAttempt(open)),
                    () -> second.set(orderAttempt(open)));

                // Both calls succeed and name the SAME order: the loser used to deadlock on the intent row and surface
                // as a 500 (D-135). A sequential retry has always returned the first order; so does a simultaneous one.
                String context = "round %d first=%s second=%s".formatted(round, first.get(), second.get());
                assertThat(first.get().at("/error").isMissingNode() || first.get().at("/error").isNull())
                    .as(context).isTrue();
                assertThat(second.get().at("/error").isMissingNode() || second.get().at("/error").isNull())
                    .as(context).isTrue();
                long orderId = first.get().at("/data/supplierOrderId").asLong();
                assertThat(orderId).as(context).isPositive();
                assertThat(second.get().at("/data/supplierOrderId").asLong()).as(context).isEqualTo(orderId);

                // And the side effects: one order, one link, one payment, never two real charges.
                assertThat(ordersFor(open.buyer().outletId())).as(context).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent_order_link where intent_id = ?",
                        Integer.class, open.intentId())).as(context).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from payment where supplier_order_id = ?",
                        Integer.class, orderId)).as(context).isEqualTo(1);
                assertThat(jdbc.queryForObject("select status from intent where id = ?", String.class,
                        open.intentId())).as(context).isEqualTo("ORDERED");
            }
        }

        @Test
        @DisplayName("two first additions for one supplier make one draft with both lines (five races)")
        void twoFirstAdditionsMakeOneDraft() throws Exception {
            for (int round = 1; round <= 5; round++) {
                var buyer = newBuyer();
                var seller = newSeller("Metro");
                long paneer = listSku(seller, "paneer", "410");
                long rice = listSku(seller, "rice", "120");
                var a = new AtomicReference<JsonNode>();
                var b = new AtomicReference<JsonNode>();

                race(() -> a.set(attempt(() -> addItem(buyer, paneer, 3))),
                     () -> b.set(attempt(() -> addItem(buyer, rice, 4))));

                String context = "round %d a=%s b=%s".formatted(round, a.get(), b.get());
                assertThat(failed(a.get())).as(context).isFalse();
                assertThat(failed(b.get())).as(context).isFalse();
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent where outlet_id = ? and status = 'DRAFT'",
                        Integer.class, buyer.outletId())).as(context).isEqualTo(1);
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent_item i join intent d on d.id = i.intent_id where d.outlet_id = ?",
                        Integer.class, buyer.outletId())).as(context).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("the same pack added twice at once is one line with both quantities (five races)")
        void samePackAddedTwiceAtOnce() throws Exception {
            for (int round = 1; round <= 5; round++) {
                var buyer = newBuyer();
                var seller = newSeller("Metro");
                long paneer = listSku(seller, "paneer", "410");
                var a = new AtomicReference<JsonNode>();
                var b = new AtomicReference<JsonNode>();

                race(() -> a.set(attempt(() -> addItem(buyer, paneer, 1))),
                     () -> b.set(attempt(() -> addItem(buyer, paneer, 1))));

                String context = "round %d a=%s b=%s".formatted(round, a.get(), b.get());
                assertThat(failed(a.get())).as(context).isFalse();
                assertThat(failed(b.get())).as(context).isFalse();
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent where outlet_id = ? and status = 'DRAFT'",
                        Integer.class, buyer.outletId())).as(context).isEqualTo(1);
                assertThat(jdbc.queryForList(
                        "select i.requested_quantity from intent_item i join intent d on d.id = i.intent_id "
                                + "where d.outlet_id = ?", java.math.BigDecimal.class, buyer.outletId()))
                        .as(context).hasSize(1).allSatisfy(q -> assertThat(q).isEqualByComparingTo("2"));
            }
        }

        @Test
        @DisplayName("an add and a send at once lose no line: it is on the sent request or on a new draft (five races)")
        void addAgainstSend() throws Exception {
            for (int round = 1; round <= 5; round++) {
                var buyer = newBuyer();
                var seller = newSeller("Metro");
                long paneer = listSku(seller, "paneer", "410");
                long rice = listSku(seller, "rice", "120");
                long intentId = addItem(buyer, paneer, 2).at("/data/id").asLong();
                var add = new AtomicReference<JsonNode>();
                var send = new AtomicReference<JsonNode>();

                race(() -> add.set(attempt(() -> addItem(buyer, rice, 1))),
                     () -> send.set(attempt(() -> api.post(buyer.token(),
                             "/api/v1/intents/" + intentId + "/send", Map.of()))));

                String context = "round %d add=%s send=%s".formatted(round, add.get(), send.get());
                assertThat(failed(add.get())).as(context).isFalse();
                assertThat(failed(send.get())).as(context).isFalse();
                // Both packs exist exactly once across everything this outlet has, and at most one draft remains.
                assertThat(jdbc.queryForList(
                        "select i.supplier_sku_id from intent_item i join intent d on d.id = i.intent_id "
                                + "where d.outlet_id = ? order by 1", Long.class, buyer.outletId()))
                        .as(context).containsExactlyInAnyOrder(paneer, rice);
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent where outlet_id = ? and status = 'DRAFT'",
                        Integer.class, buyer.outletId())).as(context).isLessThanOrEqualTo(1);
                // The sent request is never left holding a line it was not sent with: its lines are 1 or 2, and any
                // draft holds the rest.
                assertThat(jdbc.queryForObject("select status from intent where id = ?", String.class, intentId))
                        .as(context).isEqualTo("OPEN");
            }
        }

        @Test
        @DisplayName("removing the last line while another is added keeps the new line (five races)")
        void removeLastAgainstAdd() throws Exception {
            for (int round = 1; round <= 5; round++) {
                var buyer = newBuyer();
                var seller = newSeller("Metro");
                long paneer = listSku(seller, "paneer", "410");
                long rice = listSku(seller, "rice", "120");
                long itemId = addItem(buyer, paneer, 2).at("/data/items/0/id").asLong();
                var add = new AtomicReference<JsonNode>();
                var remove = new AtomicReference<Integer>();

                race(() -> add.set(attempt(() -> addItem(buyer, rice, 1))),
                     () -> remove.set(patchQuietly(buyer, itemId)));

                String context = "round %d add=%s remove=%s".formatted(round, add.get(), remove.get());
                assertThat(failed(add.get())).as(context).isFalse();
                assertThat(remove.get()).as(context).isEqualTo(200);
                assertThat(jdbc.queryForList(
                        "select i.supplier_sku_id from intent_item i join intent d on d.id = i.intent_id "
                                + "where d.outlet_id = ? and d.status = 'DRAFT'", Long.class, buyer.outletId()))
                        .as(context).containsExactly(rice);
                assertThat(jdbc.queryForObject(
                        "select count(*) from intent where outlet_id = ? and status = 'DRAFT'",
                        Integer.class, buyer.outletId())).as(context).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a removed line and an emptied draft are written to the audit log")
        void removalIsAudited() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("Metro");
            addItem(buyer, listSku(seller, "rice", "120"), 1);
            var draft = addItem(buyer, listSku(seller, "paneer", "410"), 3);
            long intentId = draft.at("/data/id").asLong();
            long first = draft.at("/data/items/0/id").asLong();
            long second = draft.at("/data/items/1/id").asLong();

            api.patchStatus(buyer.token(), "/api/v1/intent-items/" + first, Map.of("quantity", 0));
            api.patchStatus(buyer.token(), "/api/v1/intent-items/" + second, Map.of("quantity", 0));

            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_log where action = 'INTENT_ITEM_REMOVED' and entity_id in (?, ?)",
                    Integer.class, first, second)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "select count(*) from audit_log where action = 'INTENT_DRAFT_DELETED' and entity_id = ?",
                    Integer.class, intentId)).isEqualTo(1);
        }

        private JsonNode attempt(Callable<JsonNode> call) {
            try {
                return call.call();
            } catch (Exception e) {
                return json.createObjectNode().put("thrown", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private Integer patchQuietly(Buyer buyer, long itemId) {
            try {
                return api.patchStatus(buyer.token(), "/api/v1/intent-items/" + itemId, Map.of("quantity", 0));
            } catch (Exception e) {
                return -1;
            }
        }

        private boolean failed(JsonNode response) {
            return response.has("thrown") || !(response.at("/error").isMissingNode() || response.at("/error").isNull());
        }

        /** One order attempt, with its own idempotency key (a crashed client retries with a fresh one). */
        private JsonNode orderAttempt(OpenRequest open) {
            try {
                return createOrder(open.buyer().token(), open.intentId(), Map.of());
            } catch (Exception e) {
                return json.createObjectNode().put("thrown", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Test
        @DisplayName("replaying an order key with a different delivery mode is refused, not answered with the first order")
        void keyReusedWithADifferentDeliveryIsRefused() throws Exception {
            var open = sendRequest(6);
            answer(open, 6);
            String key = UUID.randomUUID().toString();
            String path = "/api/v1/intents/" + open.intentId() + "/orders";

            var first = mvc.perform(MockMvcRequestBuilders.post(path)
                    .header("Authorization", "Bearer " + open.buyer().token())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deliveryMode\":\"PICKUP\"}")).andReturn().getResponse();
            var replay = mvc.perform(MockMvcRequestBuilders.post(path)
                    .header("Authorization", "Bearer " + open.buyer().token())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deliveryMode\":\"SUPPLIER_DELIVERY\"}")).andReturn().getResponse();

            assertThat(first.getStatus()).isEqualTo(200);
            assertThat(json.readTree(replay.getContentAsString()).at("/error/code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSE");
            assertThat(ordersFor(open.buyer().outletId())).isEqualTo(1);
        }

        /**
         * A supplier answering as the expiry sweep closes the same request.
         *
         * <p>Either outcome is correct — what must not happen is an intent that
         * is EXPIRED and carries a submitted acceptance, because the restaurant
         * would be shown an offer against a request the system has closed.
         */
        @Test
        @DisplayName("answering while expiring leaves one coherent outcome")
        void answerAgainstExpiry() throws Exception {
            var open = sendRequest(4);

            // Wind the send back past the response window so the sweep will take it.
            jdbc.update("update intent set sent_at = date_sub(now(6), interval 30 day) "
                    + "where id = ?", open.intentId());

            race(
                () -> quietly(() -> {
                    expiryJob.sweep();
                    return "swept";
                }),
                () -> quietly(() -> respond(open.seller().token(), open.intentId(),
                        Map.of("lines", List.of(Map.of(
                                "intentItemId", open.itemId(), "offeredQuantity", 4)))).toString()));

            var status = jdbc.queryForObject(
                    "select status from intent where id = ?", String.class, open.intentId());
            int acceptances = jdbc.queryForObject(
                    "select count(*) from intent_acceptance where intent_id = ? and status = 'SUBMITTED'",
                    Integer.class, open.intentId());

            assertThat(status).isIn("OPEN", "EXPIRED", "RESPONSES_RECEIVED");
            if ("EXPIRED".equals(status)) {
                assertThat(acceptances)
                    .as("an expired request must not be showing a live offer")
                    .isZero();
            } else if ("RESPONSES_RECEIVED".equals(status)) {
                assertThat(acceptances).isEqualTo(1);
            }
        }

        /** Both threads released together, so they contend on the database. */
        private void race(Runnable first, Runnable second) throws Exception {
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);
            var pool = Executors.newFixedThreadPool(2);

            for (Runnable task : List.of(first, second)) {
                pool.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (Throwable ignored) {
                        // Each task already records its own outcome; a thrown
                        // exception here is one of the two legitimate losers.
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
        }

        /** Run it, and keep whatever happened — a refusal is a result here. */
        private String quietly(Callable<String> call) {
            try {
                return call.call();
            } catch (Exception e) {
                return e.getClass().getSimpleName() + ": " + e.getMessage();
            }
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
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000", "name", name + " store", "addressLine1", "Road No 36",
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

    /**
     * Defaults the delivery mode so the cases below stay about requests.
     *
     * <p>D-091 made it required: an order has to say how the goods travel before
     * it can be priced, because the fee is part of what is charged. Pickup is the
     * one mode that needs nothing else configured, which is what makes it the
     * right default for a test about something other than delivery.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withPickup(Object body) {
        var merged = new java.util.HashMap<String, Object>();
        if (body instanceof Map<?, ?> given) {
            merged.putAll((Map<String, Object>) given);
        }
        merged.putIfAbsent("deliveryMode", "PICKUP");
        return merged;
    }

    private JsonNode createOrder(String token, long intentId, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(withPickup(body))))
                .andReturn().getResponse().getContentAsString());
    }

    private int createOrderStatus(String token, long intentId, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/intents/" + intentId + "/orders")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(withPickup(body))))
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
