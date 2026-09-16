package com.costonomy.mp.access;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tenant isolation and the scope hierarchy.
 *
 * <p>Doc 03 §16: <em>"A valid permission does not permit access to an unrelated
 * restaurant, outlet, supplier or order."</em> This is the suite that makes that
 * sentence true rather than aspirational, and it is the one to extend whenever a
 * new scoped resource is added.
 */
@AutoConfigureMockMvc
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private JsonNode createRestaurant(String token, String name) throws Exception {
        return api.post(token, "/api/v1/restaurants", Map.of(
                "name", name,
                "firstOutlet", Map.of(
                        "name", name + " — Main",
                        "addressLine1", "Road No 12",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "pincode", "500034"))).get("data");
    }

    private JsonNode createSupplier(String token, String name) throws Exception {
        return api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd",
                "displayName", name,
                "firstStore", Map.of(
                        "name", name + " — Jubilee Hills",
                        "addressLine1", "Road No 36",
                        "city", "Hyderabad",
                        "state", "Telangana"))).get("data");
    }

    // ── Restaurants ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("restaurants")
    class Restaurants {

        @Test
        @DisplayName("the creator becomes owner and can read back what they created")
        void creatorBecomesOwner() throws Exception {
            String token = api.loginFresh();
            JsonNode restaurant = createRestaurant(token, "Paradise");

            assertThat(restaurant.get("id").asLong()).isPositive();
            assertThat(restaurant.get("outlets")).hasSize(1);

            // Not a trivial assertion: without the owner grant written in the same
            // transaction, the creator could not read back their own restaurant,
            // because every later call is authorized against user_role.
            assertThat(api.getStatus(token, "/api/v1/restaurants/" + restaurant.get("id").asLong()))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("another restaurant's data is reported as not found, not forbidden")
        void foreignRestaurantLooksLikeItDoesNotExist() throws Exception {
            String ownerA = api.loginFresh();
            String ownerB = api.loginFresh();

            long restaurantA = createRestaurant(ownerA, "Paradise").get("id").asLong();
            createRestaurant(ownerB, "Bawarchi");

            // Doc 09 §3. If this were 403, owner B could walk the id space and
            // learn exactly which restaurants exist on the platform.
            assertThat(api.getStatus(ownerB, "/api/v1/restaurants/" + restaurantA))
                    .describedAs("must not distinguish 'exists but not yours' from 'does not exist'")
                    .isEqualTo(404);

            // And the response for a genuinely absent id is identical.
            assertThat(api.getStatus(ownerB, "/api/v1/restaurants/99999999")).isEqualTo(404);
        }

        @Test
        @DisplayName("another restaurant's outlet cannot be read or edited")
        void foreignOutletIsUnreachable() throws Exception {
            String ownerA = api.loginFresh();
            String ownerB = api.loginFresh();

            long outletA = createRestaurant(ownerA, "Paradise")
                    .get("outlets").get(0).get("id").asLong();
            createRestaurant(ownerB, "Bawarchi");

            assertThat(api.getStatus(ownerB, "/api/v1/outlets/" + outletA)).isEqualTo(404);
            assertThat(api.patchStatus(ownerB, "/api/v1/outlets/" + outletA,
                    Map.of("name", "Hijacked"))).isEqualTo(404);

            // And the owner still can — the check is about scope, not a blanket deny.
            assertThat(api.getStatus(ownerA, "/api/v1/outlets/" + outletA)).isEqualTo(200);
        }

        @Test
        @DisplayName("a restaurant-level role reaches every outlet, including new ones")
        void restaurantRoleInheritsToOutlets() throws Exception {
            String owner = api.loginFresh();
            long restaurantId = createRestaurant(owner, "Paradise").get("id").asLong();

            // Created after the owner's grant, so this only works if the hierarchy
            // is evaluated at check time rather than grants being expanded once.
            long newOutlet = api.post(owner, "/api/v1/restaurants/" + restaurantId + "/outlets",
                    Map.of("name", "Secunderabad",
                            "addressLine1", "SP Road",
                            "city", "Hyderabad",
                            "state", "Telangana")).at("/data/id").asLong();

            assertThat(api.getStatus(owner, "/api/v1/outlets/" + newOutlet)).isEqualTo(200);
        }

        @Test
        @DisplayName("an outlet-level member cannot reach a sibling outlet")
        void outletRoleDoesNotInheritSideways() throws Exception {
            String owner = api.loginFresh();
            JsonNode restaurant = createRestaurant(owner, "Paradise");
            long restaurantId = restaurant.get("id").asLong();
            long outletOne = restaurant.get("outlets").get(0).get("id").asLong();

            long outletTwo = api.post(owner, "/api/v1/restaurants/" + restaurantId + "/outlets",
                    Map.of("name", "Secunderabad",
                            "addressLine1", "SP Road",
                            "city", "Hyderabad",
                            "state", "Telangana")).at("/data/id").asLong();

            String staffPhone = ApiClient.freshPhone();
            api.post(owner, "/api/v1/outlets/" + outletOne + "/users", Map.of(
                    "phone", staffPhone,
                    "roleCode", "REST_STORE_MANAGER",
                    "name", "Ravi"));

            String staff = api.login(staffPhone);

            assertThat(api.getStatus(staff, "/api/v1/outlets/" + outletOne))
                    .describedAs("their own outlet")
                    .isEqualTo(200);
            assertThat(api.getStatus(staff, "/api/v1/outlets/" + outletTwo))
                    .describedAs("a sibling outlet they were not granted")
                    .isEqualTo(404);
            assertThat(api.getStatus(staff, "/api/v1/restaurants/" + restaurantId))
                    .describedAs("the parent restaurant")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a role grant cannot be used to escalate out of the restaurant world")
        void cannotGrantForeignRoles() throws Exception {
            String owner = api.loginFresh();
            long outletId = createRestaurant(owner, "Paradise")
                    .get("outlets").get(0).get("id").asLong();

            // Without the REST_ prefix check, an outlet admin could grant SUP_OWNER
            // or an internal operations role and leave their own tenant entirely.
            for (String role : new String[] {"SUP_OWNER", "OPS_ADMIN", "OPS_MARKETPLACE"}) {
                assertThat(api.postStatus(owner, "/api/v1/outlets/" + outletId + "/users", Map.of(
                        "phone", ApiClient.freshPhone(),
                        "roleCode", role)))
                        .describedAs("granting %s on an outlet", role)
                        .isEqualTo(400);
            }
        }
    }

    // ── Suppliers ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("suppliers")
    class Suppliers {

        @Test
        @DisplayName("another supplier's store cannot be read or edited")
        void foreignStoreIsUnreachable() throws Exception {
            String supplierA = api.loginFresh();
            String supplierB = api.loginFresh();

            long storeA = createSupplier(supplierA, "ABC Foods")
                    .get("stores").get(0).get("id").asLong();
            createSupplier(supplierB, "XYZ Traders");

            assertThat(api.getStatus(supplierB, "/api/v1/supplier-stores/" + storeA)).isEqualTo(404);
            // Denial is 404, not 403: a competitor must not be able to learn which
            // store ids exist by reading the status code (doc 09 §3). The patch
            // carries an ordinary field — the answer window used to be here and is
            // now an operations setting, so sending it would be refused as a
            // malformed body before the scope check ever ran, and this test would
            // pass for the wrong reason.
            assertThat(api.patchStatus(supplierB, "/api/v1/supplier-stores/" + storeA,
                    Map.of("name", "Renamed by a competitor"))).isEqualTo(404);
        }

        @Test
        @DisplayName("a restaurant user cannot reach supplier endpoints, and vice versa")
        void worldsDoNotCross() throws Exception {
            String restaurateur = api.loginFresh();
            String supplier = api.loginFresh();

            long restaurantId = createRestaurant(restaurateur, "Paradise").get("id").asLong();
            long supplierId = createSupplier(supplier, "ABC Foods").get("id").asLong();

            assertThat(api.getStatus(restaurateur, "/api/v1/suppliers/" + supplierId))
                    .describedAs("a restaurant owner reading a supplier")
                    .isEqualTo(404);
            assertThat(api.getStatus(supplier, "/api/v1/restaurants/" + restaurantId))
                    .describedAs("a supplier reading a restaurant")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a store-scoped member cannot reach a sibling store")
        void storeRoleDoesNotInheritSideways() throws Exception {
            String owner = api.loginFresh();
            JsonNode supplier = createSupplier(owner, "ABC Foods");
            long supplierId = supplier.get("id").asLong();
            long storeOne = supplier.get("stores").get(0).get("id").asLong();

            long storeTwo = api.post(owner, "/api/v1/suppliers/" + supplierId + "/stores",
                    Map.of("name", "Secunderabad depot",
                            "addressLine1", "SP Road",
                            "city", "Hyderabad",
                            "state", "Telangana")).at("/data/id").asLong();

            String salesPhone = ApiClient.freshPhone();
            api.post(owner, "/api/v1/suppliers/" + supplierId + "/users", Map.of(
                    "phone", salesPhone,
                    "roleCode", "SUP_SALESPERSON",
                    "storeId", storeOne));

            String sales = api.login(salesPhone);

            assertThat(api.getStatus(sales, "/api/v1/supplier-stores/" + storeOne)).isEqualTo(200);
            assertThat(api.getStatus(sales, "/api/v1/supplier-stores/" + storeTwo)).isEqualTo(404);
        }

        @Test
        @DisplayName("a store id belonging to another supplier cannot be used to place a grant")
        void cannotGrantOntoAnotherSuppliersStore() throws Exception {
            String supplierA = api.loginFresh();
            String supplierB = api.loginFresh();

            long storeA = createSupplier(supplierA, "ABC Foods")
                    .get("stores").get(0).get("id").asLong();
            long supplierBId = createSupplier(supplierB, "XYZ Traders").get("id").asLong();

            // Supplier B is authorized on their own organisation, so the org-level
            // check passes — the store must be re-checked against that organisation
            // or B could grant one of their staff access to A's store.
            assertThat(api.postStatus(supplierB, "/api/v1/suppliers/" + supplierBId + "/users", Map.of(
                    "phone", ApiClient.freshPhone(),
                    "roleCode", "SUP_SALESPERSON",
                    "storeId", storeA)))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a supplier cannot verify or activate itself")
        void supplierCannotSelfActivate() throws Exception {
            String owner = api.loginFresh();
            long supplierId = createSupplier(owner, "ABC Foods").get("id").asLong();

            JsonNode submitted = api.post(owner, "/api/v1/suppliers/" + supplierId + "/verification",
                    Map.of("verificationType", "GST",
                            "gstin", "36ABCDE1234F1Z5",
                            "legalName", "ABC Foods Pvt Ltd"));

            assertThat(submitted.at("/data/status").asText()).isEqualTo("PENDING");

            // Submitting does not verify. Doc 03 §2.
            JsonNode supplier = api.get(owner, "/api/v1/suppliers/" + supplierId);
            assertThat(supplier.at("/data/lifecycleStatus").asText()).isEqualTo("VERIFICATION_PENDING");
            assertThat(supplier.at("/data/canTrade").asBoolean()).isFalse();

            // And the activation endpoint needs a platform permission they do not hold.
            assertThat(api.postStatus(owner, "/api/v1/admin/suppliers/" + supplierId + "/activate",
                    Map.of())).isEqualTo(403);

            long verificationId = submitted.at("/data/id").asLong();
            assertThat(api.postStatus(owner,
                    "/api/v1/admin/suppliers/verifications/" + verificationId + "/review",
                    Map.of("approved", true))).isEqualTo(403);
        }

        @Test
        @DisplayName("an operator verifies, and the supplier still cannot trade until activated")
        void verificationAndActivationAreSeparate() throws Exception {
            String owner = api.loginFresh();
            long supplierId = createSupplier(owner, "ABC Foods").get("id").asLong();
            long verificationId = api.post(owner, "/api/v1/suppliers/" + supplierId + "/verification",
                    Map.of("verificationType", "GST",
                            "gstin", "36ZZZZZ1234F1Z5",
                            "legalName", "ABC Foods Pvt Ltd")).at("/data/id").asLong();

            String operator = grantPlatformOperator();

            api.post(operator, "/api/v1/admin/suppliers/verifications/" + verificationId + "/review",
                    Map.of("approved", true));

            JsonNode verified = api.get(owner, "/api/v1/suppliers/" + supplierId);
            assertThat(verified.at("/data/lifecycleStatus").asText()).isEqualTo("VERIFIED");
            assertThat(verified.at("/data/canTrade").asBoolean())
                    .describedAs("verified is not the same as allowed to trade")
                    .isFalse();

            api.post(operator, "/api/v1/admin/suppliers/" + supplierId + "/activate", Map.of());

            JsonNode active = api.get(owner, "/api/v1/suppliers/" + supplierId);
            assertThat(active.at("/data/lifecycleStatus").asText()).isEqualTo("ACTIVE");
            assertThat(active.at("/data/canTrade").asBoolean()).isTrue();
        }

        /** An internal operator. Granted directly, since there is no self-service path — by design. */
        private String grantPlatformOperator() throws Exception {
            String phone = ApiClient.freshPhone();
            String token = api.login(phone);
            Long userId = jdbc.queryForObject(
                    "select id from users where phone = ?", Long.class, "+91" + phone);
            jdbc.update("""
                    insert into user_role (user_id, role_id, scope_type, scope_id, status, granted_at,
                                           created_at, updated_at, version)
                    select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0
                      from role r where r.code = 'OPS_SUPPLIER_VERIFY'
                    """, userId);
            return token;
        }
    }

    // ── /auth/me ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("memberships")
    class Memberships {

        @Test
        @DisplayName("a new user has no memberships — the onboarding case, not an error")
        void newUserHasNone() throws Exception {
            assertThat(api.get(api.loginFresh(), "/api/v1/auth/me")
                    .at("/data/memberships")).isEmpty();
        }

        @Test
        @DisplayName("memberships tell the client which experience to show")
        void membershipsDriveRouting() throws Exception {
            String owner = api.loginFresh();
            createRestaurant(owner, "Paradise");

            JsonNode memberships = api.get(owner, "/api/v1/auth/me").at("/data/memberships");

            assertThat(memberships).hasSize(1);
            JsonNode membership = memberships.get(0);
            assertThat(membership.get("scopeType").asText()).isEqualTo("RESTAURANT");
            assertThat(membership.get("scopeName").asText()).isEqualTo("Paradise");
            assertThat(membership.get("roles").get(0).asText()).isEqualTo("REST_OWNER");
            // Resolved server-side so the client never computes what a role means.
            assertThat(membership.get("permissions").toString()).contains("PROCUREMENT_SUBMIT");
        }

        @Test
        @DisplayName("one user can belong to both worlds at once")
        void oneUserCanBeBothRestaurantAndSupplier() throws Exception {
            // A restaurant group that also wholesales to others is an ordinary
            // arrangement, and the identity model has to allow it.
            String token = api.loginFresh();
            createRestaurant(token, "Paradise");
            createSupplier(token, "Paradise Wholesale");

            JsonNode memberships = api.get(token, "/api/v1/auth/me").at("/data/memberships");

            assertThat(memberships).hasSize(2);
            assertThat(memberships.findValuesAsText("scopeType"))
                    .containsExactlyInAnyOrder("RESTAURANT", "SUPPLIER");
        }

        @Test
        @DisplayName("an outlet membership carries its restaurant, for grouping")
        void outletMembershipCarriesParent() throws Exception {
            String owner = api.loginFresh();
            long outletId = createRestaurant(owner, "Paradise")
                    .get("outlets").get(0).get("id").asLong();

            String staffPhone = ApiClient.freshPhone();
            api.post(owner, "/api/v1/outlets/" + outletId + "/users", Map.of(
                    "phone", staffPhone, "roleCode", "REST_PROCUREMENT_STAFF"));

            JsonNode membership = api.get(api.login(staffPhone), "/api/v1/auth/me")
                    .at("/data/memberships").get(0);

            assertThat(membership.get("scopeType").asText()).isEqualTo("OUTLET");
            // The outlet switcher (§23A.4) groups by restaurant, so it needs this
            // without a second call.
            assertThat(membership.get("parentScopeName").asText()).isEqualTo("Paradise");
        }

        @Test
        @DisplayName("revoking a grant takes effect on the next request, not when a cache expires")
        void revocationIsImmediate() throws Exception {
            String owner = api.loginFresh();
            long outletId = createRestaurant(owner, "Paradise")
                    .get("outlets").get(0).get("id").asLong();

            String staffPhone = ApiClient.freshPhone();
            api.post(owner, "/api/v1/outlets/" + outletId + "/users", Map.of(
                    "phone", staffPhone, "roleCode", "REST_STORE_MANAGER"));

            String staff = api.login(staffPhone);
            assertThat(api.getStatus(staff, "/api/v1/outlets/" + outletId)).isEqualTo(200);

            jdbc.update("""
                    update user_role ur join users u on u.id = ur.user_id
                       set ur.status = 'REVOKED'
                     where u.phone = ?
                    """, "+91" + staffPhone);

            // Doc 46: "permission revoked while screen open → server rejects".
            // Their access token is still valid — which is exactly why permissions
            // are not embedded in it.
            assertThat(api.getStatus(staff, "/api/v1/outlets/" + outletId))
                    .describedAs("the still-valid token must no longer grant access")
                    .isEqualTo(404);
        }
    }
}
