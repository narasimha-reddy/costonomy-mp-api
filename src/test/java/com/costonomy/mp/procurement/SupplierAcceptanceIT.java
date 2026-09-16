package com.costonomy.mp.procurement;

import com.costonomy.mp.procurement.service.SupplierOrderTimeoutJob;
import com.costonomy.mp.procurement.service.SupplierOrderTransitions;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.payment.provider.MockPaymentProvider;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplier acceptance, rejection, partial acceptance and timeout.
 *
 * <p>Includes the concurrency scenarios doc 10 §2 makes mandatory: acceptance
 * against timeout, duplicate acceptance, and acceptance against cancellation —
 * each asserting that exactly one outcome lands.
 */
@AutoConfigureMockMvc
class SupplierAcceptanceIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SupplierOrderTransitions transitions;
    @Autowired private SupplierOrderTimeoutJob timeoutJob;
    @Autowired private MockPaymentProvider paymentProvider;

    private ApiClient api;
    private TestCheckout checkout;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(paymentProvider, api);
    }

    private record Buyer(String token, long outletId, long userId) {
    }

    /**
     * @param userId the supplier's own user id. Carried rather than re-derived
     *               from a role grant: the owner is granted at SUPPLIER scope, not
     *               SUPPLIER_STORE, and guessing the scope in a test helper is how
     *               a test ends up asserting something other than it intends.
     */
    private record Seller(String token, long storeId, long userId) {
    }

    /** A placed order, with both sides' tokens. */
    private record PlacedOrder(Buyer buyer, Seller seller, long orderId, long procurementId,
                               Long requirementItemId) {
    }

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

    /** Place an order for {@code quantity}, optionally against a requirement. */
    private PlacedOrder placeOrder(int quantity, boolean withRequirement) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("ABC Foods");
        long productId = TestCatalog.freshProduct(jdbc, "paneer");

        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                        "name", "Paneer", "packSize", 1, "packUnit", "KG",
                        "sellingPrice", "410", "gstRate", "5")).at("/data/id").asLong();
        long offerId = jdbc.queryForObject(
                "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                Long.class, skuId);

        Long requirementItemId = null;
        if (withRequirement) {
            requirementItemId = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/requirements", Map.of(
                            "items", List.of(Map.of("canonicalProductId", productId,
                                    "quantity", quantity, "unit", "KG"))))
                    .at("/data/items/0/id").asLong();
        }

        var cartRequest = requirementItemId == null
                ? Map.<String, Object>of("supplierOfferId", offerId, "quantity", quantity)
                : Map.<String, Object>of("supplierOfferId", offerId, "quantity", quantity,
                        "requirementItemId", requirementItemId);

        long procurementId = api.post(buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/cart/items", cartRequest)
                .at("/data/id").asLong();

        api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                Map.of("acceptPriceChanges", false));

        var submitted = submitOrder(buyer, procurementId);
        long orderId = submitted.at("/data/supplierOrders/0/id").asLong();

        // The order is DRAFT until it is paid for. Everything below is about what
        // a supplier does with an order they can see, so pay for it here.
        checkout.payAll(buyer.token(), submitted);

        return new PlacedOrder(buyer, seller, orderId, procurementId, requirementItemId);
    }

    private JsonNode submitOrder(Buyer buyer, long procurementId) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/procurements/" + procurementId + "/submit")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode respond(String token, String path, Object body) throws Exception {
        String response = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private int respondStatus(String token, String path, Object body, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }

    // ── Acceptance ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("acceptance")
    class Acceptance {

        @Test
        @DisplayName("accepting confirms the order and commits the full amount")
        void acceptConfirms() throws Exception {
            var placed = placeOrder(20, false);

            var accepted = respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of()).at("/data");

            assertThat(accepted.get("status").asText()).isEqualTo("CONFIRMED");
            // 20 × ₹410 + 5% = ₹8,610.
            assertThat(accepted.get("acceptedAmount").asDouble()).isEqualTo(8610.00);
            assertThat(accepted.get("items").get(0).get("acceptedQuantity").asDouble())
                    .isEqualTo(20.0);
        }

        @Test
        @DisplayName("the acceptance event names the supplier")
        void acceptanceEventNamesTheSupplier() throws Exception {
            var placed = placeOrder(20, false);
            respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of());

            String payload = jdbc.queryForObject("""
                    select payload from outbox_event
                     where event_type = 'SupplierOrderAccepted' and aggregate_id = ?
                    """, String.class, placed.orderId());

            // Doc 08's template is "{supplierName} accepted order {orderNumber}".
            // The payload carried supplierStoreId and no name, so the renderer
            // dropped the placeholder and a restaurant was told " accepted order
            // MP-…". The notification test supplied the name itself, so it proved
            // the template and never the payload.
            assertThat(payload).contains("\"supplierName\"");
            assertThat(payload).doesNotContain("\"supplierName\": \"\"");
        }

        @Test
        @DisplayName("accepting credits the requirement — and only then")
        void acceptCreditsTheRequirement() throws Exception {
            var placed = placeOrder(20, true);

            long requirementId = jdbc.queryForObject(
                    "select requirement_id from requirement_item where id = ?",
                    Long.class, placed.requirementItemId());

            // Before: submitted, nothing credited (D-015).
            var before = api.get(placed.buyer().token(), "/api/v1/requirements/" + requirementId)
                    .at("/data/items/0");
            assertThat(before.get("fulfilledQuantity").asDouble()).isZero();
            assertThat(before.get("remainingQuantity").asDouble()).isEqualTo(20.0);

            respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of());

            var after = api.get(placed.buyer().token(), "/api/v1/requirements/" + requirementId)
                    .at("/data");
            assertThat(after.at("/items/0/fulfilledQuantity").asDouble()).isEqualTo(20.0);
            assertThat(after.at("/items/0/remainingQuantity").asDouble()).isZero();
            assertThat(after.get("status").asText()).isEqualTo("FULFILLED");
        }

        @Test
        @DisplayName("a repeated accept returns the same order, not an error")
        void acceptIsIdempotent() throws Exception {
            var placed = placeOrder(20, false);
            String path = "/api/v1/supplier-orders/" + placed.orderId() + "/accept";
            String key = UUID.randomUUID().toString();

            assertThat(respondStatus(placed.seller().token(), path, Map.of(), key)).isEqualTo(200);
            // Same key: replayed. A supplier tapping Accept twice on a slow
            // connection should see their order, not a failure.
            assertThat(respondStatus(placed.seller().token(), path, Map.of(), key)).isEqualTo(200);
            // Different key: the order is already CONFIRMED, which is still the
            // true answer to "did my acceptance land?".
            assertThat(respondStatus(placed.seller().token(), path, Map.of(),
                    UUID.randomUUID().toString())).isEqualTo(200);

            assertThat(orderStatus(placed.orderId())).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("another supplier cannot accept this order")
        void acceptanceIsScoped() throws Exception {
            var placed = placeOrder(20, false);
            var stranger = newSeller("XYZ Traders");

            assertThat(respondStatus(stranger.token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of(),
                    UUID.randomUUID().toString()))
                    .isEqualTo(404);
        }
    }

    // ── Partial acceptance ───────────────────────────────────────────────

    @Nested
    @DisplayName("partial acceptance")
    class PartialAcceptance {

        @Test
        @DisplayName("accepting 12 of 20 commits only the accepted value")
        void partialCommitsOnlyWhatWasAccepted() throws Exception {
            var placed = placeOrder(20, true);
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, placed.orderId());

            var result = respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId,
                            "acceptedQuantity", 12,
                            "reason", "Only 12 kg in stock")))).at("/data");

            assertThat(result.get("status").asText()).isEqualTo("PARTIALLY_ACCEPTED");
            // 12 × ₹410 + 5% = ₹5,166. Doc 01 §14: only this is ever captured.
            assertThat(result.get("acceptedAmount").asDouble()).isEqualTo(5166.00);
            assertThat(result.get("items").get(0).get("acceptedQuantity").asDouble()).isEqualTo(12.0);
        }

        @Test
        @DisplayName("the shortfall stays on the requirement, ready to source elsewhere")
        void shortfallSurvives() throws Exception {
            var placed = placeOrder(20, true);
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, placed.orderId());

            respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "acceptedQuantity", 12))));

            long requirementId = jdbc.queryForObject(
                    "select requirement_id from requirement_item where id = ?",
                    Long.class, placed.requirementItemId());
            var requirement = api.get(placed.buyer().token(),
                    "/api/v1/requirements/" + requirementId).at("/data");

            // Guardrail 14, doc 01 §10. The restaurant never retypes the 8 kg.
            assertThat(requirement.at("/items/0/fulfilledQuantity").asDouble()).isEqualTo(12.0);
            assertThat(requirement.at("/items/0/remainingQuantity").asDouble()).isEqualTo(8.0);
            assertThat(requirement.get("status").asText()).isEqualTo("PARTIALLY_FULFILLED");
        }

        @Test
        @DisplayName("find-suppliers ranks alternatives against the remaining quantity")
        void alternativesTargetTheShortfall() throws Exception {
            var placed = placeOrder(20, true);
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, placed.orderId());
            long productId = jdbc.queryForObject(
                    "select canonical_product_id from supplier_order_item where id = ?",
                    Long.class, itemId);

            respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "acceptedQuantity", 12))));

            // Another supplier who can cover the remaining 8 kg.
            var rescue = newSeller("XYZ Traders");
            api.post(rescue.token(), "/api/v1/supplier-stores/" + rescue.storeId() + "/skus",
                    Map.of("canonicalProductId", productId, "skuCode", "XYZ-PNR",
                            "name", "Paneer", "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "425", "gstRate", "5"));

            long requirementId = jdbc.queryForObject(
                    "select requirement_id from requirement_item where id = ?",
                    Long.class, placed.requirementItemId());

            var alternatives = api.post(placed.buyer().token(),
                    "/api/v1/requirements/" + requirementId + "/find-suppliers", Map.of())
                    .at("/data/items/0");

            // Doc 15: the restaurant recovers without recreating the requirement.
            assertThat(alternatives.get("remainingQuantity").asDouble()).isEqualTo(8.0);
            assertThat(alternatives.get("offers")).isNotEmpty();
        }

        @Test
        @DisplayName("accepting more than was ordered is refused")
        void cannotAcceptMoreThanOrdered() throws Exception {
            var placed = placeOrder(20, false);
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, placed.orderId());

            // Not generosity — an order the restaurant never placed.
            assertThat(respondStatus(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "acceptedQuantity", 25))),
                    UUID.randomUUID().toString()))
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("zero on every line is recorded as a rejection")
        void zeroEverywhereIsARejection() throws Exception {
            var placed = placeOrder(20, true);
            long itemId = jdbc.queryForObject(
                    "select id from supplier_order_item where supplier_order_id = ?",
                    Long.class, placed.orderId());

            var result = respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of(Map.of(
                            "supplierOrderItemId", itemId, "acceptedQuantity", 0)))).at("/data");

            // Recording it as a partial acceptance of nothing would corrupt both
            // the supplier's acceptance rate and the restaurant's view of events.
            assertThat(result.get("status").asText()).isEqualTo("REJECTED");
            assertThat(result.get("acceptedAmount").asDouble()).isZero();

            long requirementId = jdbc.queryForObject(
                    "select requirement_id from requirement_item where id = ?",
                    Long.class, placed.requirementItemId());
            assertThat(api.get(placed.buyer().token(), "/api/v1/requirements/" + requirementId)
                    .at("/data/items/0/remainingQuantity").asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("every line must be answered")
        void everyLineMustBeAnswered() throws Exception {
            var placed = placeOrder(20, false);

            // An omitted line is ambiguous between declined and forgotten.
            assertThat(respondStatus(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/partial-accept",
                    Map.of("items", List.of()), UUID.randomUUID().toString()))
                    .isEqualTo(400);
        }
    }

    // ── Rejection and timeout ────────────────────────────────────────────

    @Nested
    @DisplayName("rejection and timeout")
    class RejectionAndTimeout {

        @Test
        @DisplayName("rejection records a reason and credits nothing")
        void rejectionCreditsNothing() throws Exception {
            var placed = placeOrder(20, true);

            var result = respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/reject",
                    Map.of("reason", "OUT_OF_STOCK", "note", "Delivery truck broke down"))
                    .at("/data");

            assertThat(result.get("status").asText()).isEqualTo("REJECTED");
            assertThat(result.get("acceptedAmount").asDouble()).isZero();

            long requirementId = jdbc.queryForObject(
                    "select requirement_id from requirement_item where id = ?",
                    Long.class, placed.requirementItemId());
            // Nothing to compensate: nothing was ever credited (D-015).
            assertThat(api.get(placed.buyer().token(), "/api/v1/requirements/" + requirementId)
                    .at("/data/items/0/remainingQuantity").asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("an unrecognised rejection reason is refused")
        void rejectionReasonIsAClosedSet() throws Exception {
            var placed = placeOrder(20, false);

            // Free text cannot be counted, and these feed supplier performance.
            assertThat(respondStatus(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/reject",
                    Map.of("reason", "did not feel like it"), UUID.randomUUID().toString()))
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("the timeout job expires an unanswered order")
        void timeoutExpiresUnanswered() throws Exception {
            var placed = placeOrder(20, true);

            // Age the deadline rather than waiting 60 seconds.
            jdbc.update("update supplier_order set acceptance_deadline = "
                    + "date_sub(utc_timestamp(6), interval 1 minute) where id = ?", placed.orderId());

            timeoutJob.expireOverdueOrders();

            assertThat(orderStatus(placed.orderId())).isEqualTo("EXPIRED");

            // Expiry is not rejection. The accepted quantity stays null — nobody
            // ever answered — rather than zero, which would claim they declined.
            var acceptedQuantity = jdbc.queryForObject(
                    "select accepted_quantity from supplier_order_item where supplier_order_id = ?",
                    java.math.BigDecimal.class, placed.orderId());
            assertThat(acceptedQuantity).isNull();
        }

        @Test
        @DisplayName("an expired order cannot then be accepted")
        void expiredCannotBeAccepted() throws Exception {
            var placed = placeOrder(20, false);
            jdbc.update("update supplier_order set acceptance_deadline = "
                    + "date_sub(utc_timestamp(6), interval 1 minute) where id = ?", placed.orderId());
            timeoutJob.expireOverdueOrders();

            assertThat(respondStatus(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of(),
                    UUID.randomUUID().toString()))
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("a passed deadline refuses acceptance even before the job runs")
        void deadlineIsTheAuthorityNotTheJob() throws Exception {
            var placed = placeOrder(20, false);

            // The window closed; the sweep has not happened yet. Doc 13 says a
            // supplier cannot accept an expired order — at every instant, not
            // eventually.
            jdbc.update("update supplier_order set acceptance_deadline = "
                    + "date_sub(utc_timestamp(6), interval 1 second) where id = ?", placed.orderId());

            assertThat(respondStatus(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of(),
                    UUID.randomUUID().toString()))
                    .isEqualTo(409);
            assertThat(orderStatus(placed.orderId())).isEqualTo("PENDING_ACCEPTANCE");
        }

        @Test
        @DisplayName("the job leaves an answered order alone")
        void jobSkipsAnsweredOrders() throws Exception {
            var placed = placeOrder(20, false);
            respond(placed.seller().token(),
                    "/api/v1/supplier-orders/" + placed.orderId() + "/accept", Map.of());

            jdbc.update("update supplier_order set acceptance_deadline = "
                    + "date_sub(utc_timestamp(6), interval 1 minute) where id = ?", placed.orderId());
            timeoutJob.expireOverdueOrders();

            assertThat(orderStatus(placed.orderId())).isEqualTo("CONFIRMED");
        }
    }

    // ── Concurrency (doc 10 §2) ──────────────────────────────────────────

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * Run two operations as simultaneously as two threads can manage, and
         * report what each one did.
         *
         * <p>A latch rather than a sleep: both threads block until released, so
         * they contend on the database rather than on a timer.
         */
        private <T> void race(Runnable first, Runnable second,
                              AtomicReference<Throwable> firstError,
                              AtomicReference<Throwable> secondError) throws Exception {

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(2);
            var pool = Executors.newFixedThreadPool(2);

            pool.submit(() -> {
                try {
                    start.await();
                    first.run();
                } catch (Throwable t) {
                    firstError.set(t);
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    start.await();
                    second.run();
                } catch (Throwable t) {
                    secondError.set(t);
                } finally {
                    done.countDown();
                }
            });

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
        }

        @Test
        @DisplayName("acceptance racing timeout produces exactly one outcome")
        void acceptanceVersusTimeout() throws Exception {
            var placed = placeOrder(20, false);
            long supplierUserId = placed.seller().userId();

            // The order is exactly at its deadline: both outcomes are legitimate,
            // which is what makes this a race rather than a sequence.
            jdbc.update("update supplier_order set acceptance_deadline = utc_timestamp(6) "
                    + "where id = ?", placed.orderId());

            var acceptError = new AtomicReference<Throwable>();
            var expireError = new AtomicReference<Throwable>();

            race(
                    () -> transitions.accept(supplierUserId, placed.orderId()),
                    () -> transitions.expire(placed.orderId()),
                    acceptError, expireError);

            String finalStatus = orderStatus(placed.orderId());

            // Doc 03 §5 and doc 10 §2: exactly one terminal outcome. Which one wins
            // is not specified and does not matter; that only one does, does.
            assertThat(finalStatus)
                    .describedAs("the order must land on exactly one outcome")
                    .isIn("CONFIRMED", "EXPIRED");

            if ("CONFIRMED".equals(finalStatus)) {
                assertThat(acceptError.get())
                        .describedAs("the winning acceptance must not have failed").isNull();
            } else {
                // The supplier is told their order expired — the thing that
                // actually happened — not that a concurrent modification occurred.
                assertThat(acceptError.get()).isNotNull();
                assertThat(rootMessage(acceptError.get()))
                        .containsAnyOf("no longer be accepted", "expired");
            }

            // And the money reflects the outcome, either way.
            var acceptedAmount = jdbc.queryForObject(
                    "select accepted_amount from supplier_order where id = ?",
                    java.math.BigDecimal.class, placed.orderId());
            if ("EXPIRED".equals(finalStatus)) {
                assertThat(acceptedAmount).isEqualByComparingTo("0.00");
            } else {
                assertThat(acceptedAmount).isEqualByComparingTo("8610.00");
            }
        }

        @Test
        @DisplayName("two simultaneous acceptances produce one acceptance")
        void duplicateAcceptance() throws Exception {
            var placed = placeOrder(20, false);
            long supplierUserId = placed.seller().userId();

            var firstError = new AtomicReference<Throwable>();
            var secondError = new AtomicReference<Throwable>();

            race(
                    () -> transitions.accept(supplierUserId, placed.orderId()),
                    () -> transitions.accept(supplierUserId, placed.orderId()),
                    firstError, secondError);

            assertThat(orderStatus(placed.orderId())).isEqualTo("CONFIRMED");

            // Whether the loser errors or replays, the requirement must be credited
            // once — double-crediting would make a 20 kg need look 40 kg satisfied.
            var acceptedAmount = jdbc.queryForObject(
                    "select accepted_amount from supplier_order where id = ?",
                    java.math.BigDecimal.class, placed.orderId());
            assertThat(acceptedAmount).isEqualByComparingTo("8610.00");
        }

        @Test
        @DisplayName("acceptance racing cancellation produces exactly one outcome")
        void acceptanceVersusCancellation() throws Exception {
            var placed = placeOrder(20, false);
            long supplierUserId = placed.seller().userId();
            long buyerUserId = placed.buyer().userId();

            var acceptError = new AtomicReference<Throwable>();
            var cancelError = new AtomicReference<Throwable>();

            race(
                    () -> transitions.accept(supplierUserId, placed.orderId()),
                    () -> transitions.cancel(buyerUserId, placed.orderId(), "Changed our minds"),
                    acceptError, cancelError);

            assertThat(orderStatus(placed.orderId()))
                    .describedAs("a cancelled order must not also be confirmed")
                    .isIn("CONFIRMED", "CANCELLED");
            // Exactly one of them succeeded.
            assertThat(acceptError.get() == null ^ cancelError.get() == null).isTrue();
        }

        private String rootMessage(Throwable error) {
            Throwable current = error;
            while (current.getCause() != null && current.getMessage() == null) {
                current = current.getCause();
            }
            return String.valueOf(current.getMessage());
        }
    }
}
