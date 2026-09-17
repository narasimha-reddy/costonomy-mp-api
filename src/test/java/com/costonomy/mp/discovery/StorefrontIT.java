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
 * SKU search, a store's catalog, and the supplier directory. Doc 05 §6.
 *
 * <p>These are the three questions the restaurant's search screen asks, and what
 * is tested here is the half that needs a database: which rows survive
 * serviceability, whether distance orders them, and whether a store's own
 * declared radius is what decides membership rather than a fixed number.
 */
@AutoConfigureMockMvc
class StorefrontIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    // Two points in Hyderabad about 7 km apart, and one 500 km away in Chennai.
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

    // ── SKU search ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("SKU search")
    class Skus {

        @Test
        @DisplayName("returns one row per supplier's pack, cheapest first, with the picture and the seller")
        void returnsOneRowPerPack() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set image_url = ? where id = ?",
                    "https://example.test/canonical.jpg", product);

            var outlet = newOutlet();
            var cheap = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            var dear = newStore("XYZ Traders", NEARBY_LAT, NEARBY_LON);
            stock(cheap, product, code("ABC", product), "410");
            stock(dear, product, code("XYZ", product), "440");

            var rows = search(outlet, tag(product));

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).get("sellingPrice").asDouble()).isEqualTo(410.0);
            assertThat(rows.get(1).get("sellingPrice").asDouble()).isEqualTo(440.0);

            var top = rows.get(0);
            assertThat(top.get("supplierName").asText()).isEqualTo("ABC Foods");
            assertThat(top.get("canonicalProductId").asLong()).isEqualTo(product);
            assertThat(top.get("distanceKm").asDouble()).isBetween(1.0, 15.0);
            // The SKU carries no picture of its own, so the canonical one stands in.
            assertThat(top.get("imageUrl").asText()).isEqualTo("https://example.test/canonical.jpg");
        }

        @Test
        @DisplayName("the supplier's own picture wins over the canonical one")
        void skuImageWins() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set image_url = ? where id = ?",
                    "https://example.test/canonical.jpg", product);

            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            long skuId = stock(store, product, code("ABC", product), "410");
            jdbc.update("update supplier_sku set image_url = ? where id = ?",
                    "https://example.test/their-own.jpg", skuId);

            assertThat(search(outlet, tag(product)).get(0).get("imageUrl").asText())
                    .isEqualTo("https://example.test/their-own.jpg");
        }

        @Test
        @DisplayName("a supplier who cannot deliver here is not offered")
        void unservedSupplierIsExcluded() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            var outlet = newOutlet();
            stock(newStore("Chennai Foods", FAR_LAT, FAR_LON), product, code("FAR", product), "300");

            assertThat(search(outlet, tag(product))).isEmpty();
        }

        @Test
        @DisplayName("matches the canonical product's name, not only the supplier's own")
        void matchesCanonicalName() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "curd");
            // Put the unique token on the *product*, and nowhere near the SKU.
            jdbc.update("update canonical_product set name = ?, normalized_name = ? where id = ?",
                    tag(product), tag(product).toLowerCase(), product);

            var outlet = newOutlet();
            // The supplier calls it "Dahi" — its name shares nothing with the term.
            stock(newStore("ABC Foods", NEARBY_LAT, NEARBY_LON), product, "DAHI-" + product, "88");

            // Found anyway, because search reads the canonical name too.
            assertThat(search(outlet, tag(product))).hasSize(1);
        }

        @Test
        @DisplayName("one character is not a search")
        void refusesShortTerms() throws Exception {
            var outlet = newOutlet();
            assertThat(search(outlet, "p")).isEmpty();
        }
    }

    // ── A store's catalog ────────────────────────────────────────────────

    @Nested
    @DisplayName("store catalog")
    class Catalog {

        @Test
        @DisplayName("lists what the store sells, and shows it even when the store cannot deliver here")
        void listsEvenWhenUnserved() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            var outlet = newOutlet();
            var far = newStore("Chennai Foods", FAR_LAT, FAR_LON);
            stock(far, product, code("FAR", product), "300");

            // The same store contributes nothing to search…
            assertThat(search(outlet, tag(product))).isEmpty();

            // …and still has a shelf, because the restaurant asked for it by name.
            var rows = api.get(outlet.token(), "/api/v1/supplier-stores/" + far.storeId()
                    + "/catalog?outletId=" + outlet.outletId()).at("/data");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("skuName").asText()).isEqualTo(code("FAR", product));
            assertThat(rows.get(0).get("distanceKm").asDouble()).isGreaterThan(100.0);
        }

        @Test
        @DisplayName("a term searches within the store")
        void filtersWithinTheStore() throws Exception {
            var outlet = newOutlet();
            var store = newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            long paneer = TestCatalog.freshProduct(jdbc, "paneer");
            long curd = TestCatalog.freshProduct(jdbc, "curd");
            stock(store, paneer, code("ABC", paneer), "410");
            stock(store, curd, code("ABC", curd), "88");

            assertThat(catalog(outlet, store, "?q=" + tag(curd))).hasSize(1);
            assertThat(catalog(outlet, store, "")).hasSize(2);
        }
    }

    // ── The supplier directory ───────────────────────────────────────────

    @Nested
    @DisplayName("supplier directory")
    class Suppliers {

        @Test
        @DisplayName("lists who delivers here with no search term at all")
        void listsWithoutATerm() throws Exception {
            var outlet = newOutlet();
            newStore("ABC Foods", NEARBY_LAT, NEARBY_LON);
            newStore("Chennai Foods", FAR_LAT, FAR_LON);

            var page = directory(outlet, "");
            var names = page.get("suppliers").findValuesAsText("supplierName");

            assertThat(names).contains("ABC Foods");
            // Five hundred kilometres away is not a supplier of yours.
            assertThat(names).doesNotContain("Chennai Foods");
        }

        @Test
        @DisplayName("nearest first")
        void sortsByDistance() throws Exception {
            var outlet = newOutlet();
            newStore("Far Foods", NEARBY_LAT, NEARBY_LON);
            newStore("Next Door", HYD_LAT, HYD_LON);

            var names = directory(outlet, "").get("suppliers").findValuesAsText("supplierName");
            assertThat(names.indexOf("Next Door")).isLessThan(names.indexOf("Far Foods"));
        }

        @Test
        @DisplayName("a store's own declared radius decides, not a fixed number")
        void respectsTheStoresOwnRadius() throws Exception {
            var outlet = newOutlet();
            var store = newStore("Short Reach", NEARBY_LAT, NEARBY_LON);

            // Seven kilometres away, and they have said they deliver one.
            jdbc.update("insert into supplier_delivery_policy "
                    + "(supplier_store_id, max_delivery_radius_km) values (?, ?)",
                    store.storeId(), 1.0);

            assertThat(directory(outlet, "").get("suppliers").findValuesAsText("supplierName"))
                    .doesNotContain("Short Reach");
        }

        @Test
        @DisplayName("a radius narrows the list and says what it left out")
        void radiusCountsWhatItExcluded() throws Exception {
            var outlet = newOutlet();
            newStore("Next Door", HYD_LAT, HYD_LON);
            newStore("Across Town", NEARBY_LAT, NEARBY_LON);

            // Across Town is about 7 km away; 2 km keeps only the near one, and the
            // other is counted rather than silently dropped.
            var page = directory(outlet, "&radiusKm=2");
            assertThat(page.get("suppliers").findValuesAsText("supplierName"))
                    .contains("Next Door")
                    .doesNotContain("Across Town");
            assertThat(page.get("beyondRadius").asInt()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("a term finds whoever stocks it, not only whoever is named it")
        void findsByWhatTheyStock() throws Exception {
            var outlet = newOutlet();
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set name = ?, normalized_name = ? where id = ?",
                    tag(product), tag(product).toLowerCase(), product);

            var stocks = newStore("Gupta Provisions", NEARBY_LAT, NEARBY_LON);
            newStore("Sharma Traders", NEARBY_LAT, NEARBY_LON);
            // Its SKU code shares nothing with the term; only the product matches.
            stock(stocks, product, "GP-" + product, "410");

            var page = directory(outlet, "&q=" + tag(product));
            var suppliers = page.get("suppliers");

            assertThat(suppliers.findValuesAsText("supplierName"))
                    .containsExactly("Gupta Provisions");
            // And the row can say why it is here.
            assertThat(suppliers.get(0).get("matchingProductCount").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("stocking it is not enough — it has to be buyable")
        void withdrawnOfferDoesNotCount() throws Exception {
            var outlet = newOutlet();
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set name = ?, normalized_name = ? where id = ?",
                    tag(product), tag(product).toLowerCase(), product);

            var store = newStore("Gupta Provisions", NEARBY_LAT, NEARBY_LON);
            long skuId = stock(store, product, "GP-" + product, "410");
            // The price is withdrawn; they list it and cannot sell it.
            jdbc.update("update supplier_offer set status = 'INACTIVE' where supplier_sku_id = ?", skuId);

            assertThat(directory(outlet, "&q=" + tag(product)).get("suppliers")
                    .findValuesAsText("supplierName")).doesNotContain("Gupta Provisions");
        }

        @Test
        @DisplayName("a term filters by name")
        void filtersByName() throws Exception {
            var outlet = newOutlet();
            newStore("Gupta Provisions", NEARBY_LAT, NEARBY_LON);
            newStore("Sharma Traders", NEARBY_LAT, NEARBY_LON);

            // Still matched by name, because the other half of that screen is
            // "find the supplier I already deal with".
            var matched = directory(outlet, "&q=gupta").get("suppliers");
            assertThat(matched.findValuesAsText("supplierName")).containsExactly("Gupta Provisions");
            // Nothing they sell matched, so the row will not claim otherwise.
            assertThat(matched.get(0).get("matchingProductCount").asInt()).isZero();
        }
    }

    // ── "N suppliers" on a product card ──────────────────────────────────

    @Nested
    @DisplayName("product supplier counts")
    class ProductCounts {

        @Test
        @DisplayName("counts only suppliers that can trade and can deliver here")
        void countsOnlyBuyableOffers() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            var outlet = newOutlet();

            var near = newStore("Near Foods", NEARBY_LAT, NEARBY_LON);
            stock(near, product, code("NEAR", product), "410");

            // Five hundred kilometres away: real, tradeable, and no use to you.
            stock(newStore("Chennai Foods", FAR_LAT, FAR_LON), product, code("FAR", product), "300");

            // Next door, but its organisation has not been through verification,
            // so it cannot accept an order and must not be offered as one.
            var pending = newStore("Pending Foods", NEARBY_LAT, NEARBY_LON);
            stock(pending, product, code("PEND", product), "250");
            jdbc.update("update supplier_organization set lifecycle_status = 'VERIFICATION_PENDING' "
                    + "where id = ?", pending.supplierId());

            var card = product(outlet, product);
            assertThat(card.get("offerCount").asInt()).isEqualTo(1);
            // ...and the "from ₹X" is the cheapest you can actually buy, not the
            // cheapest that exists — ₹250 and ₹300 are both unavailable to you.
            assertThat(card.get("lowestPrice").asDouble()).isEqualTo(410.0);
        }

        @Test
        @DisplayName("without an outlet the count is platform-wide, but still only of suppliers that can trade")
        void withoutAnOutletDistanceCannotApply() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");

            stock(newStore("Near Foods", NEARBY_LAT, NEARBY_LON), product, code("NEAR", product), "410");
            stock(newStore("Chennai Foods", FAR_LAT, FAR_LON), product, code("FAR", product), "300");

            var suspended = newStore("Gone Foods", NEARBY_LAT, NEARBY_LON);
            stock(suspended, product, code("GONE", product), "200");
            jdbc.update("update supplier_organization set lifecycle_status = 'SUSPENDED' where id = ?",
                    suspended.supplierId());

            // There is no outlet to measure from, so distance cannot exclude
            // anyone — but a suspended supplier is excluded anywhere.
            var card = api.get(api.loginFresh(), "/api/v1/products/" + product).at("/data");
            assertThat(card.get("offerCount").asInt()).isEqualTo(2);
            assertThat(card.get("lowestPrice").asDouble()).isEqualTo(300.0);
        }

        @Test
        @DisplayName("search results carry the same count as the product screen")
        void searchAgreesWithTheProductScreen() throws Exception {
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set name = ?, normalized_name = ? where id = ?",
                    tag(product), tag(product).toLowerCase(), product);

            var outlet = newOutlet();
            stock(newStore("Near Foods", NEARBY_LAT, NEARBY_LON), product, code("NEAR", product), "410");
            stock(newStore("Chennai Foods", FAR_LAT, FAR_LON), product, code("FAR", product), "300");

            var found = api.get(outlet.token(), "/api/v1/search/products?q=" + tag(product)
                    + "&outletId=" + outlet.outletId()).at("/data");

            assertThat(found).hasSize(1);
            assertThat(found.get(0).get("offerCount").asInt())
                    .isEqualTo(product(outlet, product).get("offerCount").asInt())
                    .isEqualTo(1);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private JsonNode product(Outlet outlet, long productId) throws Exception {
        return api.get(outlet.token(),
                "/api/v1/products/" + productId + "?outletId=" + outlet.outletId()).at("/data");
    }


    /**
     * A term no other test in this class can match.
     *
     * <p>SKU search is by term across the whole catalog, and these tests share one
     * database — searching "PNR" found every paneer SKU every other test had
     * created, which is a fault in the test rather than in the search. Tying the
     * term to the product id makes each assertion about its own rows.
     */
    private static String tag(long productId) {
        return "SF" + productId + "Z";
    }

    private static String code(String prefix, long productId) {
        return prefix + "-" + tag(productId);
    }

    private JsonNode search(Outlet outlet, String term) throws Exception {
        return api.get(outlet.token(), "/api/v1/search/skus?q="
                + java.net.URLEncoder.encode(term, java.nio.charset.StandardCharsets.UTF_8)
                + "&outletId=" + outlet.outletId()).at("/data");
    }

    private JsonNode catalog(Outlet outlet, Store store, String query) throws Exception {
        String separator = query.isEmpty() ? "?" : query + "&";
        return api.get(outlet.token(), "/api/v1/supplier-stores/" + store.storeId()
                + "/catalog" + separator + "outletId=" + outlet.outletId()).at("/data");
    }

    private JsonNode directory(Outlet outlet, String extra) throws Exception {
        return api.get(outlet.token(),
                "/api/v1/search/suppliers?outletId=" + outlet.outletId() + extra).at("/data");
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
        // Otherwise every assertion here would depend on the hour the suite ran.
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
}
