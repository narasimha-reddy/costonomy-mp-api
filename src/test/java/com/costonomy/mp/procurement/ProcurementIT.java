package com.costonomy.mp.procurement;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements, cart, checkout, approval and submission, end to end.
 *
 * <p>The guardrails under test here are the expensive ones: never silently
 * reprice, never silently drop an unmet quantity, never let a client decide
 * whether approval applies, and never place the same order twice.
 */
@AutoConfigureMockMvc
class ProcurementIT extends AbstractIntegrationTest {

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

    private record Buyer(String token, long restaurantId, long outletId) {
    }

    private record Seller(String token, long supplierId, long storeId) {
    }

    private Buyer newBuyer() throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of(
                        "name", "Banjara Hills",
                        "addressLine1", "Road No 12",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "pincode", "500034",
                        "latitude", "17.4156",
                        "longitude", "78.4347"))).get("data");
        return new Buyer(token, created.get("id").asLong(),
                created.get("outlets").get(0).get("id").asLong());
    }

    private Seller newSeller(String name) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd",
                "displayName", name,
                "firstStore", Map.of(
                        "name", name + " store",
                        "addressLine1", "Road No 36",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "latitude", "17.4399",
                        "longitude", "78.4983"))).get("data");

        long supplierId = created.get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);

        return new Seller(token, supplierId, created.get("stores").get(0).get("id").asLong());
    }

    /** Stock a product and return the live offer id, which is what a cart references. */
    private long stockOffer(Seller seller, long productId, String code, String price) throws Exception {
        long skuId = api.post(seller.token(), "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                Map.of("canonicalProductId", productId,
                        "skuCode", code,
                        "name", code,
                        "packSize", 1,
                        "packUnit", "KG",
                        "sellingPrice", price,
                        "gstRate", "5")).at("/data/id").asLong();

        return jdbc.queryForObject(
                "select id from supplier_offer where supplier_sku_id = ? and status = 'ACTIVE'",
                Long.class, skuId);
    }

    private JsonNode addToCart(Buyer buyer, long offerId, int quantity) throws Exception {
        return api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                Map.of("supplierOfferId", offerId, "quantity", quantity)).at("/data");
    }

    private JsonNode validate(Buyer buyer, long procurementId, boolean accept) throws Exception {
        return api.post(buyer.token(), "/api/v1/procurements/" + procurementId + "/validate",
                Map.of("acceptPriceChanges", accept)).at("/data");
    }

    private JsonNode submit(Buyer buyer, long procurementId, String key) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/procurements/" + procurementId + "/submit")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private int submitStatus(Buyer buyer, long procurementId, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/procurements/" + procurementId + "/submit")
                        .header("Authorization", "Bearer " + buyer.token())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getStatus();
    }

    // ── Cart ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cart")
    class Cart {

        @Test
        @DisplayName("the server computes every figure")
        void serverComputesTotals() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"),
                    "PNR", "410.00");

            var cart = addToCart(buyer, offerId, 20);

            // 20 × ₹410 = ₹8,200, 5% GST = ₹410, total ₹8,610. Guardrail 3: the
            // client sent a quantity and an offer id, nothing else.
            assertThat(cart.get("totalItemValue").asDouble()).isEqualTo(8200.00);
            assertThat(cart.get("totalGst").asDouble()).isEqualTo(410.00);
            assertThat(cart.get("totalAmount").asDouble()).isEqualTo(8610.00);
        }

        @Test
        @DisplayName("adding the same SKU again increases its quantity")
        void repeatedAddIncreasesQuantity() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "curd"), "CURD", "80.00");

            addToCart(buyer, offerId, 5);
            var cart = addToCart(buyer, offerId, 3);

            // Two lines for one SKU would leave the restaurant reconciling their
            // own cart by eye.
            assertThat(cart.get("supplierGroups").get(0).get("items")).hasSize(1);
            assertThat(cart.get("supplierGroups").get(0).get("items").get(0)
                    .get("quantity").asDouble()).isEqualTo(8.0);
        }

        @Test
        @DisplayName("lines are grouped by supplier")
        void groupedBySupplier() throws Exception {
            var buyer = newBuyer();
            var one = newSeller("ABC Foods");
            var two = newSeller("XYZ Traders");
            addToCart(buyer, stockOffer(one, TestCatalog.freshProduct(jdbc, "p1"), "A", "100"), 2);
            var cart = addToCart(buyer,
                    stockOffer(two, TestCatalog.freshProduct(jdbc, "p2"), "B", "200"), 3);

            // §23A.16 renders these as sections. The restaurant sees one cart even
            // though the backend will split it into two orders.
            assertThat(cart.get("supplierGroups")).hasSize(2);
        }

        @Test
        @DisplayName("a cart cannot be submitted before it is validated")
        void draftIsNotSubmittable() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            var cart = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10);

            assertThat(cart.get("submittable").asBoolean()).isFalse();
            assertThat(submitStatus(buyer, cart.get("id").asLong(), UUID.randomUUID().toString()))
                    .isEqualTo(409);
        }
    }

    // ── Price changes ────────────────────────────────────────────────────

    @Nested
    @DisplayName("price changes")
    class PriceChanges {

        @Test
        @DisplayName("a price that moved is reported with both figures, and blocks checkout")
        void priceChangeIsSurfaced() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long offerId = stockOffer(seller, productId, "PNR", "410.00");

            long cartId = addToCart(buyer, offerId, 20).get("id").asLong();
            assertThat(validate(buyer, cartId, false).get("submittable").asBoolean()).isTrue();

            // The supplier reprices between the restaurant filling the cart and
            // checking out — the exact scenario guardrail 13 is about.
            long skuId = jdbc.queryForObject(
                    "select supplier_sku_id from supplier_offer where id = ?", Long.class, offerId);
            api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "438.00"));

            var revalidated = validate(buyer, cartId, false);

            assertThat(revalidated.get("priceChanges")).hasSize(1);
            var change = revalidated.get("priceChanges").get(0);
            // §23A.16: show the old and the new. Both, so the restaurant can see
            // what actually happened rather than being told a number changed.
            assertThat(change.get("previousUnitPrice").asDouble()).isEqualTo(410.00);
            assertThat(change.get("newUnitPrice").asDouble()).isEqualTo(438.00);
            assertThat(change.get("previousLineTotal").asDouble()).isEqualTo(8610.00);
            assertThat(change.get("newLineTotal").asDouble()).isEqualTo(9198.00);

            assertThat(revalidated.get("submittable").asBoolean()).isFalse();
            // And the cart still holds the price they agreed to — not the new one.
            assertThat(revalidated.get("totalAmount").asDouble()).isEqualTo(8610.00);

            assertThat(submitStatus(buyer, cartId, UUID.randomUUID().toString()))
                    .describedAs("a changed price must block submission")
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("accepting the change explicitly applies it and unblocks checkout")
        void acceptingPriceChangeApplies() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"),
                    "PNR", "410.00");

            long cartId = addToCart(buyer, offerId, 20).get("id").asLong();
            validate(buyer, cartId, false);

            long skuId = jdbc.queryForObject(
                    "select supplier_sku_id from supplier_offer where id = ?", Long.class, offerId);
            api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "438.00"));

            validate(buyer, cartId, false);
            var accepted = validate(buyer, cartId, true);

            assertThat(accepted.get("priceChanges")).isEmpty();
            assertThat(accepted.get("totalAmount").asDouble()).isEqualTo(9198.00);
            assertThat(accepted.get("submittable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an unchanged price does not register as a change")
        void unchangedPriceIsNotAChange() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "curd"), "CURD", "80.00");
            long cartId = addToCart(buyer, offerId, 5).get("id").asLong();

            validate(buyer, cartId, false);
            // A confirmation that fires when nothing moved teaches restaurants to
            // click through the confirmation that exists to protect them.
            assertThat(validate(buyer, cartId, false).get("priceChanges")).isEmpty();
        }

        @Test
        @DisplayName("an out-of-stock line blocks checkout with a reason")
        void unavailableLineBlocks() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "ghee"), "GHEE", "620");
            long cartId = addToCart(buyer, offerId, 2).get("id").asLong();

            long skuId = jdbc.queryForObject(
                    "select supplier_sku_id from supplier_offer where id = ?", Long.class, offerId);
            api.patchStatus(seller.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("availability", "OUT_OF_STOCK"));

            var result = validate(buyer, cartId, false);

            assertThat(result.get("blockers")).hasSize(1);
            assertThat(result.get("blockers").get(0).get("code").asText()).isEqualTo("SKU_UNAVAILABLE");
            assertThat(result.get("submittable").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("a supplier going offline blocks checkout")
        void offlineSupplierBlocks() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "rice"), "RICE", "1450"), 2)
                    .get("id").asLong();

            api.patchStatus(seller.token(), "/api/v1/supplier-stores/" + seller.storeId(),
                    Map.of("status", "OFFLINE"));

            var result = validate(buyer, cartId, false);
            assertThat(result.get("blockers").get(0).get("code").asText()).isEqualTo("SUPPLIER_OFFLINE");
        }
    }

    // ── Approval ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("approval")
    class Approval {

        private void addPolicy(Buyer buyer, String conditions, String approverRoles) {
            jdbc.update("""
                    insert into procurement_policy
                        (restaurant_id, name, conditions_json, approver_roles_json,
                         priority, policy_version, status, effective_from, created_at, updated_at, version)
                    values (?, 'High value orders', ?, ?, 100, 1, 'ACTIVE', now(6), now(6), now(6), 0)
                    """, buyer.restaurantId(), conditions, approverRoles);
        }

        @Test
        @DisplayName("an order over the threshold is held for approval")
        void policyHoldsHighValueOrders() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"20000\"}", "[\"REST_OWNER\"]");

            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"),
                    "PNR", "410.00");
            long cartId = addToCart(buyer, offerId, 100).get("id").asLong();  // ₹43,050

            var validated = validate(buyer, cartId, false);

            // Decided by the server, from the policy. The client did not ask.
            assertThat(validated.get("approvalStatus").asText()).isEqualTo("PENDING");
            assertThat(validated.get("status").asText()).isEqualTo("PENDING_APPROVAL");
            assertThat(validated.get("submittable").asBoolean()).isFalse();

            assertThat(submitStatus(buyer, cartId, UUID.randomUUID().toString()))
                    .describedAs("an unapproved order must not reach suppliers")
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("an order under the threshold is not held")
        void policyIgnoresSmallOrders() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"20000\"}", "[\"REST_OWNER\"]");

            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "curd"), "CURD", "80.00"), 5)
                    .get("id").asLong();

            var validated = validate(buyer, cartId, false);
            assertThat(validated.get("approvalStatus").asText()).isEqualTo("NOT_REQUIRED");
            assertThat(validated.get("submittable").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("approving unblocks submission")
        void approvalUnblocks() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"1000\"}", "[\"REST_OWNER\"]");

            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            var approved = api.post(buyer.token(),
                    "/api/v1/procurements/" + cartId + "/approve", Map.of()).at("/data");

            assertThat(approved.get("approvalStatus").asText()).isEqualTo("APPROVED");
            assertThat(approved.get("submittable").asBoolean()).isTrue();

            assertThat(submit(buyer, cartId, UUID.randomUUID().toString())
                    .at("/data/status").asText()).isEqualTo("SUBMITTED");
        }

        @Test
        @DisplayName("editing after approval clears it")
        void editingClearsApproval() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"1000\"}", "[\"REST_OWNER\"]");

            long offerId = stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410");
            long cartId = addToCart(buyer, offerId, 10).get("id").asLong();
            validate(buyer, cartId, false);
            api.post(buyer.token(), "/api/v1/procurements/" + cartId + "/approve", Map.of());

            // An approved order whose contents changed is not an approved order.
            var edited = addToCart(buyer, offerId, 50);

            assertThat(edited.get("approvalStatus").asText()).isEqualTo("NOT_REQUIRED");
            assertThat(edited.get("status").asText()).isEqualTo("DRAFT");
            assertThat(edited.get("submittable").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("rejection returns the order to draft so it can be fixed")
        void rejectionIsRecoverable() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"1000\"}", "[\"REST_OWNER\"]");

            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            var rejected = api.post(buyer.token(), "/api/v1/procurements/" + cartId + "/reject",
                    Map.of("reason", "Too much paneer")).at("/data");

            assertThat(rejected.get("approvalStatus").asText()).isEqualTo("REJECTED");
            assertThat(rejected.get("status").asText()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("someone without the permission cannot approve")
        void approvalIsPermissioned() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            addPolicy(buyer, "{\"minOrderValue\":\"1000\"}", "[\"REST_OWNER\"]");

            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            // Procurement staff can build a cart and cannot approve it. That
            // separation is what makes an approval policy mean anything.
            String staffPhone = ApiClient.freshPhone();
            api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/users",
                    Map.of("phone", staffPhone, "roleCode", "REST_PROCUREMENT_STAFF"));
            String staff = api.login(staffPhone);

            assertThat(api.postStatus(staff, "/api/v1/procurements/" + cartId + "/approve", Map.of()))
                    .isEqualTo(404);
        }
    }

    // ── Submission ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("order history")
    class History {

        @Test
        @DisplayName("an unfunded order is not in it, however the window is asked for")
        void draftsStayInvisible() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "P", "100"), 2)
                    .get("id").asLong();
            api.post(buyer.token(), "/api/v1/procurements/" + cartId + "/validate", null);
            submit(buyer, cartId, UUID.randomUUID().toString());

            // Submitted but never paid: the order exists and is DRAFT, which is
            // exactly the state guardrail 16 says a supplier must never see.
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_order where supplier_store_id = ? "
                            + "and status = 'DRAFT'", Integer.class, seller.storeId()))
                    .isEqualTo(1);

            String base = "/api/v1/supplier-stores/" + seller.storeId() + "/orders";
            assertThat(api.get(seller.token(), base).at("/data")).isEmpty();
            // And asking for it by name returns nothing rather than the order.
            assertThat(api.get(seller.token(), base + "?status=DRAFT").at("/data")).isEmpty();
        }

        @Test
        @DisplayName("the window is on when the order arrived, and defaults to a week")
        void windowIsOnArrival() throws Exception {
            var seller = newSeller("XYZ Traders");
            String base = "/api/v1/supplier-stores/" + seller.storeId() + "/orders";

            // A window that ends before anything existed is empty; the default
            // window reaches back over it.
            assertThat(api.getStatus(seller.token(),
                    base + "?from=2020-01-01T00:00:00Z&to=2020-01-08T00:00:00Z")).isEqualTo(200);

            // Backwards is a caller mistake, not an empty result.
            assertThat(api.getStatus(seller.token(),
                    base + "?from=2026-09-10T00:00:00Z&to=2026-09-01T00:00:00Z")).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("submission")
    class Submission {

        @Test
        @DisplayName("one order per supplier, each with its own snapshotted deadline")
        void splitsBySupplier() throws Exception {
            var buyer = newBuyer();
            var one = newSeller("ABC Foods");
            var two = newSeller("XYZ Traders");

            // Different SLAs, so the snapshot is visibly per-store. Arranged
            // directly: a supplier cannot set their own answer window any more —
            // it is an operations setting (doc 13) — and this test is about the
            // order splitting, not about who may change an SLA.
            jdbc.update("update supplier_store set response_sla_seconds = 120 where id = ?",
                    two.storeId());

            addToCart(buyer, stockOffer(one, TestCatalog.freshProduct(jdbc, "p1"), "A", "100"), 2);
            long cartId = addToCart(buyer,
                    stockOffer(two, TestCatalog.freshProduct(jdbc, "p2"), "B", "200"), 3)
                    .get("id").asLong();

            validate(buyer, cartId, false);
            var submitted = submit(buyer, cartId, UUID.randomUUID().toString());
            var orders = submitted.at("/data/supplierOrders");

            assertThat(orders).hasSize(2);
            assertThat(orders).allSatisfy(order -> {
                // Drafts until they are paid for — a supplier must never see an
                // order the restaurant has not funded (guardrail 16).
                assertThat(order.get("status").asText()).isEqualTo("DRAFT");
                assertThat(order.get("orderNumber").asText()).startsWith("MP-");
                assertThat(order.get("acceptanceDeadline").isNull())
                        .describedAs("no clock runs while the customer is still in checkout")
                        .isTrue();
                assertThat(order.get("acceptedAmount").asDouble())
                        .describedAs("nothing is accepted until the supplier answers")
                        .isZero();
            });
            // One payment per supplier order, not one per checkout (D-010).
            assertThat(submitted.at("/data/paymentIntents")).hasSize(2);

            checkout.payAll(buyer.token(), submitted);

            var orderIds = new java.util.ArrayList<Long>();
            orders.forEach(order -> orderIds.add(order.get("id").asLong()));

            var released = orderIds.stream()
                    .map(id -> {
                        try {
                            return api.get(buyer.token(), "/api/v1/supplier-orders/" + id)
                                    .at("/data");
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .toList();

            assertThat(released).allSatisfy(order -> {
                assertThat(order.get("status").asText()).isEqualTo("PENDING_ACCEPTANCE");
                // The authoritative deadline the countdown reads (§23A.34). It
                // starts when the supplier can first see the order, not when the
                // restaurant pressed submit.
                assertThat(order.get("acceptanceDeadline").isNull()).isFalse();
            });

            var slas = released.stream()
                    .map(order -> order.get("responseSlaSeconds").asText())
                    .toList();
            assertThat(slas).containsExactlyInAnyOrder("60", "120");
        }

        @Test
        @DisplayName("order numbers are unique")
        void orderNumbersAreUnique() throws Exception {
            var buyer = newBuyer();
            var one = newSeller("ABC Foods");
            var two = newSeller("XYZ Traders");
            addToCart(buyer, stockOffer(one, TestCatalog.freshProduct(jdbc, "p1"), "A", "100"), 2);
            long cartId = addToCart(buyer,
                    stockOffer(two, TestCatalog.freshProduct(jdbc, "p2"), "B", "200"), 3)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            var orders = submit(buyer, cartId, UUID.randomUUID().toString())
                    .at("/data/supplierOrders");

            assertThat(orders.findValuesAsText("orderNumber")).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a repeated submit with the same key does not place a second order")
        void submitIsIdempotent() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            String key = UUID.randomUUID().toString();
            var first = submit(buyer, cartId, key).at("/data/supplierOrders");
            var second = submit(buyer, cartId, key).at("/data/supplierOrders");

            // Doc 04 §21. A duplicate would place a second real order with a real
            // supplier, who would then prepare goods nobody asked for twice.
            assertThat(first.get(0).get("orderNumber").asText())
                    .isEqualTo(second.get(0).get("orderNumber").asText());

            Integer count = jdbc.queryForObject(
                    "select count(*) from supplier_order where procurement_id = ?",
                    Integer.class, cartId);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("a retry with a different key still does not duplicate")
        void differentKeyStillDoesNotDuplicate() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            submit(buyer, cartId, UUID.randomUUID().toString());
            // A client that crashed and retried often generates a fresh key. The
            // procurement's own state, and uk_supplier_order_procurement_store
            // underneath it, are what make that safe.
            var retried = submit(buyer, cartId, UUID.randomUUID().toString());

            assertThat(retried.at("/data/supplierOrders")).hasSize(1);
            Integer count = jdbc.queryForObject(
                    "select count(*) from supplier_order where procurement_id = ?",
                    Integer.class, cartId);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("a stale validation is refused rather than trusted")
        void staleValidationIsRefused() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            // Age the validation past its TTL. Doc 01 §11 requires revalidation at
            // checkout, and this is where "too long ago" becomes a refusal.
            jdbc.update("update procurement set validated_at = date_sub(utc_timestamp(6), "
                    + "interval 1 hour) where id = ?", cartId);

            assertThat(submitStatus(buyer, cartId, UUID.randomUUID().toString())).isEqualTo(422);
        }

        @Test
        @DisplayName("a supplier going offline between validation and submit fails recoverably")
        void offlineBetweenValidateAndSubmit() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);

            // The client saw submittable: true a moment ago. Guardrail 4 — that is
            // not the authority, so submission re-checks.
            api.patchStatus(seller.token(), "/api/v1/supplier-stores/" + seller.storeId(),
                    Map.of("status", "OFFLINE"));

            assertThat(submitStatus(buyer, cartId, UUID.randomUUID().toString())).isEqualTo(422);

            // FAILED, not cancelled — doc 03 §4 wants the cart to survive so the
            // restaurant revalidates rather than rebuilding it.
            String status = jdbc.queryForObject(
                    "select status from procurement where id = ?", String.class, cartId);
            assertThat(status).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_order where procurement_id = ?",
                    Integer.class, cartId)).isZero();
        }
    }

    // ── Requirements ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("requirements")
    class Requirements {

        @Test
        @DisplayName("a requirement tracks requested, fulfilled and remaining separately")
        void tracksRemaining() throws Exception {
            var buyer = newBuyer();
            long productId = TestCatalog.freshProduct(jdbc, "paneer");

            var requirement = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/requirements", Map.of(
                            "items", java.util.List.of(Map.of(
                                    "canonicalProductId", productId,
                                    "quantity", 20,
                                    "unit", "KG")))).at("/data");

            var item = requirement.get("items").get(0);
            assertThat(requirement.get("status").asText()).isEqualTo("OPEN");
            assertThat(item.get("requestedQuantity").asDouble()).isEqualTo(20.0);
            assertThat(item.get("fulfilledQuantity").asDouble()).isZero();
            // §23A.14 requires this to be explicit rather than computed on the device.
            assertThat(item.get("remainingQuantity").asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("submitting against a requirement does not consume it")
        void submittingDoesNotConsumeTheRequirement() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            long productId = TestCatalog.freshProduct(jdbc, "paneer");
            long offerId = stockOffer(seller, productId, "PNR", "410");

            var requirement = api.post(buyer.token(),
                    "/api/v1/outlets/" + buyer.outletId() + "/requirements", Map.of(
                            "items", java.util.List.of(Map.of(
                                    "canonicalProductId", productId,
                                    "quantity", 20,
                                    "unit", "KG")))).at("/data");
            long requirementItemId = requirement.get("items").get(0).get("id").asLong();

            api.post(buyer.token(), "/api/v1/outlets/" + buyer.outletId() + "/cart/items",
                    Map.of("supplierOfferId", offerId, "quantity", 20,
                            "requirementItemId", requirementItemId));

            long cartId = jdbc.queryForObject(
                    "select id from procurement where outlet_id = ? order by id desc limit 1",
                    Long.class, buyer.outletId());
            validate(buyer, cartId, false);
            submit(buyer, cartId, UUID.randomUUID().toString());

            var after = api.get(buyer.token(),
                    "/api/v1/requirements/" + requirement.get("id").asLong()).at("/data");

            // Guardrail 14. Placing an order is a hope, not a fulfilment: quantity
            // is credited only when a supplier accepts. If it were decremented here,
            // a rejected order would look fulfilled and the need would vanish.
            assertThat(after.get("items").get(0).get("fulfilledQuantity").asDouble()).isZero();
            assertThat(after.get("items").get(0).get("remainingQuantity").asDouble()).isEqualTo(20.0);
            assertThat(after.get("status").asText()).isEqualTo("SOURCING");
        }

        @Test
        @DisplayName("another outlet's requirement is not reachable")
        void requirementsAreScoped() throws Exception {
            var mine = newBuyer();
            var stranger = newBuyer();
            long productId = TestCatalog.freshProduct(jdbc, "paneer");

            long requirementId = api.post(mine.token(),
                    "/api/v1/outlets/" + mine.outletId() + "/requirements", Map.of(
                            "items", java.util.List.of(Map.of(
                                    "canonicalProductId", productId,
                                    "quantity", 20, "unit", "KG")))).at("/data/id").asLong();

            assertThat(api.getStatus(stranger.token(), "/api/v1/requirements/" + requirementId))
                    .isEqualTo(404);
        }
    }

    // ── Scope ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("an order is visible to its restaurant and its supplier, and nobody else")
        void orderVisibleToBothSides() throws Exception {
            var buyer = newBuyer();
            var seller = newSeller("ABC Foods");
            var stranger = newBuyer();

            long cartId = addToCart(buyer,
                    stockOffer(seller, TestCatalog.freshProduct(jdbc, "paneer"), "PNR", "410"), 10)
                    .get("id").asLong();
            validate(buyer, cartId, false);
            long orderId = submit(buyer, cartId, UUID.randomUUID().toString())
                    .at("/data/supplierOrders/0/id").asLong();

            assertThat(api.getStatus(buyer.token(), "/api/v1/supplier-orders/" + orderId))
                    .describedAs("the restaurant that placed it").isEqualTo(200);
            assertThat(api.getStatus(seller.token(), "/api/v1/supplier-orders/" + orderId))
                    .describedAs("the supplier that received it").isEqualTo(200);
            assertThat(api.getStatus(stranger.token(), "/api/v1/supplier-orders/" + orderId))
                    .describedAs("an unrelated restaurant").isEqualTo(404);
        }
    }
}
