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

import java.util.ArrayList;
import java.util.List;
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
                "contactName", "Ops Desk", "contactPhone", "+919876500000",
                "firstStore", Map.of("contactName", "Store Desk", "contactPhone", "+919876500000", 
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
        @DisplayName("an item fulfilled by multiple brands displays all options with lowest priced first")
        void displaysMultiBrandOptionsLowestPricedFirst() throws Exception {
            long paneer = freshProduct("paneer");
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);

            jdbc.update("insert into brand (name, normalized_name) values ('Nandini', 'nandini') on duplicate key update name=name");
            long brandNandini = jdbc.queryForObject("select id from brand where normalized_name = 'nandini'", Long.class);

            jdbc.update("insert into brand (name, normalized_name) values ('Amul', 'amul') on duplicate key update name=name");
            long brandAmul = jdbc.queryForObject("select id from brand where normalized_name = 'amul'", Long.class);

            jdbc.update("insert into brand (name, normalized_name) values ('Milky Mist', 'milky mist') on duplicate key update name=name");
            long brandMilky = jdbc.queryForObject("select id from brand where normalized_name = 'milky mist'", Long.class);

            long skuAmul = stock(store, paneer, "AMUL-PNR", "410");
            jdbc.update("update supplier_sku set brand_id = ?, name = 'Amul Paneer' where id = ?", brandAmul, skuAmul);

            long skuNandini = stock(store, paneer, "NAN-PNR", "380");
            jdbc.update("update supplier_sku set brand_id = ?, name = 'Nandini Paneer' where id = ?", brandNandini, skuNandini);

            long skuMilky = stock(store, paneer, "MILKY-PNR", "430");
            jdbc.update("update supplier_sku set brand_id = ?, name = 'Milky Mist Paneer' where id = ?", brandMilky, skuMilky);

            var result = recommend(outlet, paneer, 5);
            var offers = result.get("offers");
            assertThat(offers).hasSize(1);

            var offer = offers.get(0);
            var brandOptions = offer.get("brandOptions");
            assertThat(brandOptions).hasSize(3);

            // Lowest priced one first: Nandini (380) < Amul (410) < Milky Mist (430)
            assertThat(brandOptions.get(0).get("brandName").asText()).isEqualTo("Nandini");
            assertThat(brandOptions.get(0).get("sellingPrice").asDouble()).isEqualTo(380.0);

            assertThat(brandOptions.get(1).get("brandName").asText()).isEqualTo("Amul");
            assertThat(brandOptions.get(1).get("sellingPrice").asDouble()).isEqualTo(410.0);

            assertThat(brandOptions.get(2).get("brandName").asText()).isEqualTo("Milky Mist");
            assertThat(brandOptions.get(2).get("sellingPrice").asDouble()).isEqualTo(430.0);
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

    // ── Sort and filters (D-149) ─────────────────────────────────────────

    @Nested
    @DisplayName("sort and filters on the comparison")
    class Choices {

        private void rate(Outlet outlet, Store store, int stars) {
            Long buyerUserId = jdbc.queryForObject("select id from users limit 1", Long.class);
            jdbc.update("""
                    insert into procurement (outlet_id, created_by, status, approval_status,
                                              payment_method, payment_status, total_amount, created_at, updated_at, version)
                    values (?, ?, 'SUBMITTED', 'NOT_REQUIRED', 'PREPAID', 'CAPTURED', 500.00, now(6), now(6), 0)
                    """, outlet.outletId(), buyerUserId);
            Long procurementId = jdbc.queryForObject(
                    "select id from procurement where outlet_id = ? order by id desc limit 1", Long.class, outlet.outletId());
            String orderNumber = "SO-CHOICE-" + System.nanoTime();
            jdbc.update("""
                    insert into supplier_order (procurement_id, supplier_store_id, outlet_id, order_number, status,
                                                total_amount, accepted_amount, delivery_mode, payment_method,
                                                payment_status, created_at, updated_at, version)
                    values (?, ?, ?, ?, 'COMPLETED', 500.00, 500.00, 'SUPPLIER_DELIVERY', 'PREPAID', 'CAPTURED',
                            now(6), now(6), 0)
                    """, procurementId, store.storeId(), outlet.outletId(), orderNumber);
            Long orderId = jdbc.queryForObject("select id from supplier_order where order_number = ?",
                    Long.class, orderNumber);
            jdbc.update("""
                    insert into rating (supplier_order_id, outlet_id, supplier_store_id, overall_rating,
                                        moderation_status, rated_by, version)
                    values (?, ?, ?, ?, 'PUBLISHED', ?, 0)
                    """, orderId, outlet.outletId(), store.storeId(), stars, buyerUserId);
        }

        private JsonNode compare(Outlet outlet, long product, String extra) throws Exception {
            return api.get(outlet.token(), "/api/v1/products/" + product + "/recommendations?outletId="
                    + outlet.outletId() + "&quantity=20" + extra).at("/data");
        }

        private List<String> order(JsonNode result) {
            var names = new ArrayList<String>();
            result.get("offers").forEach(o -> names.add(o.get("supplierName").asText()));
            return names;
        }

        /** Four suppliers: a near dear one, a far cheap one with little stock, a near mid-price one that is closed. */
        private record Field(Outlet outlet, long product, Store near, Store far, Store thin, Store closed) {
        }

        private Field field() throws Exception {
            long product = freshProduct("choices");
            var outlet = newOutlet();
            String run = "C" + System.nanoTime();
            var near = newStore(run + " Near", HYD_LAT, HYD_LON);
            var far = newStore(run + " Far", NEARBY_LAT, NEARBY_LON);
            var thin = newStore(run + " Thin", NEARBY_LAT, NEARBY_LON);
            var closed = newStore(run + " Closed", HYD_LAT, HYD_LON);
            stock(near, product, "NR" + run, "450");
            stock(far, product, "FR" + run, "400");
            long thinSku = stock(thin, product, "TH" + run, "380");
            stock(closed, product, "CL" + run, "420");
            // The cheapest has only 5 kg against the 20 asked for.
            assertThat(api.patchStatus(thin.token(), "/api/v1/supplier-skus/" + thinSku,
                    Map.of("availableQuantity", "5"))).isEqualTo(200);
            jdbc.update("update supplier_store set operating_hours_json = ? where id = ?",
                    "{\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\","
                            + "\"SUNDAY\"],\"opensAt\":\"01:00\",\"closesAt\":\"01:01\"}", closed.storeId());
            rate(outlet, near, 3);
            rate(outlet, far, 5);
            rate(outlet, thin, 4);
            return new Field(outlet, product, near, far, thin, closed);
        }

        @Test
        @DisplayName("price puts the cheapest first, nearest the closest, rating the best rated; best value is unchanged")
        void sorts() throws Exception {
            var f = field();

            var byPrice = order(compare(f.outlet(), f.product(), "&sort=price"));
            var byNearest = order(compare(f.outlet(), f.product(), "&sort=nearest"));
            var byRating = order(compare(f.outlet(), f.product(), "&sort=rating"));
            var bestValue = order(compare(f.outlet(), f.product(), ""));
            var explicitBestValue = order(compare(f.outlet(), f.product(), "&sort=best_value"));

            assertThat(byPrice.get(0)).endsWith("Thin");
            assertThat(byPrice.get(byPrice.size() - 1)).endsWith("Near");
            assertThat(byNearest.subList(0, 2)).allSatisfy(n -> assertThat(n).containsAnyOf("Near", "Closed"));
            assertThat(byRating.get(0)).endsWith("Far");
            assertThat(byRating.get(1)).endsWith("Thin");
            assertThat(explicitBestValue).as("best_value is the default ranking").isEqualTo(bestValue);
        }

        @Test
        @DisplayName("covers-quantity hides the supplier without enough stock, and says how many it hid")
        void coversQuantity() throws Exception {
            var f = field();

            var result = compare(f.outlet(), f.product(), "&coversQuantity=true");

            assertThat(order(result)).noneMatch(n -> n.endsWith("Thin"));
            assertThat(order(result)).hasSize(3);
            assertThat(result.get("hiddenByFilters").asInt()).isEqualTo(1);
            assertThat(compare(f.outlet(), f.product(), "").get("hiddenByFilters").asInt())
                    .as("nothing hidden without a filter").isZero();
        }

        @Test
        @DisplayName("open-now hides a closed supplier")
        void openNow() throws Exception {
            var f = field();

            var result = compare(f.outlet(), f.product(), "&openNow=true");

            assertThat(order(result)).noneMatch(n -> n.endsWith("Closed"));
            assertThat(result.get("hiddenByFilters").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("a distance filter hides suppliers beyond it, and a filter does not re-rank the rest")
        void distanceAndRanking() throws Exception {
            var f = field();
            var all = order(compare(f.outlet(), f.product(), ""));

            var nearOnly = compare(f.outlet(), f.product(), "&radiusKm=3");

            assertThat(order(nearOnly)).allSatisfy(n -> assertThat(n).containsAnyOf("Near", "Closed"));
            assertThat(nearOnly.get("hiddenByFilters").asInt()).isEqualTo(2);
            // The survivors keep their relative order from the unfiltered ranking.
            assertThat(all.stream().filter(order(nearOnly)::contains).toList()).isEqualTo(order(nearOnly));
        }

        @Test
        @DisplayName("an unknown sort and a non-positive distance are refused")
        void refusesNonsense() throws Exception {
            var f = field();

            var badSort = api.get(f.outlet().token(), "/api/v1/products/" + f.product()
                    + "/recommendations?outletId=" + f.outlet().outletId() + "&sort=cheapest");
            var badRadius = api.get(f.outlet().token(), "/api/v1/products/" + f.product()
                    + "/recommendations?outletId=" + f.outlet().outletId() + "&radiusKm=0");

            assertThat(badSort.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(badRadius.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }

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

            // D-087: the response is a page now, because a distance filter has to be
            // able to say what it left out rather than just returning less.
            var results = api.get(outlet.token(),
                    "/api/v1/search/suppliers?q=krishna&outletId=" + outlet.outletId())
                    .at("/data/suppliers");

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

            assertThat(api.get(api.loginFresh(), "/api/v1/search/suppliers?q=ghost")
                    .at("/data/suppliers")).isEmpty();
        }
    }
}
