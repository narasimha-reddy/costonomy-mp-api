package com.costonomy.mp.procurement;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order lifecycle after D-091, which is the one the product actually has.
 *
 * <p>This replaces {@code SupplierAcceptanceIT}. Every case in that suite was
 * about accepting, partially accepting, rejecting or expiring an order — four
 * things an order can no longer do, because the supplier commits on the request
 * and the restaurant pays against that commitment before an order exists.
 *
 * <p>What is worth asserting instead is that the order arrives agreed, that the
 * mode decides where it goes and who may take it there, and that a supplier who
 * cannot fulfil leaves a record saying it was them.
 */
@AutoConfigureMockMvc
class SupplierOrderLifecycleIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider paymentProvider;

    private ApiClient api;
    private TestOrder orders;
    private TestCheckout checkout;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        orders = new TestOrder(mvc, json, api);
        checkout = new TestCheckout(paymentProvider, api);
    }

    private record Buyer(String token, long outletId) {
    }

    private record Seller(String token, long storeId) {
    }

    private record Placed(Buyer buyer, Seller seller, TestOrder.Created order) {
    }

    // ── The shape of a funded order ──────────────────────────────────────

    @Nested
    @DisplayName("an order built from an accepted request")
    class Arrival {

        @Test
        @DisplayName("is confirmed once paid, never pending acceptance")
        void confirmedOnFunding() throws Exception {
            var placed = place(20, "PICKUP");

            // Before payment it is a draft, and the supplier cannot see it.
            assertThat(statusOf(placed.order().orderId())).isEqualTo("DRAFT");

            pay(placed);

            // The assertion this whole architecture is for: nobody is asked to
            // accept what they already accepted.
            assertThat(statusOf(placed.order().orderId())).isEqualTo("CONFIRMED");
            assertThat(jdbc.queryForObject(
                    "select acceptance_deadline from supplier_order where id = ?",
                    Object.class, placed.order().orderId()))
                    .as("no acceptance clock runs on an order nobody must answer")
                    .isNull();
        }

        @Test
        @DisplayName("carries the mode the restaurant chose")
        void carriesMode() throws Exception {
            var placed = place(20, "PICKUP");
            assertThat(jdbc.queryForObject(
                    "select delivery_mode from supplier_order where id = ?",
                    String.class, placed.order().orderId())).isEqualTo("PICKUP");
        }

        @Test
        @DisplayName("a pickup is carried free")
        void pickupIsFree() throws Exception {
            var placed = place(20, "PICKUP");
            assertThat(jdbc.queryForObject(
                    "select delivery_fee from supplier_order where id = ?",
                    java.math.BigDecimal.class, placed.order().orderId()).doubleValue())
                    .isZero();
        }
    }

    // ── Where it goes, and who may take it there ─────────────────────────

    @Nested
    @DisplayName("movement")
    class Movement {

        @Test
        @DisplayName("a pickup order completes when the restaurant collects it")
        void pickupCompletesOnCollection() throws Exception {
            var placed = place(20, "PICKUP");
            pay(placed);

            keyed(placed.seller().token(), orderPath(placed) + "/preparing", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/ready", Map.of());
            assertThat(statusOf(placed.order().orderId())).isEqualTo("READY_FOR_PICKUP");

            // Nothing delivered it, so it never passes through DELIVERED --
            // collection is received the same way a delivery is, which is what
            // lets a pickup record a shortfall at all.
            receive(placed, 20);

            assertThat(statusOf(placed.order().orderId())).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("a pickup order cannot be sent out for delivery")
        void pickupHasNoDeliveryLeg() throws Exception {
            var placed = place(20, "PICKUP");
            pay(placed);
            keyed(placed.seller().token(), orderPath(placed) + "/preparing", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/ready", Map.of());

            assertThat(keyedStatus(placed.seller().token(),
                    orderPath(placed) + "/out-for-delivery", Map.of()))
                    .as("nothing is being delivered")
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("a supplier carrying their own order may move it themselves")
        void supplierCarriesTheirOwn() throws Exception {
            var placed = place(20, "SUPPLIER_DELIVERY");
            pay(placed);

            keyed(placed.seller().token(), orderPath(placed) + "/preparing", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/ready", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/out-for-delivery", Map.of());
            assertThat(statusOf(placed.order().orderId())).isEqualTo("OUT_FOR_DELIVERY");

            keyed(placed.seller().token(), orderPath(placed) + "/delivered", Map.of());
            assertThat(statusOf(placed.order().orderId())).isEqualTo("DELIVERED");

            // Delivered is still not completed. The restaurant says what arrived.
            receive(placed, 20);
            assertThat(statusOf(placed.order().orderId())).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("a supplier cannot claim movement a courier is responsible for")
        void supplierCannotClaimCourierMovement() throws Exception {
            var placed = place(20, "COSTONOMY_DELIVERY");
            pay(placed);
            keyed(placed.seller().token(), orderPath(placed) + "/preparing", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/ready", Map.of());

            // §23A.38. The courier's events are the only evidence a van left, and
            // a button is not evidence.
            assertThat(keyedStatus(placed.seller().token(),
                    orderPath(placed) + "/out-for-delivery", Map.of()))
                    .isEqualTo(403);
        }
    }

    // ── Backing out ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("a supplier who cannot fulfil cancels, and the record says it was them")
        void supplierCancelIsAttributed() throws Exception {
            var placed = place(20, "PICKUP");
            pay(placed);

            assertThat(keyedStatus(placed.seller().token(),
                    orderPath(placed) + "/supplier-cancel", Map.of("reason", "OUT_OF_STOCK")))
                    .as("a supplier must be able to cancel an order they cannot fulfil")
                    .isEqualTo(200);

            assertThat(statusOf(placed.order().orderId())).isEqualTo("CANCELLED");
            // The whole reason this is an attribute rather than a status: a
            // reliability figure has to know whose decision it was.
            assertThat(jdbc.queryForObject(
                    "select cancelled_by from supplier_order where id = ?",
                    String.class, placed.order().orderId())).isEqualTo("SUPPLIER");
        }

        @Test
        @DisplayName("a restaurant cancelling is recorded as the restaurant")
        void restaurantCancelIsAttributed() throws Exception {
            var placed = place(20, "PICKUP");
            pay(placed);

            keyed(placed.buyer().token(), orderPath(placed) + "/cancel",
                    Map.of("reason", "Changed our minds"));

            assertThat(jdbc.queryForObject(
                    "select cancelled_by from supplier_order where id = ?",
                    String.class, placed.order().orderId())).isEqualTo("RESTAURANT");
        }

        @Test
        @DisplayName("nothing is cancellable once the goods have left")
        void noCancellationPastPickup() throws Exception {
            var placed = place(20, "SUPPLIER_DELIVERY");
            pay(placed);
            keyed(placed.seller().token(), orderPath(placed) + "/preparing", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/ready", Map.of());
            keyed(placed.seller().token(), orderPath(placed) + "/out-for-delivery", Map.of());

            // Doc 01 §13: the path from here is return or dispute.
            assertThat(keyedStatus(placed.buyer().token(), orderPath(placed) + "/cancel",
                    Map.of("reason", "Too late"))).isEqualTo(409);
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private Placed place(int quantity, String mode) throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("ABC Foods");
        long skuId = listSku(seller);

        // Both modes have to be permitted for the store, or choosing one is
        // refused before anything else is tested.
        jdbc.update("""
                insert into supplier_delivery_policy
                    (supplier_store_id, own_delivery_enabled, costonomy_delivery_enabled,
                     own_delivery_fee, created_at, updated_at, version)
                values (?, 1, 1, 60, now(6), now(6), 0)
                on duplicate key update own_delivery_enabled = 1,
                     costonomy_delivery_enabled = 1
                """, seller.storeId());

        var created = orders.place(buyer.token(), buyer.outletId(), seller.token(),
                skuId, quantity, quantity, mode, null, null);
        return new Placed(buyer, seller, created);
    }

    private void pay(Placed placed) throws Exception {
        checkout.pay(placed.buyer().token(), placed.order().paymentId(),
                placed.order().providerOrderId());
    }

    /** Confirm what arrived, which is what finishes an order either way. */
    private void receive(Placed placed, int received) throws Exception {
        long itemId = jdbc.queryForObject(
                "select id from supplier_order_item where supplier_order_id = ?",
                Long.class, placed.order().orderId());

        keyed(placed.buyer().token(),
                "/api/v1/supplier-orders/" + placed.order().orderId() + "/receive",
                Map.of("items", List.of(Map.of(
                        "supplierOrderItemId", itemId,
                        "receivedQuantity", received,
                        "damagedQuantity", 0,
                        "missingQuantity", 0))));
    }

    private String orderPath(Placed placed) {
        return "/api/v1/supplier-orders/" + placed.order().orderId();
    }

    private String statusOf(long orderId) {
        return jdbc.queryForObject(
                "select status from supplier_order where id = ?", String.class, orderId);
    }

    private Buyer newBuyer() throws Exception {
        String token = api.login(ApiClient.freshPhone());
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                        "name", "Paradise",
                        "firstOutlet", Map.of("name", "Banjara Hills", "addressLine1", "Road No 12",
                                "city", "Hyderabad", "state", "Telangana", "pincode", "500034",
                                "latitude", "17.4156", "longitude", "78.4347")))
                .at("/data/outlets/0/id").asLong();
        return new Buyer(token, outletId);
    }

    private Seller newSeller(String name) throws Exception {
        String token = api.login(ApiClient.freshPhone());
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd", "displayName", name,
                "firstStore", Map.of("name", name + " store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");

        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", created.get("id").asLong());
        TestCatalog.tradesAroundTheClock(jdbc, created.get("id").asLong());

        return new Seller(token, created.get("stores").get(0).get("id").asLong());
    }

    private long listSku(Seller seller) throws Exception {
        long productId = TestCatalog.freshProduct(jdbc, "paneer");
        return api.post(seller.token(),
                        "/api/v1/supplier-stores/" + seller.storeId() + "/skus",
                        Map.of("canonicalProductId", productId, "skuCode", "PNR-" + productId,
                                "name", "Paneer", "packSize", 1, "packUnit", "KG",
                                "sellingPrice", "410", "gstRate", "5"))
                .at("/data/id").asLong();
    }

    private JsonNode keyed(String token, String path, Object body) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString());
    }

    private int keyedStatus(String token, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus();
    }
}
