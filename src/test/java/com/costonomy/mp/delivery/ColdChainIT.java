package com.costonomy.mp.delivery;

import com.costonomy.mp.delivery.service.DeliveryService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.costonomy.mp.support.TestCatalog;
import com.costonomy.mp.support.TestOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cold chain: one place stamps it on every order, a supplier's declaration is superseded rather than edited, and a
 * chilled order is carried only by a carrier verified for it (D-134). Every test asserts rows, not just statuses, and
 * builds its own data, because the suite shares one database.
 */
@AutoConfigureMockMvc
class ColdChainIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeliveryService deliveryService;

    private ApiClient api;
    private TestOrder orders;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
    }

    /** The seeded capability rows belong to the two mock providers; a test that removes them puts them back. */
    @AfterEach
    void restoreMockCapability() {
        jdbc.update("""
                insert into delivery_provider_cold_chain_capability (delivery_provider_id, vehicle_type, evidence, verified_by)
                select p.id, v.vehicle_type,
                       'LOCAL AND TEST USE ONLY: the mock provider simulates a temperature-controlled vehicle; it is not a real carrier',
                       'seed'
                  from delivery_provider p
                  join (select 'THREE_WHEELER' as vehicle_type union all select 'FOUR_WHEELER_TRUCK') v
                 where p.code in ('MOCK_EXPRESS', 'MOCK_SAVER')
                   and not exists (select 1 from delivery_provider_cold_chain_capability c
                                    where c.delivery_provider_id = p.id and c.vehicle_type = v.vehicle_type)
                """);
        jdbc.update("update delivery_provider set enabled = 1 where code in ('MOCK_EXPRESS', 'MOCK_SAVER')");
    }

    private void noCarrierIsVerified() {
        jdbc.update("delete from delivery_provider_cold_chain_capability");
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long orgId, long storeId, long productId, long skuId) {
    }

    private record Reply(int status, JsonNode body) {
        String code() {
            return body.at("/error/code").asText();
        }
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

    /** A store that delivers and lets Costonomy deliver, with an ordinary SKU (no cold chain declared) at Rs 100. */
    private Seller newSeller(boolean chilledProduct) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", "Fresh Dairy " + System.nanoTime() + " Pvt Ltd", "displayName", "Fresh Dairy",
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000",
                        "name", "Fresh Dairy store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long orgId = created.get("id").asLong();
        long storeId = created.get("stores").get(0).get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', verification_status = 'VERIFIED' where id = ?",
                orgId);
        TestCatalog.tradesAroundTheClock(jdbc, orgId);
        jdbc.update("""
                insert into supplier_delivery_policy (supplier_store_id, own_delivery_enabled,
                    costonomy_delivery_enabled, own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 0, now(6), now(6), 0)
                """, storeId);
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        if (chilledProduct) {
            jdbc.update("update canonical_product set requires_cold_chain = 1 where id = ?", productId);
        }
        long skuId = createSku(token, storeId, productId, null);
        return new Seller(token, orgId, storeId, productId, skuId);
    }

    private long createSku(String token, long storeId, long productId, Boolean coldChain) throws Exception {
        var body = new java.util.HashMap<String, Object>(Map.of("canonicalProductId", productId,
                "skuCode", "PNR-" + System.nanoTime(), "name", "Paneer", "packSize", 1, "packUnit", "KG",
                "sellingPrice", "100", "gstRate", "5"));
        if (coldChain != null) {
            body.put("requiresColdChain", coldChain);
        }
        var reply = post(token, "/api/v1/supplier-stores/" + storeId + "/skus", body);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.body().at("/data/id").asLong();
    }

    private Reply post(String token, String path, Object body) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        return reply(result);
    }

    private Reply send(String method, String token, String path, Object body) throws Exception {
        var request = switch (method) {
            case "PUT" -> MockMvcRequestBuilders.put(path);
            case "PATCH" -> MockMvcRequestBuilders.patch(path);
            default -> throw new IllegalArgumentException(method);
        };
        var result = mvc.perform(request.header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse();
        return reply(result);
    }

    private Reply reply(org.springframework.mock.web.MockHttpServletResponse result) throws Exception {
        String text = result.getContentAsString();
        return new Reply(result.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private Reply declare(Seller s, Map<String, Object> body) throws Exception {
        return send("PUT", s.token(), "/api/v1/supplier-skus/" + s.skuId() + "/handling", body);
    }

    private long placePickupOrder(Buyer buyer, Seller seller) throws Exception {
        api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up", Map.of("amount", "5000.00"));
        return orders.place(buyer.token(), buyer.outletId(), seller.token(), seller.skuId(), 5, 5,
                "PICKUP", "WALLET", null).orderId();
    }

    private int declarationRows(long skuId) {
        return jdbc.queryForObject("select count(*) from supplier_sku_handling_declaration where supplier_sku_id = ?",
                Integer.class, skuId);
    }

    private int currentDeclarations(long skuId) {
        return jdbc.queryForObject("""
                select count(*) from supplier_sku_handling_declaration
                 where supplier_sku_id = ? and effective_to is null""", Integer.class, skuId);
    }

    private boolean skuFlag(long skuId, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select " + column + " from supplier_sku where id = ?",
                Boolean.class, skuId));
    }

    // ── one place stamps every order ─────────────────────────────────────

    @Nested
    @DisplayName("every order is stamped from one place")
    class Stamping {

        @Test
        @DisplayName("a chilled SKU gives a chilled line and a chilled order header, and the HSN code is copied")
        void chilledSkuStampsLineAndHeader() throws Exception {
            var seller = newSeller(false);
            jdbc.update("update supplier_sku set hsn_code = '0406' where id = ?", seller.skuId());
            assertThat(declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled paneer")).status())
                    .isEqualTo(200);

            long orderId = placePickupOrder(newBuyer(), seller);

            assertThat(jdbc.queryForObject("select has_cold_chain_items from supplier_order where id = ?",
                    Boolean.class, orderId)).isTrue();
            var line = jdbc.queryForMap("""
                    select requires_cold_chain, hsn_code from supplier_order_item where supplier_order_id = ?""", orderId);
            assertThat(line.get("requires_cold_chain")).isIn(true, 1);
            assertThat(line.get("hsn_code")).isEqualTo("0406");
        }

        @Test
        @DisplayName("an ordinary SKU gives an ordinary order: nothing is flagged by default")
        void ordinarySkuIsNotFlagged() throws Exception {
            var seller = newSeller(false);

            long orderId = placePickupOrder(newBuyer(), seller);

            assertThat(jdbc.queryForObject("select has_cold_chain_items from supplier_order where id = ?",
                    Boolean.class, orderId)).isFalse();
            assertThat(jdbc.queryForObject("select requires_cold_chain from supplier_order_item where supplier_order_id = ?",
                    Boolean.class, orderId)).isFalse();
        }

        @Test
        @DisplayName("a product that requires cold chain raises its SKUs to it, and an order of that SKU is chilled")
        void productFloorRaisesTheSku() throws Exception {
            var seller = newSeller(true);   // the product is chilled; the SKU was created without saying so

            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isTrue();
            assertThat(jdbc.queryForObject("""
                    select requires_cold_chain from supplier_sku_handling_declaration
                     where supplier_sku_id = ? and effective_to is null""", Boolean.class, seller.skuId())).isTrue();

            long orderId = placePickupOrder(newBuyer(), seller);
            assertThat(jdbc.queryForObject("select has_cold_chain_items from supplier_order where id = ?",
                    Boolean.class, orderId)).isTrue();
        }

        @Test
        @DisplayName("declaring a chilled product's SKU as not chilled is refused and saves nothing")
        void floorCannotBeDeclaredAway() throws Exception {
            var seller = newSeller(true);
            int before = declarationRows(seller.skuId());

            var onCreate = post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus", Map.of(
                    "canonicalProductId", seller.productId(), "skuCode", "X-" + System.nanoTime(), "name", "Paneer",
                    "packSize", 1, "packUnit", "KG", "sellingPrice", "100", "gstRate", "5",
                    "requiresColdChain", false));
            var onDeclare = declare(seller, Map.of("requiresColdChain", false, "reason", "Not really chilled"));

            assertThat(onCreate.status()).isEqualTo(400);
            assertThat(onDeclare.status()).isEqualTo(400);
            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isTrue();
            assertThat(declarationRows(seller.skuId())).isEqualTo(before);
            assertThat(jdbc.queryForObject("select count(*) from supplier_sku where supplier_store_id = ?",
                    Integer.class, seller.storeId())).isEqualTo(1);
        }
    }

    // ── declarations supersede ───────────────────────────────────────────

    @Nested
    @DisplayName("a supplier's declaration is superseded, never edited")
    class Declarations {

        @Test
        @DisplayName("a change closes the current row, opens the next, and is audited with before, after and the reason")
        void changeSupersedes() throws Exception {
            var seller = newSeller(false);
            assertThat(declarationRows(seller.skuId())).isEqualTo(1);

            var reply = declare(seller, Map.of("requiresColdChain", true, "reason", "Now sold chilled"));

            assertThat(reply.status()).isEqualTo(200);
            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isTrue();
            assertThat(declarationRows(seller.skuId())).isEqualTo(2);
            assertThat(currentDeclarations(seller.skuId())).isEqualTo(1);
            var rows = jdbc.queryForList("""
                    select id, requires_cold_chain, effective_to, superseded_by_id, reason
                      from supplier_sku_handling_declaration where supplier_sku_id = ? order by id""", seller.skuId());
            assertThat(rows.get(0).get("effective_to")).isNotNull();
            assertThat(((Number) rows.get(0).get("superseded_by_id")).longValue())
                    .isEqualTo(((Number) rows.get(1).get("id")).longValue());
            assertThat(rows.get(1).get("effective_to")).isNull();
            assertThat(rows.get(1).get("reason")).isEqualTo("Now sold chilled");

            var audit = jdbc.queryForMap("""
                    select before_json, after_json, reason from audit_log
                     where action = 'SKU_HANDLING_DECLARED' and entity_type = 'SUPPLIER_SKU' and entity_id = ?""",
                    seller.skuId());
            assertThat(json.readTree(audit.get("before_json").toString()).get("requiresColdChain").asBoolean()).isFalse();
            assertThat(json.readTree(audit.get("after_json").toString()).get("requiresColdChain").asBoolean()).isTrue();
            assertThat(audit.get("reason")).isEqualTo("Now sold chilled");
        }

        @Test
        @DisplayName("repeating the same declaration, or saving the SKU with the values it already has, changes nothing")
        void unchangedIsANoOp() throws Exception {
            var seller = newSeller(false);
            declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled"));
            int rows = declarationRows(seller.skuId());
            int audits = jdbc.queryForObject("select count(*) from audit_log where action = 'SKU_HANDLING_DECLARED' and entity_id = ?",
                    Integer.class, seller.skuId());

            assertThat(declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled")).status()).isEqualTo(200);
            assertThat(send("PATCH", seller.token(), "/api/v1/supplier-skus/" + seller.skuId(),
                    Map.of("requiresColdChain", true, "name", "Paneer block")).status()).isEqualTo(200);

            assertThat(declarationRows(seller.skuId())).isEqualTo(rows);
            assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'SKU_HANDLING_DECLARED' and entity_id = ?",
                    Integer.class, seller.skuId())).isEqualTo(audits);
        }

        @Test
        @DisplayName("the ordinary SKU update refuses to flip cold chain or catch-weight in place")
        void inPlaceEditIsBlocked() throws Exception {
            var seller = newSeller(false);

            var coldChain = send("PATCH", seller.token(), "/api/v1/supplier-skus/" + seller.skuId(),
                    Map.of("requiresColdChain", true));
            var catchWeight = send("PATCH", seller.token(), "/api/v1/supplier-skus/" + seller.skuId(),
                    Map.of("isCatchWeight", true));

            assertThat(coldChain.status()).isEqualTo(400);
            assertThat(catchWeight.status()).isEqualTo(400);
            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isFalse();
            assertThat(skuFlag(seller.skuId(), "is_catch_weight")).isFalse();
            assertThat(declarationRows(seller.skuId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a change needs a reason, and the supplier's own store: a blank reason or another supplier changes nothing")
        void reasonAndOwnership() throws Exception {
            var seller = newSeller(false);
            var stranger = newSeller(false);

            assertThat(declare(seller, Map.of("requiresColdChain", true)).status()).isEqualTo(400);
            assertThat(declare(seller, Map.of("requiresColdChain", true, "reason", "  ")).status()).isEqualTo(400);
            assertThat(send("PUT", stranger.token(), "/api/v1/supplier-skus/" + seller.skuId() + "/handling",
                    Map.of("requiresColdChain", true, "reason", "Sneaky")).status()).isEqualTo(404);

            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isFalse();
            assertThat(declarationRows(seller.skuId())).isEqualTo(1);
        }

        @Test
        @DisplayName("an order already placed keeps what it was placed with; the next order gets the new declaration")
        void placedOrdersKeepTheirSnapshot() throws Exception {
            var seller = newSeller(false);
            var buyer = newBuyer();
            long before = placePickupOrder(buyer, seller);

            declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled from now"));
            long after = placePickupOrder(buyer, seller);

            assertThat(jdbc.queryForObject("select has_cold_chain_items from supplier_order where id = ?",
                    Boolean.class, before)).isFalse();
            assertThat(jdbc.queryForObject("select requires_cold_chain from supplier_order_item where supplier_order_id = ?",
                    Boolean.class, before)).isFalse();
            assertThat(jdbc.queryForObject("select has_cold_chain_items from supplier_order where id = ?",
                    Boolean.class, after)).isTrue();
        }

        @Test
        @DisplayName("two declarations at once leave exactly one current row, with both changes applied in turn")
        void concurrentDeclarations() throws Exception {
            var seller = newSeller(false);

            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                start.await();
                return declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled"));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return declare(seller, Map.of("isCatchWeight", true, "reason", "Sold by weight"));
            }));
            start.countDown();
            for (var future : futures) {
                assertThat(future.get().status()).isEqualTo(200);
            }
            pool.shutdown();

            assertThat(currentDeclarations(seller.skuId())).isEqualTo(1);
            assertThat(declarationRows(seller.skuId())).isEqualTo(3);
            assertThat(skuFlag(seller.skuId(), "requires_cold_chain")).isTrue();
            assertThat(skuFlag(seller.skuId(), "is_catch_weight")).isTrue();
        }
    }

    // ── only a verified carrier carries chilled goods ────────────────────

    @Nested
    @DisplayName("chilled goods go only with a verified carrier")
    class Carriers {

        private long chilledIntent(Buyer buyer, Seller seller) throws Exception {
            declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled"));
            var draft = api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/intent-items",
                    Map.of("supplierSkuId", seller.skuId(), "quantity", 5));
            long intentId = draft.at("/data/id").asLong();
            long itemId = draft.at("/data/items/0/id").asLong();
            api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
            post(seller.token(), "/api/v1/intents/" + intentId + "/respond", Map.of("lines",
                    List.of(Map.of("intentItemId", itemId, "offeredQuantity", 5))));
            return intentId;
        }

        private int feeQuotes(long intentId) {
            return jdbc.queryForObject("select count(*) from delivery_fee_quote where intent_id = ?",
                    Integer.class, intentId);
        }

        @Test
        @DisplayName("at checkout, with no verified carrier, our delivery is refused: 422, no quote saved, no order")
        void checkoutRefusedWithNoCarrier() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller(false);
            long intentId = chilledIntent(buyer, seller);
            noCarrierIsVerified();

            var refused = post(buyer.token(), "/api/v1/intents/" + intentId + "/delivery-quote", Map.of());

            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.code()).isEqualTo("DELIVERY_UNAVAILABLE");
            assertThat(refused.body().at("/error/message").asText()).contains("chilled goods");
            assertThat(feeQuotes(intentId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from intent_order_link where intent_id = ?",
                    Integer.class, intentId)).isZero();
        }

        @Test
        @DisplayName("with a verified carrier the fee is quoted, and the quote is marked as a chilled one")
        void checkoutQuotedWithAVerifiedCarrier() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller(false);
            long intentId = chilledIntent(buyer, seller);   // the mock providers are seeded as verified

            var quoted = post(buyer.token(), "/api/v1/intents/" + intentId + "/delivery-quote", Map.of());

            assertThat(quoted.status()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select cold_chain from delivery_fee_quote where intent_id = ?",
                    Boolean.class, intentId)).isTrue();
        }

        @Test
        @DisplayName("a fee quoted for ordinary goods cannot be spent after the SKU is declared chilled")
        void staleQuoteIsRefused() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller(false);
            var draft = api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/intent-items",
                    Map.of("supplierSkuId", seller.skuId(), "quantity", 5));
            long intentId = draft.at("/data/id").asLong();
            long itemId = draft.at("/data/items/0/id").asLong();
            api.post(buyer.token(), "/api/v1/intents/" + intentId + "/send", Map.of());
            post(seller.token(), "/api/v1/intents/" + intentId + "/respond", Map.of("lines",
                    List.of(Map.of("intentItemId", itemId, "offeredQuantity", 5))));
            String reference = api.post(buyer.token(), "/api/v1/intents/" + intentId + "/delivery-quote", Map.of())
                    .at("/data/quoteReference").asText();
            assertThat(jdbc.queryForObject("select cold_chain from delivery_fee_quote where reference = ?",
                    Boolean.class, reference)).isFalse();

            declare(seller, Map.of("requiresColdChain", true, "reason", "Chilled from now"));
            api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/wallet/top-up", Map.of("amount", "5000.00"));
            var order = post(buyer.token(), "/api/v1/intents/" + intentId + "/orders", Map.of(
                    "deliveryMode", "COSTONOMY_DELIVERY", "deliveryQuoteReference", reference, "paymentMethod", "WALLET"));

            assertThat(order.status()).isEqualTo(422);
            assertThat(order.code()).isEqualTo("PRICE_CHANGED");
            assertThat(jdbc.queryForObject("select count(*) from intent_order_link where intent_id = ?",
                    Integer.class, intentId)).isZero();
            assertThat(jdbc.queryForObject("select consumed_at from delivery_fee_quote where reference = ?",
                    java.sql.Timestamp.class, reference)).isNull();
        }

        /** A ready, chilled order from a store in Hyderabad, as CarrierFareFailClosedIT seeds one. */
        private long readyChilledOrder() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller(false);
            long storeId = seller.storeId();
            jdbc.update("update supplier_store set pincode = '500003' where id = ?", storeId);
            // Any existing user will do for the audit column.
            Long buyerUserId = jdbc.queryForObject("select min(id) from users", Long.class);
            jdbc.update("""
                    insert into procurement (outlet_id, created_by, status, approval_status,
                                             payment_method, payment_status, total_amount, created_at, updated_at, version)
                    values (?, ?, 'SUBMITTED', 'NOT_REQUIRED', 'PREPAID', 'CAPTURED', 500.00, now(6), now(6), 0)
                    """, buyer.outletId(), buyerUserId);
            Long procurementId = jdbc.queryForObject(
                    "select id from procurement where outlet_id = ? order by id desc limit 1", Long.class, buyer.outletId());
            String orderNumber = "SO-" + System.nanoTime();
            jdbc.update("""
                    insert into supplier_order (procurement_id, supplier_store_id, outlet_id, order_number, status,
                                                total_amount, accepted_amount, delivery_mode, payment_method, payment_status,
                                                has_cold_chain_items, created_at, updated_at, version)
                    values (?, ?, ?, ?, 'READY_FOR_PICKUP', 500.00, 500.00, 'COSTONOMY_DELIVERY', 'PREPAID', 'CAPTURED',
                            1, now(6), now(6), 0)
                    """, procurementId, storeId, buyer.outletId(), orderNumber);
            return jdbc.queryForObject("select id from supplier_order where order_number = ?", Long.class, orderNumber);
        }

        @Test
        @DisplayName("at dispatch, with no verified carrier, the delivery fails with its own code and nothing is booked or charged")
        void dispatchFailsWithNoCarrier() throws Exception {
            long orderId = readyChilledOrder();
            noCarrierIsVerified();

            deliveryService.autoDispatch(orderId);

            var delivery = jdbc.queryForMap("""
                    select id, status, failure_code, provider_code, fee from delivery where supplier_order_id = ?""", orderId);
            assertThat(delivery.get("status")).isEqualTo("QUOTE_FAILED");
            assertThat(delivery.get("failure_code")).isEqualTo("NO_COLD_CHAIN_CARRIER");
            assertThat(delivery.get("provider_code")).isNull();
            long deliveryId = ((Number) delivery.get("id")).longValue();
            assertThat(jdbc.queryForObject("select count(*) from delivery_provider_attempt where delivery_id = ?",
                    Integer.class, deliveryId)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from delivery_ledger where delivery_id = ?",
                    Integer.class, deliveryId)).isZero();
            assertThat(jdbc.queryForList("select status from delivery_quote where delivery_id = ?", String.class, deliveryId))
                    .isNotEmpty().allMatch("UNSERVICEABLE"::equals);
        }

        @Test
        @DisplayName("at dispatch, with a verified carrier, it is booked in the vehicle class it is verified for")
        void dispatchBooksAVerifiedCarrier() throws Exception {
            long orderId = readyChilledOrder();

            deliveryService.autoDispatch(orderId);

            var delivery = jdbc.queryForMap("""
                    select id, status, provider_code from delivery where supplier_order_id = ?""", orderId);
            assertThat(delivery.get("provider_code")).isIn("MOCK_EXPRESS", "MOCK_SAVER");
            assertThat(delivery.get("status")).isNotEqualTo("QUOTE_FAILED");
            long deliveryId = ((Number) delivery.get("id")).longValue();
            var selected = jdbc.queryForMap("""
                    select provider_code, vehicle_type from delivery_quote where delivery_id = ? and selected = 1""", deliveryId);
            assertThat(selected.get("vehicle_type")).isIn("THREE_WHEELER", "FOUR_WHEELER_TRUCK");
        }
    }
}
