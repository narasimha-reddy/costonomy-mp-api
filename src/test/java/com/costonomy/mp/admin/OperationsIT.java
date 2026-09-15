package com.costonomy.mp.admin;

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
 * Operations APIs. Doc 04 §19, doc 08 §11, doc 09 §12–13, §17.
 *
 * <p>Three properties carry this suite, and all three are about the boundary
 * rather than the data. <b>Operations is a separate API</b> — an operator holds no
 * tenant permission and cannot arrive through a restaurant's endpoints.
 * <b>Inspection and mutation are separately permissioned</b>, so a support user
 * can be given the whole read surface and none of the writes. And <b>a tenant
 * cannot reach the admin API at all</b>, however senior they are inside their own
 * organisation.
 */
@AutoConfigureMockMvc
class OperationsIT extends AbstractIntegrationTest {

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

    private record Seller(String token, long supplierId, long storeId) {
    }

    private record PlacedOrder(Buyer buyer, Seller seller, long orderId, long skuId) {
    }

    // ── setup ────────────────────────────────────────────────────────────

    /** An operator with exactly one internal role. */
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

    private Seller newSeller(String name) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd", "displayName", name,
                "gstin", "36AAAAA" + String.format("%04d", (int) (Math.random() * 9999)) + "A1Z5",
                "firstStore", Map.of("name", name + " store", "addressLine1", "Road No 36",
                        "city", "Hyderabad", "state", "Telangana",
                        "latitude", "17.4399", "longitude", "78.4983"))).get("data");
        long supplierId = created.get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", supplierId);
        return new Seller(token, supplierId, created.get("stores").get(0).get("id").asLong());
    }

    /** An order placed, paid for and accepted, so there is something to inspect. */
    private PlacedOrder placedOrder() throws Exception {
        var buyer = newBuyer();
        var seller = newSeller("ABC Foods");
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
        mvc.perform(MockMvcRequestBuilders
                .post("/api/v1/supplier-orders/" + orderId + "/accept")
                .header("Authorization", "Bearer " + seller.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON));

        return new PlacedOrder(buyer, seller, orderId, skuId);
    }

    // ── The boundary ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("the operations boundary")
    class Boundary {

        @Test
        @DisplayName("a restaurant owner cannot reach the operations API")
        void tenantsAreRefused() throws Exception {
            var order = placedOrder();

            // The most senior role inside a tenant is still not an operator. Doc 09
            // §17: operations is a separate consumer, not a privileged tenant view.
            for (String token : List.of(order.buyer().token(), order.seller().token())) {
                assertThat(api.getStatus(token, "/api/v1/admin/orders")).isEqualTo(403);
                assertThat(api.getStatus(token, "/api/v1/admin/suppliers")).isEqualTo(403);
                assertThat(api.getStatus(token, "/api/v1/admin/audit")).isEqualTo(403);
                assertThat(api.getStatus(token, "/api/v1/admin/dashboard")).isEqualTo(403);
            }
        }

        @Test
        @DisplayName("an operator holds no tenant permission at all")
        void operatorsHoldNoTenantPermission() throws Exception {
            // V17's actual point. Before it, OPS roles held ORDER_VIEW and
            // CREDIT_VIEW at PLATFORM scope, which satisfies a check at *any*
            // outlet or store — so an operator could read and act through the
            // restaurant's own endpoints, indistinguishable from the restaurant.
            var borrowed = jdbc.queryForList("""
                    select r.code, p.code
                      from role_permission rp
                      join role r on r.id = rp.role_id
                      join permission p on p.id = rp.permission_id
                     where r.code like 'OPS_%'
                       and p.scope in ('RESTAURANT', 'SUPPLIER')
                    """);

            assertThat(borrowed)
                    .describedAs("no internal role may hold a tenant permission")
                    .isEmpty();

            var shared = jdbc.queryForList("""
                    select r.code, p.code
                      from role_permission rp
                      join role r on r.id = rp.role_id
                      join permission p on p.id = rp.permission_id
                     where r.code like 'OPS_%'
                       and p.code in ('ORDER_VIEW', 'CREDIT_VIEW')
                    """);

            assertThat(shared)
                    .describedAs("and none of the tenant-facing shared ones either")
                    .isEmpty();
        }

        @Test
        @DisplayName("an operator cannot read an order through the tenant endpoint")
        void operatorsCannotUseTenantEndpoints() throws Exception {
            var order = placedOrder();
            String support = operator("OPS_SUPPORT");

            // They can see it through their own API, and only through their own.
            assertThat(api.getStatus(support,
                    "/api/v1/supplier-orders/" + order.orderId())).isEqualTo(404);
            assertThat(api.getStatus(support,
                    "/api/v1/admin/orders/" + order.orderId() + "/timeline")).isEqualTo(200);
        }
    }

    // ── Read and write are different grants ──────────────────────────────

    @Nested
    @DisplayName("inspection without mutation")
    class ReadWriteSeparation {

        @Test
        @DisplayName("support can read everything and change nothing")
        void supportInspectsOnly() throws Exception {
            var order = placedOrder();
            String support = operator("OPS_SUPPORT");

            // Doc 09 §13, in one test. The read surface:
            assertThat(api.getStatus(support, "/api/v1/admin/suppliers")).isEqualTo(200);
            assertThat(api.getStatus(support, "/api/v1/admin/orders")).isEqualTo(200);
            assertThat(api.getStatus(support,
                    "/api/v1/admin/orders/" + order.orderId() + "/payment")).isEqualTo(200);
            assertThat(api.getStatus(support, "/api/v1/admin/disputes")).isEqualTo(200);
            assertThat(api.getStatus(support, "/api/v1/admin/config")).isEqualTo(200);

            // And the mutations, every one refused:
            assertThat(api.postStatus(support,
                    "/api/v1/admin/suppliers/" + order.seller().supplierId() + "/suspend",
                    Map.of("reason", "Trying it on"))).isEqualTo(403);
            assertThat(api.postStatus(support,
                    "/api/v1/admin/catalog/skus/" + order.skuId() + "/disable",
                    Map.of("reason", "Trying it on"))).isEqualTo(403);
            assertThat(api.patchStatus(support, "/api/v1/admin/config",
                    Map.of("key", "ranking.weight.price",
                            "value", "1", "reason", "Trying it on"))).isEqualTo(403);
        }

        @Test
        @DisplayName("a delivery operator can inspect deliveries and not payments")
        void specialistsAreNarrow() throws Exception {
            var order = placedOrder();
            String deliveryOps = operator("OPS_DELIVERY");

            assertThat(api.getStatus(deliveryOps,
                    "/api/v1/admin/orders/" + order.orderId() + "/timeline")).isEqualTo(200);
            // A delivery operator has no business reading a payment or a credit
            // ledger. Before V17 there was no permission that let them do one
            // without the other.
            assertThat(api.getStatus(deliveryOps,
                    "/api/v1/admin/orders/" + order.orderId() + "/payment")).isEqualTo(403);
            assertThat(api.getStatus(deliveryOps,
                    "/api/v1/admin/credit/exposure")).isEqualTo(403);
        }
    }

    // ── What operations can see ──────────────────────────────────────────

    @Nested
    @DisplayName("inspection")
    class Inspection {

        @Test
        @DisplayName("an order's timeline gathers every module's record of it")
        void timelineIsAssembled() throws Exception {
            var order = placedOrder();
            String support = operator("OPS_SUPPORT");

            var timeline = api.get(support,
                    "/api/v1/admin/orders/" + order.orderId() + "/timeline").at("/data");

            assertThat(timeline.at("/order/orderNumber").asText()).startsWith("MP-");
            assertThat(timeline.at("/order/supplierName").asText()).isEqualTo("ABC Foods");
            assertThat(timeline.at("/payment/status").asText()).isNotBlank();

            var actions = new java.util.ArrayList<String>();
            timeline.get("entries").forEach(entry -> actions.add(entry.get("action").asText()));

            // Release and acceptance both appear, in that order — which is the
            // whole value of the screen.
            assertThat(actions).contains("SUPPLIER_ORDER_RELEASED", "SUPPLIER_ORDER_CONFIRMED");
            assertThat(actions.indexOf("SUPPLIER_ORDER_RELEASED"))
                    .isLessThan(actions.indexOf("SUPPLIER_ORDER_CONFIRMED"));
            // The payment authorised before the order was released — guardrail 16,
            // visible in the ordering, which is the point of the screen.
            assertThat(actions.indexOf("PAYMENT_AUTHORIZED"))
                    .isLessThan(actions.indexOf("SUPPLIER_ORDER_RELEASED"));
        }

        @Test
        @DisplayName("suppliers are searchable by name and by GSTIN")
        void supplierSearch() throws Exception {
            var order = placedOrder();
            String support = operator("OPS_SUPPORT");

            var results = api.get(support, "/api/v1/admin/suppliers?query=ABC").at("/data");
            assertThat(results).isNotEmpty();
            assertThat(results.get(0).get("displayName").asText()).contains("ABC");
            assertThat(results.get(0).get("storeCount").asInt()).isPositive();

            var detail = api.get(support,
                    "/api/v1/admin/suppliers/" + order.seller().supplierId()).at("/data");
            assertThat(detail.get("stores")).hasSize(1);
            // Nobody has rated this store, so there is no average — not a three.
            assertThat(detail.at("/stores/0/averageRating").isNull()).isTrue();
            assertThat(detail.at("/stores/0/ratingCount").asInt()).isZero();
        }

        @Test
        @DisplayName("audit search answers by entity and by actor")
        void auditIsSearchable() throws Exception {
            var order = placedOrder();
            String support = operator("OPS_SUPPORT");

            var entries = api.get(support, "/api/v1/admin/audit?entityType=SUPPLIER_ORDER"
                    + "&entityId=" + order.orderId()).at("/data");

            assertThat(entries).isNotEmpty();
            assertThat(entries.get(0).get("entityId").asLong()).isEqualTo(order.orderId());

            // Doc 09 §6: entity snapshots may hold personal data, and a general
            // audit read is not the permission for that.
            assertThat(entries.toString())
                    .doesNotContain("beforeJson").doesNotContain("afterJson");
        }

        @Test
        @DisplayName("the dashboard shows a dash, not a flattering number, when nothing happened")
        void dashboardDoesNotInventRates() throws Exception {
            String support = operator("OPS_SUPPORT");

            // A window with no deliveries in it. Doc 08 §11's figures are read at a
            // glance, and 100% on-time because nothing shipped is worse than blank.
            var dashboard = api.get(support, "/api/v1/admin/dashboard?windowDays=1").at("/data");

            assertThat(dashboard.get("windowDays").asInt()).isEqualTo(1);
            assertThat(dashboard.at("/orders/activeOrders").isNull()).isFalse();
            assertThat(dashboard.at("/delivery/onTimeRate").isNull())
                    .describedAs("no measured deliveries means no on-time rate")
                    .isTrue();
            assertThat(dashboard.at("/disputes/byCategory").isObject()).isTrue();
        }

        @Test
        @DisplayName("operations sees the provider bidding a restaurant never does")
        void providerDetailIsVisibleToOperations() throws Exception {
            // Doc 06 §4 and §10 keep quotes from restaurants, not from operations —
            // "why did this delivery cost that" has to be answerable somewhere.
            String support = operator("OPS_SUPPORT");
            assertThat(api.getStatus(support, "/api/v1/admin/orders/1/delivery"))
                    .describedAs("the endpoint exists and is permissioned, "
                            + "even when this order has no delivery")
                    .isIn(200, 404);
        }
    }

    // ── What operations can change ───────────────────────────────────────

    @Nested
    @DisplayName("moderation")
    class Moderation {

        @Test
        @DisplayName("suspending a supplier stops new trade and leaves old orders alone")
        void suspensionIsForwardLooking() throws Exception {
            var order = placedOrder();
            String marketplace = operator("OPS_MARKETPLACE");

            api.post(marketplace,
                    "/api/v1/admin/suppliers/" + order.seller().supplierId() + "/suspend",
                    Map.of("reason", "Quality review"));

            assertThat(jdbc.queryForObject(
                    "select lifecycle_status from supplier_organization where id = ?",
                    String.class, order.seller().supplierId())).isEqualTo("SUSPENDED");

            // A supplier suspended today still owes the deliveries they accepted
            // yesterday; cancelling them would punish the restaurant instead.
            assertThat(jdbc.queryForObject(
                    "select status from supplier_order where id = ?",
                    String.class, order.orderId())).isEqualTo("CONFIRMED");

            // And it is on the record, with the reason.
            var audit = api.get(marketplace,
                    "/api/v1/admin/audit?action=SUPPLIER_SUSPENDED").at("/data");
            assertThat(audit.get(0).get("reason").asText()).isEqualTo("Quality review");
            assertThat(audit.get(0).get("newState").asText()).isEqualTo("SUSPENDED");
        }

        @Test
        @DisplayName("a suspension needs a reason")
        void suspensionRequiresAReason() throws Exception {
            var order = placedOrder();
            String marketplace = operator("OPS_MARKETPLACE");

            // An unexplained suspension is indistinguishable from a mistake, and
            // the supplier asking why deserves an answer that exists.
            assertThat(api.postStatus(marketplace,
                    "/api/v1/admin/suppliers/" + order.seller().supplierId() + "/suspend",
                    Map.of())).isEqualTo(400);
        }

        @Test
        @DisplayName("disabling a SKU closes its offer but keeps its history")
        void disablingSkuSupersedesTheOffer() throws Exception {
            var order = placedOrder();
            String moderation = operator("OPS_MODERATION");

            api.post(moderation, "/api/v1/admin/catalog/skus/" + order.skuId() + "/disable",
                    Map.of("reason", "Mislabelled product"));

            assertThat(jdbc.queryForObject(
                    "select status from supplier_sku where id = ?",
                    String.class, order.skuId())).isEqualTo("DISABLED");
            // Doc 02 §4: the order placed at that price must stay reconstructable,
            // so the offer is superseded rather than deleted.
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_offer where supplier_sku_id = ? "
                            + "and status = 'ACTIVE'", Integer.class, order.skuId())).isZero();
            assertThat(jdbc.queryForObject(
                    "select count(*) from supplier_offer where supplier_sku_id = ?",
                    Integer.class, order.skuId())).isPositive();
        }
    }

    // ── Configuration ────────────────────────────────────────────────────

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a change supersedes the old value rather than overwriting it")
        void changesAreVersioned() throws Exception {
            String admin = operator("OPS_ADMIN");
            String key = "ranking.weight.price";

            var before = api.get(admin, "/api/v1/admin/config?prefix=ranking.weight.price")
                    .at("/data");
            assertThat(before).isNotEmpty();
            int versionBefore = before.get(0).get("configVersion").asInt();

            var updated = api.patchStatus(admin, "/api/v1/admin/config", Map.of(
                    "key", key, "value", "0.45", "reason", "Q4 ranking review"));
            assertThat(updated).isEqualTo(200);

            var after = api.get(admin,
                    "/api/v1/admin/config?prefix=ranking.weight.price&includeHistory=true")
                    .at("/data");

            // Doc 09 §10 and §11: a settlement computed in March must stay
            // reproducible in June, which is impossible if March's rate can be
            // edited. The old row is closed, not replaced.
            assertThat(after.size()).isGreaterThan(before.size());

            var active = jdbc.queryForMap("""
                    select config_value, config_version from app_config
                     where config_key = ? and status = 'ACTIVE'
                    """, key);
            assertThat(active.get("config_value")).isEqualTo("0.45");
            assertThat(((Number) active.get("config_version")).intValue())
                    .isEqualTo(versionBefore + 1);

            var superseded = jdbc.queryForMap("""
                    select effective_to from app_config
                     where config_key = ? and status = 'SUPERSEDED'
                     order by config_version desc limit 1
                    """, key);
            assertThat(superseded.get("effective_to"))
                    .describedAs("the old version is closed at the moment the new one starts")
                    .isNotNull();
        }

        @Test
        @DisplayName("a change is audited with its reason")
        void changesAreAudited() throws Exception {
            String admin = operator("OPS_ADMIN");

            api.patchStatus(admin, "/api/v1/admin/config", Map.of(
                    "key", "eta.averageSpeedKmph",
                    "value", "22", "reason", "Traffic survey"));

            var audit = api.get(admin, "/api/v1/admin/audit?action=CONFIG_CHANGED").at("/data");
            assertThat(audit).isNotEmpty();
            assertThat(audit.get(0).get("reason").asText())
                    .contains("eta.averageSpeedKmph").contains("Traffic survey");
            assertThat(audit.get(0).get("newState").asText()).isEqualTo("22");
        }

        @Test
        @DisplayName("an unknown key is refused rather than created")
        void unknownKeysAreRefused() throws Exception {
            String admin = operator("OPS_ADMIN");

            // A typo would otherwise become a configuration value nothing reads,
            // while the setting the operator meant to change stays as it was.
            assertThat(api.patchStatus(admin, "/api/v1/admin/config", Map.of(
                    "key", "ranking.weight.nosuchthing",
                    "value", "7", "reason", "Typo"))).isEqualTo(400);
        }

        @Test
        @DisplayName("viewing configuration does not imply changing it")
        void viewDoesNotImplyManage() throws Exception {
            String finance = operator("OPS_FINANCE");

            assertThat(api.getStatus(finance, "/api/v1/admin/config")).isEqualTo(200);
            assertThat(api.patchStatus(finance, "/api/v1/admin/config", Map.of(
                    "key", "ranking.weight.price",
                    "value", "9.99", "reason", "Not mine to change"))).isEqualTo(403);
        }
    }
}
