package com.costonomy.mp.discovery;

import com.costonomy.mp.discovery.service.SupplierPerformanceProvider;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
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
    @SpyBean private SupplierPerformanceProvider performance;

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
            // The suite shares one database, so many stores sit at the same distance and this one may not be on the
            // first page. Read every page (D-139) rather than depend on where it sorts.
            String run = "000 " + System.nanoTime();
            newStore(run + " ABC Foods", NEARBY_LAT, NEARBY_LON);
            newStore(run + " Chennai Foods", FAR_LAT, FAR_LON);

            var names = new java.util.ArrayList<String>();
            int offset = 0;
            while (true) {
                var page = directory(outlet, "&limit=100&offset=" + offset);
                names.addAll(page.get("suppliers").findValuesAsText("supplierName"));
                if (page.get("nextOffset").isNull()) {
                    break;
                }
                offset = page.get("nextOffset").asInt();
            }

            assertThat(names).contains(run + " ABC Foods");
            // Five hundred kilometres away is not a supplier of yours.
            assertThat(names).doesNotContain(run + " Chennai Foods");
        }

        @Test
        @DisplayName("nearest first")
        void sortsByDistance() throws Exception {
            var outlet = newOutlet();
            // Searched for by a unique name, for the reason in listsWithoutATerm.
            String run = "R" + System.nanoTime();
            newStore("Far Foods " + run, NEARBY_LAT, NEARBY_LON);
            newStore("Next Door " + run, HYD_LAT, HYD_LON);

            var names = directory(outlet, "&q=" + run).get("suppliers").findValuesAsText("supplierName");
            assertThat(names).contains("Next Door " + run, "Far Foods " + run);
            assertThat(names.indexOf("Next Door " + run)).isLessThan(names.indexOf("Far Foods " + run));
        }

        @Test
        @DisplayName("a store's own declared radius decides, not a fixed number")
        void respectsTheStoresOwnRadius() throws Exception {
            var outlet = newOutlet();
            String run = "R" + System.nanoTime();
            var store = newStore("Short Reach " + run, NEARBY_LAT, NEARBY_LON);
            newStore("Long Reach " + run, NEARBY_LAT, NEARBY_LON);

            // Seven kilometres away, and they have said they deliver one.
            jdbc.update("insert into supplier_delivery_policy "
                    + "(supplier_store_id, max_delivery_radius_km) values (?, ?)",
                    store.storeId(), 1.0);

            // The control is what makes the absence mean something: it is in the
            // same place with no limit, so if it is listed, the search reached here.
            assertThat(directory(outlet, "&q=" + run).get("suppliers").findValuesAsText("supplierName"))
                    .contains("Long Reach " + run)
                    .doesNotContain("Short Reach " + run);
        }

        @Test
        @DisplayName("a radius narrows the list and says what it left out")
        void radiusCountsWhatItExcluded() throws Exception {
            var outlet = newOutlet();
            // Named uniquely and searched for by that name: the suite shares one
            // database, and a list of every supplier near Hyderabad is a list of
            // whatever other test classes created there — enough of them pushed
            // Next Door off the page and failed this for a reason it isn't about.
            String run = "R" + System.nanoTime();
            newStore("Next Door " + run, HYD_LAT, HYD_LON);
            newStore("Across Town " + run, NEARBY_LAT, NEARBY_LON);

            // Across Town is about 7 km away; 2 km keeps only the near one, and the
            // other is counted rather than silently dropped.
            var page = directory(outlet, "&radiusKm=2&q=" + run);
            assertThat(page.get("suppliers").findValuesAsText("supplierName"))
                    .contains("Next Door " + run)
                    .doesNotContain("Across Town " + run);
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
        @DisplayName("out of stock is not a matching item")
        void outOfStockDoesNotCount() throws Exception {
            var outlet = newOutlet();
            long product = TestCatalog.freshProduct(jdbc, "paneer");
            jdbc.update("update canonical_product set name = ?, normalized_name = ? where id = ?",
                    tag(product), tag(product).toLowerCase(), product);

            var stocked = newStore("Gupta Provisions", NEARBY_LAT, NEARBY_LON);
            var empty = newStore("Sharma Traders", NEARBY_LAT, NEARBY_LON);
            stock(stocked, product, "GP-" + product, "410");
            long soldOut = stock(empty, product, "ST-" + product, "400");
            jdbc.update("update supplier_offer set availability = 'OUT_OF_STOCK' "
                    + "where supplier_sku_id = ?", soldOut);

            var suppliers = directory(outlet, "&q=" + tag(product)).get("suppliers");

            // Sharma carries it and cannot sell it today, so they are not an
            // answer to "who has this" — the pack list still shows the row.
            assertThat(suppliers.findValuesAsText("supplierName"))
                    .containsExactly("Gupta Provisions");
            assertThat(suppliers.get(0).get("matchingProductCount").asInt()).isEqualTo(1);
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

        @Test
        @DisplayName("an outlet the caller cannot see returns 404, not 403")
        void unscopedOutletReturnsNotFound() throws Exception {
            var outlet = newOutlet();
            String otherToken = api.loginFresh();

            // When an outletId is supplied that the caller is not scoped to, requireScoped
            // throws NotFoundException, mapped to 404.
            int status = api.getStatus(otherToken, "/api/v1/search/suppliers?outletId=" + outlet.outletId());
            assertThat(status).isEqualTo(404);
        }

        @Test
        @DisplayName("reach=all bypasses serviceability filter for credit flow")
        void reachAllBypassesServiceability() throws Exception {
            var outlet = newOutlet();
            String run = "R" + System.nanoTime();
            newStore(run + " Chennai Foods", FAR_LAT, FAR_LON);

            var page = directory(outlet, "&reach=all&q=" + run);
            var names = page.get("suppliers").findValuesAsText("supplierName");
            assertThat(names).contains(run + " Chennai Foods");
        }

        @Test
        @DisplayName("100 AAA stores at 2-5 km plus one ZZZ at 0.5 km: ZZZ must be first on page 1, disjoint pages cover all with no repeats, limit clamped, nextOffset null at end")
        void paginationAndNearestSorting() throws Exception {
            var outlet = newOutlet();
            String run = "D139_" + System.currentTimeMillis();

            // Insert 100 AAA stores directly via SQL into database to be fast
            // Distances around 2 to 5 km from HYD (17.4156, 78.4347).
            // A delta of 0.02 to 0.04 deg lat is ~2.2 km to 4.5 km.
            String insertOrg = """
                    insert into supplier_organization (legal_name, display_name, contact_name, contact_phone, lifecycle_status, verification_status)
                    values (?, ?, 'Desk', '+919876500000', 'ACTIVE', 'VERIFIED')
                    """;
            String insertStore = """
                    insert into supplier_store (supplier_organization_id, name, address_line1, city, state, pincode, latitude, longitude, preparation_minutes, status)
                    values (?, ?, 'Street', 'Hyderabad', 'Telangana', '500034', ?, ?, 30, 'ACTIVE')
                    """;

            for (int i = 0; i < 100; i++) {
                String orgName = String.format("%s_AAA_%03d", run, i);
                jdbc.update(insertOrg, orgName + " Ltd", orgName);
                Long orgId = jdbc.queryForObject("select id from supplier_organization where display_name = ?", Long.class, orgName);
                double lat = 17.4350 + (i * 0.0001); // ~2.2 km away
                double lon = 78.4350;
                jdbc.update(insertStore, orgId, orgName + " Store", String.valueOf(lat), String.valueOf(lon));
                Long storeId = jdbc.queryForObject("select id from supplier_store where supplier_organization_id = ?", Long.class, orgId);
                TestCatalog.tradesAroundTheClock(jdbc, orgId);
            }

            // Insert one ZZZ store at 0.5 km (~0.004 deg lat)
            String zzzName = run + "_ZZZ_Near";
            jdbc.update(insertOrg, zzzName + " Ltd", zzzName);
            Long zzzOrgId = jdbc.queryForObject("select id from supplier_organization where display_name = ?", Long.class, zzzName);
            double zzzLat = 17.4200; // ~0.5 km away
            double zzzLon = 78.4347;
            jdbc.update(insertStore, zzzOrgId, zzzName + " Store", String.valueOf(zzzLat), String.valueOf(zzzLon));
            TestCatalog.tradesAroundTheClock(jdbc, zzzOrgId);

            // Total stores matching run is 101.
            // Page 1 with default limit 50, offset 0:
            var page1 = directory(outlet, "&q=" + run + "&offset=0&limit=50");
            assertThat(page1.get("total").asInt()).isEqualTo(101);
            assertThat(page1.get("nextOffset").asInt()).isEqualTo(50);
            var page1Suppliers = page1.get("suppliers");
            assertThat(page1Suppliers).hasSize(50);
            // ZZZ must be first on page 1 because it's nearest (0.5 km vs 2+ km)!
            assertThat(page1Suppliers.get(0).get("supplierName").asText()).isEqualTo(zzzName);

            // Page 2 with limit 50, offset 50:
            var page2 = directory(outlet, "&q=" + run + "&offset=50&limit=50");
            assertThat(page2.get("total").asInt()).isEqualTo(101);
            assertThat(page2.get("nextOffset").asInt()).isEqualTo(100);
            var page2Suppliers = page2.get("suppliers");
            assertThat(page2Suppliers).hasSize(50);

            // Page 3 with limit 50, offset 100:
            var page3 = directory(outlet, "&q=" + run + "&offset=100&limit=50");
            assertThat(page3.get("total").asInt()).isEqualTo(101);
            assertThat(page3.get("nextOffset").isNull()).isTrue();
            var page3Suppliers = page3.get("suppliers");
            assertThat(page3Suppliers).hasSize(1);

            // Verify disjoint pages cover all stores with no repeats:
            var allNames = new java.util.ArrayList<String>();
            allNames.addAll(page1Suppliers.findValuesAsText("supplierName"));
            allNames.addAll(page2Suppliers.findValuesAsText("supplierName"));
            allNames.addAll(page3Suppliers.findValuesAsText("supplierName"));
            assertThat(allNames).hasSize(101);
            assertThat(new java.util.HashSet<>(allNames)).hasSize(101);

            // Limit above 100 is clamped: request limit=200
            var clampedPage = directory(outlet, "&q=" + run + "&offset=0&limit=200");
            assertThat(clampedPage.get("suppliers")).hasSize(100); // clamped to 100
            assertThat(clampedPage.get("nextOffset").asInt()).isEqualTo(100);
        }

        @Test
        @DisplayName("filters by openNow=true and minRating, and sorts by rating with nearest and storeId as tie-breakers")
        void filtersAndSortByRating() throws Exception {
            var outlet = newOutlet();
            String run = "F" + System.nanoTime();

            // Store A: 5-star, open, near (HYD_LAT, HYD_LON ~0.5 km)
            var storeA = newStore(run + " StoreA_5star_near", HYD_LAT, HYD_LON);
            // Store B: 5-star, open, farther (NEARBY_LAT, NEARBY_LON ~7 km)
            var storeB = newStore(run + " StoreB_5star_far", NEARBY_LAT, NEARBY_LON);
            // Store C: 3-star, open, near
            var storeC = newStore(run + " StoreC_3star_near", HYD_LAT, HYD_LON);
            // Store D: unrated, open, near
            var storeD = newStore(run + " StoreD_unrated_near", HYD_LAT, HYD_LON);
            // Store E: 5-star, closed (custom hours 01:00-01:01), near
            var storeE = newStore(run + " StoreE_5star_closed", HYD_LAT, HYD_LON);
            jdbc.update("update supplier_store set operating_hours_json = ? where id = ?",
                    "{\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"],\"opensAt\":\"01:00\",\"closesAt\":\"01:01\"}",
                    storeE.storeId());

            // Ratings:
            rateStore(outlet, storeA, 5);
            rateStore(outlet, storeB, 5);
            rateStore(outlet, storeC, 3);
            rateStore(outlet, storeE, 5);

            // 1. sort=rating without filters: 5-star stores first (ordered by distance: A before B), then 3-star C, then unrated D
            var sortedPage = directory(outlet, "&q=" + run + "&sort=rating");
            var sortedNames = sortedPage.get("suppliers").findValuesAsText("supplierName");
            assertThat(sortedNames).containsSubsequence(
                    run + " StoreA_5star_near",
                    run + " StoreB_5star_far",
                    run + " StoreC_3star_near",
                    run + " StoreD_unrated_near");

            // 2. minRating=4: excludes Store C (3-star) and Store D (unrated), includes A, B, E
            var minRatingPage = directory(outlet, "&q=" + run + "&minRating=4");
            var minRatingNames = minRatingPage.get("suppliers").findValuesAsText("supplierName");
            assertThat(minRatingNames).contains(run + " StoreA_5star_near", run + " StoreB_5star_far", run + " StoreE_5star_closed");
            assertThat(minRatingNames).doesNotContain(run + " StoreC_3star_near", run + " StoreD_unrated_near");

            // 3. openNow=true: excludes Store E (closed), includes A, B, C, D
            var openNowPage = directory(outlet, "&q=" + run + "&openNow=true");
            var openNowNames = openNowPage.get("suppliers").findValuesAsText("supplierName");
            assertThat(openNowNames).contains(run + " StoreA_5star_near", run + " StoreB_5star_far", run + " StoreC_3star_near", run + " StoreD_unrated_near");
            assertThat(openNowNames).doesNotContain(run + " StoreE_5star_closed");

            // 4. Combined: openNow=true & minRating=4 & sort=rating: only A and B, in that order
            var combinedPage = directory(outlet, "&q=" + run + "&openNow=true&minRating=4&sort=rating");
            var combinedNames = combinedPage.get("suppliers").findValuesAsText("supplierName");
            assertThat(combinedNames).containsExactly(run + " StoreA_5star_near", run + " StoreB_5star_far");

            // 5. Unknown sort returns 422
            var unknownSort = api.get(outlet.token(),
                    "/api/v1/search/suppliers?outletId=" + outlet.outletId() + "&sort=bogus");
            assertThat(unknownSort.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }


    @Nested
    @DisplayName("supplier list filters, paging and reach (D-181)")
    class FilterPaging {

        @Test
        @DisplayName("total and nextOffset describe the filtered list, and two pages cover it without repeats")
        void pagingStaysCorrectWithFilters() throws Exception {
            var outlet = newOutlet();
            String run = "G" + System.nanoTime();
            for (int i = 1; i <= 3; i++) {
                rateStore(outlet, newStore(run + " Five" + i, HYD_LAT, HYD_LON), 5);
            }
            for (int i = 1; i <= 2; i++) {
                rateStore(outlet, newStore(run + " Three" + i, HYD_LAT, HYD_LON), 3);
            }
            newStore(run + " Unrated", HYD_LAT, HYD_LON);

            var first = directory(outlet, "&q=" + run + "&minRating=4&limit=2&offset=0");
            var second = directory(outlet, "&q=" + run + "&minRating=4&limit=2&offset=2");

            assertThat(first.get("total").asInt()).as("only the three five-star stores count").isEqualTo(3);
            assertThat(first.get("suppliers")).hasSize(2);
            assertThat(first.get("nextOffset").asInt()).isEqualTo(2);
            assertThat(second.get("suppliers")).hasSize(1);
            assertThat(second.get("nextOffset").isNull()).isTrue();

            var names = new java.util.ArrayList<String>();
            names.addAll(first.get("suppliers").findValuesAsText("supplierName"));
            names.addAll(second.get("suppliers").findValuesAsText("supplierName"));
            assertThat(names).hasSize(3).doesNotHaveDuplicates()
                    .allSatisfy(name -> assertThat(name).contains("Five"));
        }

        @Test
        @DisplayName("reach=all still skips serviceability, and the filters still apply to it")
        void reachAllSkipsServiceabilityNotFilters() throws Exception {
            var outlet = newOutlet();
            String run = "H" + System.nanoTime();
            var farFive = newStore(run + " FarFive", FAR_LAT, FAR_LON);
            var farThree = newStore(run + " FarThree", FAR_LAT, FAR_LON);
            rateStore(outlet, farFive, 5);
            rateStore(outlet, farThree, 3);

            var serviceable = directory(outlet, "&q=" + run).get("suppliers").findValuesAsText("supplierName");
            var everyone = directory(outlet, "&q=" + run + "&reach=all").get("suppliers").findValuesAsText("supplierName");
            var everyoneFiltered = directory(outlet, "&q=" + run + "&reach=all&minRating=4")
                    .get("suppliers").findValuesAsText("supplierName");

            assertThat(serviceable).as("far stores are not serviceable").isEmpty();
            assertThat(everyone).contains(run + " FarFive", run + " FarThree");
            assertThat(everyoneFiltered).containsExactly(run + " FarFive");
        }

        @Test
        @DisplayName("a minimum rating outside 1 to 5 is refused on both lists, as an unknown sort is")
        void badFiltersAreRefused() throws Exception {
            var outlet = newOutlet();

            for (String bad : new String[] {"0", "6", "-1"}) {
                var refused = api.get(outlet.token(),
                        "/api/v1/search/suppliers?outletId=" + outlet.outletId() + "&minRating=" + bad);
                assertThat(refused.at("/error/code").asText()).as("directory minRating=" + bad)
                        .isEqualTo("VALIDATION_ERROR");
                var popularRefused = api.get(outlet.token(),
                        "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?minRating=" + bad);
                assertThat(popularRefused.at("/error/code").asText()).as("popular minRating=" + bad)
                        .isEqualTo("VALIDATION_ERROR");
            }
            var badSort = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?sort=bogus");
            assertThat(badSort.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
        }
    }

    // ── Popular suppliers ────────────────────────────────────────────────

    @Nested
    @DisplayName("popular suppliers")
    class Popular {

        @Test
        @DisplayName("ratings are looked up for the returned page only, unless a filter or sort needs every store's (D-181)")
        void ratingsAreNotLoadedForEveryStoreWithoutAFilter() throws Exception {
            var outlet = newOutlet();
            long product = TestCatalog.freshProduct(jdbc, "ratingload");
            for (int i = 1; i <= 4; i++) {
                stock(newStore("Rated " + System.nanoTime() + " " + i, HYD_LAT, HYD_LON), product,
                        code("RL" + i, product), "100");
            }

            org.mockito.Mockito.clearInvocations(performance);
            api.get(outlet.token(), "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=2");
            int largestWithoutFilter = largestRatingLookup();

            org.mockito.Mockito.clearInvocations(performance);
            api.get(outlet.token(), "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=2&minRating=1");
            int largestWithFilter = largestRatingLookup();

            assertThat(largestWithoutFilter).as("only the two returned suppliers").isLessThanOrEqualTo(2);
            assertThat(largestWithFilter).as("every store, to filter on rating").isGreaterThan(2);
        }

        /** The most store ids passed to one rating lookup since the spy was last cleared. */
        private int largestRatingLookup() {
            return org.mockito.Mockito.mockingDetails(performance).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("forStores"))
                    .mapToInt(call -> ((java.util.Collection<?>) call.getArgument(0)).size())
                    .max().orElse(0);
        }

        @Test
        @DisplayName("a far supplier is excluded and does not eat the limit")
        void farSupplierIsExcludedAndDoesNotEatTheLimit() throws Exception {
            var outlet = newOutlet();
            String catSlug = "cat-" + System.nanoTime();
            jdbc.update("insert into product_category (name, slug, display_order, status, created_at, updated_at, version) values (?, ?, 10, 'ACTIVE', now(6), now(6), 0)",
                    "Cat " + catSlug, catSlug);
            long catId = jdbc.queryForObject("select id from product_category where slug = ?", Long.class, catSlug);

            long p1 = TestCatalog.freshProduct(jdbc, "pop1");
            long p2 = TestCatalog.freshProduct(jdbc, "pop2");
            long p3 = TestCatalog.freshProduct(jdbc, "pop3");
            long p4 = TestCatalog.freshProduct(jdbc, "pop4");
            long p5 = TestCatalog.freshProduct(jdbc, "pop5");

            long p6 = TestCatalog.freshProduct(jdbc, "pop6");
            long p7 = TestCatalog.freshProduct(jdbc, "pop7");
            long p8 = TestCatalog.freshProduct(jdbc, "pop8");
            long p9 = TestCatalog.freshProduct(jdbc, "pop9");
            long p10 = TestCatalog.freshProduct(jdbc, "pop10");
            long p11 = TestCatalog.freshProduct(jdbc, "pop11");

            jdbc.update("update canonical_product set category_id = ? where id in (?,?,?,?,?,?,?,?,?,?,?)",
                    catId, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11);

            // 1 Far store (500 km away) with 11 SKUs (higher skuCount than other stores)
            var farStore = newStore("Far Popular Store", FAR_LAT, FAR_LON);
            stock(farStore, p1, code("FAR1", p1), "100");
            stock(farStore, p2, code("FAR2", p2), "100");
            stock(farStore, p3, code("FAR3", p3), "100");
            stock(farStore, p4, code("FAR4", p4), "100");
            stock(farStore, p5, code("FAR5", p5), "100");
            stock(farStore, p6, code("FAR6", p6), "100");
            stock(farStore, p7, code("FAR7", p7), "100");
            stock(farStore, p8, code("FAR8", p8), "100");
            stock(farStore, p9, code("FAR9", p9), "100");
            stock(farStore, p10, code("FAR10", p10), "100");
            stock(farStore, p11, code("FAR11", p11), "100");

            // 2 Near stores (7 km away) with 10 SKUs each
            var near1 = newStore("Near Popular 1", NEARBY_LAT, NEARBY_LON);
            stock(near1, p1, code("N1_1", p1), "110");
            stock(near1, p2, code("N1_2", p2), "110");
            stock(near1, p3, code("N1_3", p3), "110");
            stock(near1, p4, code("N1_4", p4), "110");
            stock(near1, p5, code("N1_5", p5), "110");
            stock(near1, p6, code("N1_6", p6), "110");
            stock(near1, p7, code("N1_7", p7), "110");
            stock(near1, p8, code("N1_8", p8), "110");
            stock(near1, p9, code("N1_9", p9), "110");
            stock(near1, p10, code("N1_10", p10), "110");

            var near2 = newStore("Near Popular 2", NEARBY_LAT, NEARBY_LON);
            stock(near2, p1, code("N2_1", p1), "120");
            stock(near2, p2, code("N2_2", p2), "120");
            stock(near2, p3, code("N2_3", p3), "120");
            stock(near2, p4, code("N2_4", p4), "120");
            stock(near2, p5, code("N2_5", p5), "120");
            stock(near2, p6, code("N2_6", p6), "120");
            stock(near2, p7, code("N2_7", p7), "120");
            stock(near2, p8, code("N2_8", p8), "120");
            stock(near2, p9, code("N2_9", p9), "120");
            stock(near2, p10, code("N2_10", p10), "120");

            // Popularity is counted over the whole shared database, so stores left behind by other test classes
            // (some stock several SKUs) would outrank these and eat the limit. Raise the three stores above the
            // busiest other store, far one highest, so the test checks the filter and not the leftover data.
            Integer busiest = jdbc.queryForObject("""
                    select coalesce(max(n), 0) from (
                        select count(distinct f.id) n from supplier_offer f
                         where f.status = 'ACTIVE' and f.supplier_store_id not in (?, ?, ?)
                         group by f.supplier_store_id) t
                    """, Integer.class, farStore.storeId(), near1.storeId(), near2.storeId());
            for (int i = 10; i < busiest + 1; i++) {
                long extra = TestCatalog.freshProduct(jdbc, "popx" + i);
                stock(near1, extra, code("N1X" + i, extra), "110");
                stock(near2, extra, code("N2X" + i, extra), "120");
            }
            for (int i = 11; i < busiest + 2; i++) {
                long extra = TestCatalog.freshProduct(jdbc, "popf" + i);
                stock(farStore, extra, code("FARX" + i, extra), "100");
            }

            // Far store is closer to top in SQL query (one more SKU than either near store or any leftover store).
            // With limit=2: if farStore was not filtered out before limit, it would be included and eat the limit!
            // But because farStore is filtered by serviceability, both Near Popular 1 and Near Popular 2 are returned.
            var res = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=2&categoryId=" + catId).at("/data");

            var names = res.findValuesAsText("supplierName");
            assertThat(names).doesNotContain("Far Popular Store");
            assertThat(names).contains("Near Popular 1", "Near Popular 2");
            assertThat(res.size()).isEqualTo(2);
        }

        @Test
        @DisplayName("limit is clamped to at most 100")
        void limitIsClampedToAtMost100() throws Exception {
            var outlet = newOutlet();
            // Request limit=200, ensure endpoint does not fail and returns valid list
            var res = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=200").at("/data");
            assertThat(res.isArray()).isTrue();
            assertThat(res.size()).isLessThanOrEqualTo(100);
        }

        @Test
        @DisplayName("filters and sorts popular suppliers by openNow, minRating, radiusKm, and sort=rating")
        void popularFiltersAndSort() throws Exception {
            var outlet = newOutlet();
            String run = "P" + System.nanoTime();
            long[] prods = new long[10];
            for (int i = 0; i < 10; i++) {
                prods[i] = TestCatalog.freshProduct(jdbc, "popitem" + i);
            }

            // Store A: 5-star, open, near (HYD_LAT, HYD_LON ~0.5 km)
            var storeA = newStore(run + " PopA_5star_near", HYD_LAT, HYD_LON);
            for (int i = 0; i < 10; i++) stock(storeA, prods[i], code("PA" + i, prods[i]), "100");
            // Store B: 5-star, open, farther (NEARBY_LAT, NEARBY_LON ~7 km)
            var storeB = newStore(run + " PopB_5star_far", NEARBY_LAT, NEARBY_LON);
            for (int i = 0; i < 10; i++) stock(storeB, prods[i], code("PB" + i, prods[i]), "100");
            // Store C: 3-star, open, near
            var storeC = newStore(run + " PopC_3star_near", HYD_LAT, HYD_LON);
            for (int i = 0; i < 10; i++) stock(storeC, prods[i], code("PC" + i, prods[i]), "100");
            // Store D: unrated, open, near
            var storeD = newStore(run + " PopD_unrated_near", HYD_LAT, HYD_LON);
            for (int i = 0; i < 10; i++) stock(storeD, prods[i], code("PD" + i, prods[i]), "100");
            // Store E: 5-star, closed, near
            var storeE = newStore(run + " PopE_5star_closed", HYD_LAT, HYD_LON);
            for (int i = 0; i < 10; i++) stock(storeE, prods[i], code("PE" + i, prods[i]), "100");
            jdbc.update("update supplier_store set operating_hours_json = ? where id = ?",
                    "{\"days\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"],\"opensAt\":\"01:00\",\"closesAt\":\"01:01\"}",
                    storeE.storeId());

            // Ratings
            rateStore(outlet, storeA, 5);
            rateStore(outlet, storeB, 5);
            rateStore(outlet, storeC, 3);
            rateStore(outlet, storeE, 5);

            // 1. sort=rating: 5-star stores first (A before B), then 3-star C, then unrated D
            var resRating = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=100&sort=rating").at("/data");
            var namesRating = resRating.findValuesAsText("supplierName").stream()
                    .filter(n -> n.startsWith(run)).toList();
            assertThat(namesRating).containsSubsequence(
                    run + " PopA_5star_near",
                    run + " PopB_5star_far",
                    run + " PopC_3star_near",
                    run + " PopD_unrated_near");

            // 2. minRating=4: excludes Store C (3-star) and Store D (unrated)
            var resMinRating = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=100&minRating=4").at("/data");
            var namesMinRating = resMinRating.findValuesAsText("supplierName").stream()
                    .filter(n -> n.startsWith(run)).toList();
            assertThat(namesMinRating).contains(run + " PopA_5star_near", run + " PopB_5star_far", run + " PopE_5star_closed");
            assertThat(namesMinRating).doesNotContain(run + " PopC_3star_near", run + " PopD_unrated_near");

            // 3. openNow=true: excludes Store E (closed)
            var resOpenNow = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=100&openNow=true").at("/data");
            var namesOpenNow = resOpenNow.findValuesAsText("supplierName").stream()
                    .filter(n -> n.startsWith(run)).toList();
            assertThat(namesOpenNow).contains(run + " PopA_5star_near", run + " PopB_5star_far", run + " PopC_3star_near", run + " PopD_unrated_near");
            assertThat(namesOpenNow).doesNotContain(run + " PopE_5star_closed");

            // 4. radiusKm=5: excludes Store B (~7 km away)
            var resRadius = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?limit=100&radiusKm=5").at("/data");
            var namesRadius = resRadius.findValuesAsText("supplierName").stream()
                    .filter(n -> n.startsWith(run)).toList();
            assertThat(namesRadius).contains(run + " PopA_5star_near", run + " PopC_3star_near");
            assertThat(namesRadius).doesNotContain(run + " PopB_5star_far");

            // 5. Unknown sort returns 422
            var unknownSort = api.get(outlet.token(),
                    "/api/v1/outlets/" + outlet.outletId() + "/suppliers/popular?sort=bogus");
            assertThat(unknownSort.at("/error/code").asText()).isEqualTo("VALIDATION_ERROR");
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

    private void rateStore(Outlet outlet, Store store, int stars) {
        Long buyerUserId = jdbc.queryForObject("select id from users limit 1", Long.class);

        jdbc.update("""
                insert into procurement (outlet_id, created_by, status, approval_status,
                                          payment_method, payment_status, total_amount, created_at, updated_at, version)
                values (?, ?, 'SUBMITTED', 'NOT_REQUIRED', 'PREPAID', 'CAPTURED', 500.00, now(6), now(6), 0)
                """, outlet.outletId(), buyerUserId);
        Long procurementId = jdbc.queryForObject(
                "select id from procurement where outlet_id = ? order by id desc limit 1", Long.class, outlet.outletId());

        String orderNumber = "SO-RATE-" + System.nanoTime();
        jdbc.update("""
                insert into supplier_order (procurement_id, supplier_store_id, outlet_id,
                                            order_number, status, total_amount, accepted_amount,
                                            delivery_mode, payment_method, payment_status, created_at, updated_at, version)
                values (?, ?, ?, ?, 'COMPLETED', 500.00, 500.00, 'SUPPLIER_DELIVERY', 'PREPAID', 'CAPTURED', now(6), now(6), 0)
                """, procurementId, store.storeId(), outlet.outletId(), orderNumber);
        Long supplierOrderId = jdbc.queryForObject(
                "select id from supplier_order where order_number = ?", Long.class, orderNumber);

        jdbc.update("""
                insert into rating (supplier_order_id, outlet_id, supplier_store_id,
                                    overall_rating, moderation_status, rated_by, version)
                values (?, ?, ?, ?, 'PUBLISHED', ?, 0)
                """, supplierOrderId, outlet.outletId(), store.storeId(), stars, buyerUserId);
    }
}
