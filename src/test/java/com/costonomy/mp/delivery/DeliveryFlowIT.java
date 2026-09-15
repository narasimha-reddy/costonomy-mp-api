package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.provider.MockDeliveryProvider;
import com.costonomy.mp.delivery.service.DeliveryJobs;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery end to end. Doc 06 in full, doc 04 §14.
 *
 * <p>Three properties carry this suite. <b>One delivery per consignment</b> —
 * reassignment and failure append attempts, never a second journey (doc 06 §7).
 * <b>Nothing is fabricated</b> — no driver before assignment, no position before a
 * provider reports one, and a stale position is marked stale rather than shown as
 * current (doc 06 §8). And <b>bidding stays internal</b>: the restaurant sees one
 * fee and never which partner bid what (doc 06 §4, §10).
 */
@AutoConfigureMockMvc
class DeliveryFlowIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryJobs deliveryJobs;
    @Autowired private TestPaymentAccess payments;

    @Autowired
    @Qualifier("mockExpressDeliveryProvider")
    private MockDeliveryProvider express;

    @Autowired
    @Qualifier("mockSaverDeliveryProvider")
    private MockDeliveryProvider saver;

    private ApiClient api;
    private TestCheckout checkout;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        checkout = new TestCheckout(payments.provider(), api);
        express.disarm();
        saver.disarm();
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    /** An order that has been accepted, prepared and is ready for a courier. */
    private record ReadyOrder(Buyer buyer, Seller seller, long orderId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                        "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                        "contactName", "Asha", "contactPhone", "+919876511111",
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
                        "contactName", "Imran", "contactPhone", "+919876522222",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    /** Set what this store offers. Absent means Costonomy delivery only. */
    private void deliveryPolicy(Seller seller, boolean own, boolean costonomy, String ownFee) {
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, ?, ?, ?, now(6), now(6), 0)
                on duplicate key update own_delivery_enabled = values(own_delivery_enabled),
                    costonomy_delivery_enabled = values(costonomy_delivery_enabled),
                    own_delivery_fee = values(own_delivery_fee)
                """, seller.storeId(), own, costonomy, ownFee);
    }

    /** Place, pay for, accept and prepare an order until a courier is needed. */
    private ReadyOrder readyOrder() throws Exception {
        var buyer = newBuyer();
        var seller = newSeller();
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
                Map.of("supplierOfferId", offerId, "quantity", 10)).at("/data/id").asLong();
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

        return new ReadyOrder(buyer, seller, orderId);
    }

    private JsonNode supplierPost(Seller seller, String path) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + seller.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode requestDelivery(ReadyOrder order) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/supplier-orders/" + order.orderId() + "/delivery")
                        .header("Authorization", "Bearer " + order.seller().token())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data");
    }

    /** An internal operator, for the protected simulation endpoints. */
    private String operator() throws Exception {
        String phone = ApiClient.freshPhone();
        String token = api.login(phone);
        Long userId = jdbc.queryForObject(
                "select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status,
                                       granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                  from role r where r.code = 'OPS_DELIVERY'
                """, userId);
        return token;
    }

    private JsonNode simulate(String operatorToken, long deliveryId, Map<String, Object> body)
            throws Exception {
        String response = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/internal/deliveries/" + deliveryId + "/simulate")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/data");
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }

    // ── The journey ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the journey")
    class Journey {

        @Test
        @DisplayName("ready → booked → assigned → picked up → delivered")
        void endToEnd() throws Exception {
            // Doc 06 §6, all twelve steps.
            var order = readyOrder();
            var operator = operator();

            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            assertThat(delivery.get("mode").asText()).isEqualTo("COSTONOMY");
            assertThat(delivery.get("status").asText()).isEqualTo("PROVIDER_SELECTED");
            assertThat(delivery.get("fee").asDouble()).isPositive();
            // §23A.21: before assignment there is no driver, and the response says
            // so rather than inventing one.
            assertThat(delivery.get("driverName").isNull()).isTrue();
            assertThat(delivery.get("location").isNull()).isTrue();
            assertThat(delivery.get("trackable").asBoolean()).isFalse();

            var assigned = simulate(operator, deliveryId,
                    Map.of("status", "DRIVER_ASSIGNED", "description", "Driver on the way"));
            assertThat(assigned.get("status").asText()).isEqualTo("DRIVER_ASSIGNED");
            assertThat(assigned.get("driverName").asText()).isNotBlank();
            assertThat(assigned.get("trackable").asBoolean()).isTrue();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_AT_PICKUP"));

            var pickedUp = simulate(operator, deliveryId, Map.of("status", "PICKED_UP"));
            assertThat(pickedUp.get("status").asText()).isEqualTo("PICKED_UP");
            // The consignment drives the order. §23A.38: only the courier's event
            // can do this, which is why the supplier has no transition for it.
            assertThat(orderStatus(order.orderId())).isEqualTo("OUT_FOR_DELIVERY");

            simulate(operator, deliveryId, Map.of("status", "IN_TRANSIT",
                    "latitude", "17.4280", "longitude", "78.4660"));
            simulate(operator, deliveryId, Map.of("status", "ARRIVED_AT_DESTINATION"));

            var delivered = simulate(operator, deliveryId, Map.of("status", "DELIVERED"));
            assertThat(delivered.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(orderStatus(order.orderId())).isEqualTo("DELIVERED");

            // The whole journey, in order, for §23A.21's timeline.
            var timeline = delivered.get("timeline");
            var types = new java.util.ArrayList<String>();
            timeline.forEach(entry -> types.add(entry.get("status").asText()));
            assertThat(types).containsSubsequence("DELIVERY_REQUESTED", "PROVIDER_SELECTED",
                    "DRIVER_ASSIGNED", "PICKED_UP", "DELIVERED");
        }

        @Test
        @DisplayName("an order that isn't packed yet can't have a courier sent to it")
        void notReadyMeansNoDelivery() throws Exception {
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

            // Doc 06 §6 step 1. A courier sent to unpacked goods waits, and the ETA
            // starts running against a supplier who cannot meet it.
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/supplier-orders/" + orderId + "/delivery")
                            .header("Authorization", "Bearer " + seller.token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(409);
            assertThat(jdbc.queryForObject("select count(*) from delivery where supplier_order_id = ?",
                    Integer.class, orderId)).isZero();
        }

        @Test
        @DisplayName("asking twice sends one courier")
        void requestIsIdempotent() throws Exception {
            var order = readyOrder();

            long first = requestDelivery(order).get("id").asLong();
            long second = requestDelivery(order).get("id").asLong();

            assertThat(second).isEqualTo(first);
            assertThat(jdbc.queryForObject(
                    "select count(*) from delivery where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);
        }
    }

    // ── Selection and the internal-only rule ─────────────────────────────

    @Nested
    @DisplayName("choosing a partner")
    class Choosing {

        @Test
        @DisplayName("the cheaper partner wins, and every quote is on the record")
        void cheapestWins() throws Exception {
            var order = readyOrder();
            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            // Both mocks quote this route; MOCK_SAVER is cheaper per km.
            var quotes = jdbc.queryForList(
                    "select provider_code, amount, selected from delivery_quote "
                            + "where delivery_id = ? order by id", deliveryId);
            assertThat(quotes).hasSize(2);

            var selected = quotes.stream()
                    .filter(quote -> Boolean.TRUE.equals(quote.get("selected"))
                            || Integer.valueOf(1).equals(quote.get("selected")))
                    .findFirst().orElseThrow();
            assertThat(selected.get("provider_code")).isEqualTo("MOCK_SAVER");

            // Doc 06 §10: the restaurant pays the fee we booked, and it is the
            // cheapest quote rather than an average or a markup of the dearest.
            assertThat(delivery.get("fee").asDouble())
                    .isEqualTo(((java.math.BigDecimal) selected.get("amount")).doubleValue());
        }

        @Test
        @DisplayName("the restaurant never sees who bid what")
        void biddingStaysInternal() throws Exception {
            var order = readyOrder();
            var delivery = requestDelivery(order);

            // Doc 06 §4 and §10. Asserted on the serialised response rather than on
            // the DTO, because a field added later would pass a type-level check.
            String body = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/deliveries/" + delivery.get("id").asLong())
                            .header("Authorization", "Bearer " + order.buyer().token()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("MOCK_SAVER")
                    .doesNotContain("MOCK_EXPRESS")
                    .doesNotContain("providerCode")
                    .doesNotContain("quote");
        }

        @Test
        @DisplayName("a partner that can't quote is skipped, not fatal")
        void quoteFailureFallsBack() throws Exception {
            var order = readyOrder();
            // Doc 06 §7: quote failure → try an alternative provider.
            saver.arm(MockDeliveryProvider.Failure.QUOTE_FAILS);

            var delivery = requestDelivery(order);
            assertThat(delivery.get("status").asText()).isEqualTo("PROVIDER_SELECTED");

            long deliveryId = delivery.get("id").asLong();
            var byProvider = new HashMap<String, String>();
            jdbc.queryForList("select provider_code, status from delivery_quote "
                    + "where delivery_id = ?", deliveryId)
                    .forEach(row -> byProvider.put((String) row.get("provider_code"),
                            (String) row.get("status")));

            // The failure is recorded, so the fallback is explicable afterwards
            // rather than looking like a choice.
            assertThat(byProvider).containsEntry("MOCK_SAVER", "FAILED")
                    .containsEntry("MOCK_EXPRESS", "QUOTED");
        }

        @Test
        @DisplayName("a partner that refuses the booking hands it to the next one")
        void bookingFailureFallsBack() throws Exception {
            var order = readyOrder();
            // Doc 06 §7: provider unavailable → select another.
            saver.arm(MockDeliveryProvider.Failure.BOOKING_FAILS);

            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            assertThat(delivery.get("status").asText()).isEqualTo("PROVIDER_SELECTED");

            var attempts = jdbc.queryForList(
                    "select provider_code, outcome, attempt_number from "
                            + "delivery_provider_attempt where delivery_id = ? "
                            + "order by attempt_number", deliveryId);

            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(0)).containsEntry("provider_code", "MOCK_SAVER")
                    .containsEntry("outcome", "FAILED");
            assertThat(attempts.get(1)).containsEntry("provider_code", "MOCK_EXPRESS")
                    .containsEntry("outcome", "BOOKED");
            // Still one delivery. Doc 06 §7.
            assertThat(jdbc.queryForObject(
                    "select count(*) from delivery where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("when nobody can carry it, the delivery says so")
        void allProvidersFailing() throws Exception {
            var order = readyOrder();
            express.arm(MockDeliveryProvider.Failure.QUOTE_FAILS);
            saver.arm(MockDeliveryProvider.Failure.QUOTE_FAILS);

            var delivery = requestDelivery(order);

            assertThat(delivery.get("status").asText()).isEqualTo("QUOTE_FAILED");
            assertThat(delivery.get("failureCode").asText()).isEqualTo("NO_SERVICEABLE_PROVIDER");
            // Recoverable, not terminal: quoting can be retried once a partner is
            // back, and the goods still need to move.
            assertThat(orderStatus(order.orderId())).isEqualTo("READY_FOR_PICKUP");
        }
    }

    // ── Reassignment ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("reassignment")
    class Reassignment {

        @Test
        @DisplayName("a cancelled driver is replaced without a second delivery")
        void driverCancellationIsReassigned() throws Exception {
            var order = readyOrder();
            var operator = operator();

            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED"));
            var cancelled = simulate(operator, deliveryId,
                    Map.of("status", "DRIVER_CANCELLED", "description", "Bike broke down"));

            assertThat(cancelled.get("status").asText()).isEqualTo("DRIVER_CANCELLED");
            // The driver is gone, so their details go with them — showing a name
            // for a courier who is not coming is worse than showing none.
            assertThat(cancelled.get("driverName").isNull()).isTrue();

            String body = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/deliveries/" + deliveryId + "/reassign")
                            .header("Authorization", "Bearer " + order.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Driver cancelled\"}"))
                    .andReturn().getResponse().getContentAsString();
            var reassigned = json.readTree(body).at("/data");

            // Doc 06 §7: one logical delivery, throughout.
            assertThat(reassigned.get("id").asLong()).isEqualTo(deliveryId);
            assertThat(reassigned.get("status").asText()).isEqualTo("PROVIDER_SELECTED");
            assertThat(jdbc.queryForObject(
                    "select count(*) from delivery where supplier_order_id = ?",
                    Integer.class, order.orderId())).isEqualTo(1);

            // And it went to a different partner — handing it back to the one that
            // just cancelled is not a reassignment.
            var attempts = jdbc.queryForList(
                    "select provider_code, attempt_type, outcome from delivery_provider_attempt "
                            + "where delivery_id = ? order by attempt_number", deliveryId);
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1).get("provider_code"))
                    .isNotEqualTo(attempts.get(0).get("provider_code"));
            assertThat(attempts.get(1)).containsEntry("attempt_type", "REASSIGNMENT");
        }

        @Test
        @DisplayName("an order already collected can't be reassigned")
        void noReassignmentAfterPickup() throws Exception {
            var order = readyOrder();
            var operator = operator();
            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED"));
            simulate(operator, deliveryId, Map.of("status", "PICKED_UP"));

            // A second courier cannot collect what the first one already has.
            int status = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/deliveries/" + deliveryId + "/reassign")
                            .header("Authorization", "Bearer " + order.buyer().token())
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(422);
        }
    }

    // ── Tracking honesty ─────────────────────────────────────────────────

    @Nested
    @DisplayName("tracking")
    class Tracking {

        @Test
        @DisplayName("a position that has gone quiet is marked stale, not shown as current")
        void staleLocationIsFlagged() throws Exception {
            var order = readyOrder();
            var operator = operator();
            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED"));
            var moving = simulate(operator, deliveryId, Map.of("status", "PICKED_UP",
                    "latitude", "17.4300", "longitude", "78.4700"));

            assertThat(moving.get("location").isNull()).isFalse();
            assertThat(moving.get("locationStale").asBoolean()).isFalse();

            // Age the fix past the freshness threshold. Doc 06 §8: the app must
            // show a stale state rather than an old pin as if it were current.
            jdbc.update("update delivery_location set recorded_at = "
                    + "date_sub(utc_timestamp(6), interval 10 minute) where delivery_id = ?",
                    deliveryId);

            var stale = api.get(order.buyer().token(), "/api/v1/deliveries/" + deliveryId)
                    .at("/data");
            assertThat(stale.get("locationStale").asBoolean()).isTrue();
            assertThat(stale.get("locationAgeSeconds").asInt()).isGreaterThan(300);
            // The position is still returned — the app draws it greyed out rather
            // than showing an empty map.
            assertThat(stale.get("location").isNull()).isFalse();
        }

        @Test
        @DisplayName("no position exists before a driver does")
        void noLocationBeforeAssignment() throws Exception {
            var order = readyOrder();
            var delivery = requestDelivery(order);

            // Doc 06 §8: never fabricate. A plausible midpoint is indistinguishable
            // from a real fix once it is on a map.
            assertThat(delivery.get("location").isNull()).isTrue();
            assertThat(delivery.get("trackable").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject(
                    "select count(*) from delivery_location where delivery_id = ?",
                    Integer.class, delivery.get("id").asLong())).isZero();
        }

        @Test
        @DisplayName("a repeated provider event changes nothing")
        void duplicateEventIsIgnored() throws Exception {
            var order = readyOrder();
            var operator = operator();
            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED"));
            simulate(operator, deliveryId, Map.of("status", "PICKED_UP"));

            // The poll re-reads every event the provider is holding, so a provider
            // that keeps its history replays all of it on every sweep. Doc 06 §13's
            // replay protection is what makes that harmless.
            deliveryJobs.pollActiveDeliveries();
            deliveryJobs.pollActiveDeliveries();

            var applied = jdbc.queryForList("select provider_event_id, disposition "
                    + "from delivery_event where delivery_id = ? and provider_event_id is not null",
                    deliveryId);
            var ids = applied.stream().map(row -> row.get("provider_event_id")).toList();
            assertThat(ids).doesNotHaveDuplicates();

            assertThat(jdbc.queryForObject("select status from delivery where id = ?",
                    String.class, deliveryId)).isEqualTo("PICKED_UP");
        }

        @Test
        @DisplayName("a late event can't drag a delivery backwards")
        void outOfOrderEventIsRecordedNotApplied() throws Exception {
            var order = readyOrder();
            var operator = operator();
            var delivery = requestDelivery(order);
            long deliveryId = delivery.get("id").asLong();

            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED"));
            simulate(operator, deliveryId, Map.of("status", "PICKED_UP"));

            // Doc 06 §12. A DRIVER_ASSIGNED arriving now describes the past;
            // applying it would tell a restaurant watching their goods move that
            // the driver is still being found.
            simulate(operator, deliveryId, Map.of("status", "DRIVER_ASSIGNED",
                    "description", "Late duplicate assignment"));

            assertThat(jdbc.queryForObject("select status from delivery where id = ?",
                    String.class, deliveryId)).isEqualTo("PICKED_UP");
            assertThat(jdbc.queryForObject("select count(*) from delivery_event "
                            + "where delivery_id = ? and disposition = 'OUT_OF_ORDER'",
                    Integer.class, deliveryId)).isPositive();

            // And the restaurant's timeline shows only what actually happened.
            var timeline = api.get(order.buyer().token(),
                    "/api/v1/deliveries/" + deliveryId + "/events").at("/data");
            var statuses = new java.util.ArrayList<String>();
            timeline.forEach(entry -> statuses.add(entry.get("status").asText()));
            assertThat(statuses).endsWith("PICKED_UP");
        }
    }

    // ── Own delivery, and who may report what ────────────────────────────

    @Nested
    @DisplayName("supplier own delivery")
    class OwnDelivery {

        @Test
        @DisplayName("the supplier carries it, for their own fee, with no tracking")
        void ownDeliveryNeedsNoPartner() throws Exception {
            var order = readyOrder();
            deliveryPolicy(order.seller(), true, true, "0.00");

            var delivery = requestDelivery(order);

            assertThat(delivery.get("mode").asText()).isEqualTo("SUPPLIER_OWN");
            // Doc 01 §20: the supplier absorbs it, so the restaurant pays nothing.
            assertThat(delivery.get("fee").asDouble()).isZero();
            // Doc 06 §2: no live tracking initially. There is no provider reporting
            // positions, so there is nothing honest to draw.
            assertThat(delivery.get("trackable").asBoolean()).isFalse();
            assertThat(jdbc.queryForObject(
                    "select count(*) from delivery_quote where delivery_id = ?",
                    Integer.class, delivery.get("id").asLong())).isZero();
        }

        @Test
        @DisplayName("the supplier reports their own progress")
        void supplierReportsOwnDelivery() throws Exception {
            var order = readyOrder();
            deliveryPolicy(order.seller(), true, true, "0.00");
            long deliveryId = requestDelivery(order).get("id").asLong();

            api.post(order.seller().token(),
                    "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of());
            assertThat(orderStatus(order.orderId())).isEqualTo("OUT_FOR_DELIVERY");

            api.post(order.seller().token(),
                    "/api/v1/deliveries/" + deliveryId + "/delivered", Map.of());
            assertThat(orderStatus(order.orderId())).isEqualTo("DELIVERED");
        }

        @Test
        @DisplayName("a supplier can't claim a pickup a courier made")
        void supplierCannotClaimPartnerDelivery() throws Exception {
            var order = readyOrder();
            long deliveryId = requestDelivery(order).get("id").asLong();

            // §23A.38. The supplier is not carrying this one, so their word is not
            // evidence of anything — and "just mark it delivered" is exactly what
            // that rule exists to prevent.
            assertThat(api.postStatus(order.seller().token(),
                    "/api/v1/deliveries/" + deliveryId + "/dispatched", Map.of()))
                    .isEqualTo(403);
            assertThat(orderStatus(order.orderId())).isEqualTo("READY_FOR_PICKUP");
        }
    }

    // ── Who may do what ──────────────────────────────────────────────────

    @Nested
    @DisplayName("access")
    class Access {

        @Test
        @DisplayName("an unrelated restaurant can't see the delivery")
        void strangersSeeNothing() throws Exception {
            var order = readyOrder();
            var stranger = newBuyer();
            long deliveryId = requestDelivery(order).get("id").asLong();

            // 404, not 403 (doc 09 §3) — otherwise a caller walks ids and learns
            // which orders are out for delivery.
            assertThat(api.getStatus(stranger.token(), "/api/v1/deliveries/" + deliveryId))
                    .isEqualTo(404);
            assertThat(api.getStatus(stranger.token(),
                    "/api/v1/deliveries/" + deliveryId + "/events")).isEqualTo(404);
        }

        @Test
        @DisplayName("simulation is closed to everyone but operations")
        void simulationIsProtected() throws Exception {
            var order = readyOrder();
            long deliveryId = requestDelivery(order).get("id").asLong();

            // Doc 06 §11: "developer simulation endpoints must be protected and
            // unavailable to normal users". Neither side of a real order is one.
            for (String token : new String[] {order.buyer().token(), order.seller().token()}) {
                int status = mvc.perform(MockMvcRequestBuilders
                                .post("/api/v1/internal/deliveries/" + deliveryId + "/simulate")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"DELIVERED\"}"))
                        .andReturn().getResponse().getStatus();

                assertThat(status).isEqualTo(403);
            }
            assertThat(orderStatus(order.orderId())).isEqualTo("READY_FOR_PICKUP");
        }
    }
}
