package com.costonomy.mp.discovery;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recommendation pipeline against real data. Doc 07 §3, §13, §14.
 *
 * <p>Scoring itself is unit-tested in {@code BestValueScorerTest}. What is tested
 * here is the half that needs a database: which offers survive filtering, and
 * whether an outlet that cannot be served is told so rather than shown an empty
 * list with no explanation.
 */
@AutoConfigureMockMvc
class RecommendationIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    // Two points in Hyderabad, about 7 km apart, and one 500 km away in Chennai.
    private static final String HYD_LAT = "17.4156";
    private static final String HYD_LON = "78.4347";
    private static final String NEARBY_LAT = "17.4399";
    private static final String NEARBY_LON = "78.4983";
    private static final String FAR_LAT = "13.0827";
    private static final String FAR_LON = "80.2707";

    private record Outlet(String token, long outletId) {
    }

    private record Store(String token, long supplierId, long storeId) {
    }

    private Outlet newOutlet() throws Exception {
        String token = api.loginFresh();
        long outletId = api.post(token, "/api/v1/restaurants", Map.of(
                "name", "Paradise",
                "firstOutlet", Map.of(
                        "name", "Banjara Hills",
                        "addressLine1", "Road No 12",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "pincode", "500034",
                        "latitude", HYD_LAT,
                        "longitude", HYD_LON)))
                .at("/data/outlets/0/id").asLong();
        return new Outlet(token, outletId);
    }

    private Store newStore(String name, String latitude, String longitude) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd",
                "displayName", name,
                "firstStore", Map.of(
                        "name", name + " store",
                        "addressLine1", "Road No 36",
                        "city", "Hyderabad",
                        "state", "Telangana",
                        "pincode", "500033",
                        "latitude", latitude,
                        "longitude", longitude,
                        "preparationMinutes", 45))).get("data");

        long supplierId = created.get("id").asLong();
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);

        return new Store(token, supplierId, created.get("stores").get(0).get("id").asLong());
    }

    private long stock(Store store, long productId, String code, String price) throws Exception {
        return api.post(store.token(), "/api/v1/supplier-stores/" + store.storeId() + "/skus",
                Map.of("canonicalProductId", productId,
                        "skuCode", code,
                        "name", code,
                        "packSize", 1,
                        "packUnit", "KG",
                        "sellingPrice", price,
                        "gstRate", "5")).at("/data/id").asLong();
    }

    /**
     * A canonical product for this test alone.
     *
     * <p>These tests assert exactly which offers come back, so they cannot share a
     * seeded product with every other test in the suite — see {@link TestCatalog}.
     */
    private long freshProduct(String label) {
        return TestCatalog.freshProduct(jdbc, label);
    }

    private JsonNode recommend(Outlet outlet, long productId, int quantity) throws Exception {
        return api.get(outlet.token(), "/api/v1/products/" + productId
                + "/recommendations?outletId=" + outlet.outletId() + "&quantity=" + quantity)
                .at("/data");
    }

    // ── Filtering ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("nearby suppliers are ranked with explanations and an ETA")
        void ranksNearbySuppliers() throws Exception {
            long paneer = freshProduct("paneer");
            var outlet = newOutlet();
            stock(newStore("ABC Foods", NEARBY_LAT, NEARBY_LON), paneer, "ABC-PNR", "410");
            stock(newStore("XYZ Traders", NEARBY_LAT, NEARBY_LON), paneer, "XYZ-PNR", "440");

            var result = recommend(outlet, paneer, 20);
            var offers = result.get("offers");

            assertThat(offers).hasSize(2);
            assertThat(result.get("unservedReason").isNull()).isTrue();

            var top = offers.get(0);
            // 20 kg at ₹410 = ₹8,200 plus 5% GST.
            assertThat(top.get("itemTotal").asDouble()).isEqualTo(8200.0);
            assertThat(top.get("gstAmount").asDouble()).isEqualTo(410.0);
            assertThat(top.get("effectiveTotal").asDouble()).isEqualTo(8610.0);
            assertThat(top.get("etaMinutes").asInt()).isPositive();
            assertThat(top.get("distanceKm").asDouble()).isBetween(1.0, 15.0);
            assertThat(top.get("explanations").toString()).contains("BEST_TOTAL_VALUE");
        }

        @Test
        @DisplayName("a supplier beyond its delivery radius is excluded")
        void farSupplierExcluded() throws Exception {
            long curd = freshProduct("curd");
            var outlet = newOutlet();
            var far = newStore("Chennai Dairy", FAR_LAT, FAR_LON);
            stock(far, curd, "CHN-CURD", "70");

            var result = recommend(outlet, curd, 10);

            assertThat(result.get("offers")).isEmpty();
            // §23A.15: the unmet item is explained, not silently dropped. And the
            // reason has to be specific enough to act on.
            assertThat(result.get("unservedReason").asText())
                    .contains("delivers to this outlet");
        }

        @Test
        @DisplayName("an offline store is excluded and the reason says so")
        void offlineStoreExcluded() throws Exception {
            long butter = freshProduct("butter");
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            stock(store, butter, "ABC-BUTTER", "520");

            assertThat(recommend(outlet, butter, 5).get("offers")).hasSize(1);

            api.patchStatus(store.token(), "/api/v1/supplier-stores/" + store.storeId(),
                    Map.of("status", "OFFLINE"));

            var result = recommend(outlet, butter, 5);
            assertThat(result.get("offers")).isEmpty();
            assertThat(result.get("unservedReason").asText()).contains("accepting orders");
        }

        @Test
        @DisplayName("an out-of-stock offer is excluded")
        void outOfStockExcluded() throws Exception {
            long ghee = freshProduct("ghee");
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            long skuId = stock(store, ghee, "ABC-GHEE", "620");

            api.patchStatus(store.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("availability", "OUT_OF_STOCK"));

            var result = recommend(outlet, ghee, 5);
            assertThat(result.get("offers")).isEmpty();
            assertThat(result.get("unservedReason").asText()).contains("available");
        }

        @Test
        @DisplayName("a superseded offer is excluded and only the current price is used")
        void supersededOfferExcluded() throws Exception {
            long sugar = freshProduct("sugar");
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            long skuId = stock(store, sugar, "ABC-SUGAR", "2400");

            api.patchStatus(store.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "2600"));

            var offers = recommend(outlet, sugar, 1).get("offers");

            // Doc 07 §14: an expired offer is excluded. The old price still exists
            // in history and must not be recommended from.
            assertThat(offers).hasSize(1);
            assertThat(offers.get(0).get("unitPrice").asDouble()).isEqualTo(2600.0);
        }

        @Test
        @DisplayName("a store's explicit pincode list overrides geography")
        void pincodeListOverridesDistance() throws Exception {
            long rice = freshProduct("rice");
            var outlet = newOutlet();  // pincode 500034
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            stock(store, rice, "ABC-RICE", "1450");

            assertThat(recommend(outlet, rice, 2).get("offers")).hasSize(1);

            // A supplier who says "these areas only" means it, and a distance
            // calculation should not overrule them.
            jdbc.update("""
                    insert into supplier_delivery_policy
                        (supplier_store_id, serviceable_pincodes_json, created_at, updated_at, version)
                    values (?, '["500001","500002"]', now(6), now(6), 0)
                    """, store.storeId());

            assertThat(recommend(outlet, rice, 2).get("offers")).isEmpty();
        }

        @Test
        @DisplayName("a configured radius narrower than the distance excludes the store")
        void radiusIsRespected() throws Exception {
            long onion = freshProduct("onion");
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            stock(store, onion, "ABC-ONION", "30");

            assertThat(recommend(outlet, onion, 50).get("offers")).hasSize(1);

            // The store is ~7 km away; a 2 km radius puts the outlet outside it.
            jdbc.update("""
                    insert into supplier_delivery_policy
                        (supplier_store_id, max_delivery_radius_km, created_at, updated_at, version)
                    values (?, 2, now(6), now(6), 0)
                    """, store.storeId());

            assertThat(recommend(outlet, onion, 50).get("offers")).isEmpty();
        }
    }

    // ── Honesty ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("honesty")
    class Honesty {

        @Test
        @DisplayName("every supplier is marked NEW_SUPPLIER while there is no order history")
        void noFabricatedPerformance() throws Exception {
            // The system has no completed orders, so nobody has a fill rate. Doc 07
            // §4 forbids fabricating metrics and §5 forbids showing an explanation
            // the data does not support.
            long paneer = freshProduct("paneer");
            var outlet = newOutlet();
            stock(newStore("ABC Foods", NEARBY_LAT, NEARBY_LON), paneer, "NEW-PNR", "410");

            var offer = recommend(outlet, paneer, 10).get("offers").get(0);
            String explanations = offer.get("explanations").toString();

            assertThat(explanations).contains("NEW_SUPPLIER");
            assertThat(explanations)
                    .doesNotContain("HIGH_FILL_RATE", "RELIABLE_SUPPLIER", "LOWER_HISTORICAL_COST");

            // And the breakdown shows those components were absent, not scored zero.
            var components = offer.get("scoreComponents");
            assertThat(components.has("fillRate")).isFalse();
            assertThat(components.has("onTime")).isFalse();
            assertThat(components.has("price")).isTrue();
        }

        @Test
        @DisplayName("a recommendation never exposes commission")
        void noCommissionAnywhere() throws Exception {
            long paneer = freshProduct("paneer");
            var outlet = newOutlet();
            stock(newStore("ABC Foods", NEARBY_LAT, NEARBY_LON), paneer, "C-PNR", "410");

            String body = recommend(outlet, paneer, 10).toString().toLowerCase();

            // Guardrail 9: commission is not a ranking factor and not shown.
            assertThat(body).doesNotContain("commission", "takerate", "margin");
        }

        @Test
        @DisplayName("effectiveTotal excludes delivery, which is not quoted yet")
        void deliveryIsNotGuessed() throws Exception {
            long paneer = freshProduct("paneer");
            var outlet = newOutlet();
            stock(newStore("ABC Foods", NEARBY_LAT, NEARBY_LON), paneer, "D-PNR", "400");

            var offer = recommend(outlet, paneer, 10).get("offers").get(0);

            // 10 × ₹400 = ₹4,000 + 5% = ₹4,200, and nothing else. A guessed
            // delivery fee folded into a total would be a made-up commercial value.
            assertThat(offer.get("effectiveTotal").asDouble()).isEqualTo(4200.0);
        }
    }

    // ── Scope ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("scope")
    class Scope {

        @Test
        @DisplayName("recommendations for another restaurant's outlet are not reachable")
        void outletIsScoped() throws Exception {
            var mine = newOutlet();
            var stranger = newOutlet();
            long paneer = freshProduct("paneer");

            // The outlet decides serviceability and therefore what a competitor
            // could learn about someone else's supplier options.
            assertThat(api.getStatus(stranger.token(), "/api/v1/products/" + paneer
                    + "/recommendations?outletId=" + mine.outletId()))
                    .isEqualTo(404);
        }
    }

    // ── Search ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("suggestions cover products, aliases and categories")
        void suggestionsCoverVocabulary() throws Exception {
            String token = api.loginFresh();

            assertThat(api.get(token, "/api/v1/search/suggestions?q=pan").at("/data")
                    .findValuesAsText("term")).contains("Paneer");

            // The alias is shown as typed, because that is what the kitchen
            // recognises even though the product is called Curd.
            var dahi = api.get(token, "/api/v1/search/suggestions?q=dah").at("/data");
            assertThat(dahi.findValuesAsText("term")).contains("Dahi");
            assertThat(dahi.findValuesAsText("type")).contains("ALIAS");

            assertThat(api.get(token, "/api/v1/search/suggestions?q=dai").at("/data")
                    .findValuesAsText("type")).contains("CATEGORY");
        }

        @Test
        @DisplayName("a single character returns nothing")
        void suggestionsNeedTwoCharacters() throws Exception {
            assertThat(api.get(api.loginFresh(), "/api/v1/search/suggestions?q=p").at("/data"))
                    .isEmpty();
        }

        @Test
        @DisplayName("supplier search finds tradeable suppliers by name")
        void supplierSearchFindsTradeable() throws Exception {
            var store = newStore("Krishna Dairy", NEARBY_LAT, NEARBY_LON);
            stock(store, freshProduct("curd"), "KRI-CURD", "70");
            var outlet = newOutlet();

            var results = api.get(outlet.token(),
                    "/api/v1/search/suppliers?q=krishna&outletId=" + outlet.outletId()).at("/data");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).get("supplierName").asText()).isEqualTo("Krishna Dairy");
            assertThat(results.get(0).get("productCount").asInt()).isEqualTo(1);
            assertThat(results.get(0).get("distanceKm").asDouble()).isPositive();
        }

        @Test
        @DisplayName("a suspended supplier is not findable")
        void suspendedSupplierHidden() throws Exception {
            var store = newStore("Ghost Traders", NEARBY_LAT, NEARBY_LON);
            jdbc.update("update supplier_organization set lifecycle_status = 'SUSPENDED' where id = ?",
                    store.supplierId());

            assertThat(api.get(api.loginFresh(), "/api/v1/search/suppliers?q=ghost").at("/data"))
                    .isEmpty();
        }
    }
}
