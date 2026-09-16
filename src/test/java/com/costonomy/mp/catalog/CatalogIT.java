package com.costonomy.mp.catalog;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Catalog: SKUs, offer supersession, comparison, and bulk import.
 *
 * <p>The properties under test are the ones that are expensive to get wrong —
 * a price edit destroying history, an offline supplier's offers still appearing
 * purchasable, and an import writing to the catalog before anyone confirmed it.
 */
@AutoConfigureMockMvc
class CatalogIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    /** A supplier with one active, tradeable store. */
    private record Supplier(String token, long supplierId, long storeId) {
    }

    private Supplier newSupplier(String name) throws Exception {
        String token = api.loginFresh();
        JsonNode created = api.post(token, "/api/v1/suppliers", Map.of(
                "legalName", name + " Pvt Ltd",
                "displayName", name,
                "firstStore", Map.of(
                        "name", name + " store",
                        "addressLine1", "Road No 36",
                        "city", "Hyderabad",
                        "state", "Telangana"))).get("data");

        long supplierId = created.get("id").asLong();
        long storeId = created.get("stores").get(0).get("id").asLong();

        // Activate directly: verification and activation are an operations flow
        // covered by TenantIsolationIT, and this suite is about the catalog.
        jdbc.update("update supplier_organization set lifecycle_status = 'ACTIVE', "
                + "verification_status = 'VERIFIED' where id = ?", supplierId);
        TestCatalog.tradesAroundTheClock(jdbc, supplierId);

        return new Supplier(token, supplierId, storeId);
    }

    /**
     * A canonical product for this test alone.
     *
     * <p>The Canonical nested class deliberately uses the seeded catalog, because
     * that is what it is testing. Everything else asserts exactly which offers or
     * SKUs exist and must not share a product with the rest of the suite — see
     * {@link TestCatalog}.
     */
    private long freshProduct(String label) {
        return TestCatalog.freshProduct(jdbc, label);
    }

    // ── Canonical catalog ────────────────────────────────────────────────

    @Nested
    @DisplayName("canonical catalog")
    class Canonical {

        @Test
        @DisplayName("the seeded catalog is browsable")
        void catalogIsSeeded() throws Exception {
            String token = api.loginFresh();

            assertThat(api.get(token, "/api/v1/categories").at("/data")).isNotEmpty();
            assertThat(api.get(token, "/api/v1/products?size=5").at("/data")).hasSize(5);
        }

        @Test
        @DisplayName("search matches a product's own name")
        void searchByName() throws Exception {
            var results = api.get(api.loginFresh(), "/api/v1/search/products?q=paneer").at("/data");
            assertThat(results.findValuesAsText("name")).contains("Paneer");
        }

        @Test
        @DisplayName("search matches a configured alias")
        void searchByAlias() throws Exception {
            // "dahi" finds Curd because the alias is configured, not because a
            // matcher guessed (doc 07 §2).
            var results = api.get(api.loginFresh(), "/api/v1/search/products?q=dahi").at("/data");
            assertThat(results.findValuesAsText("name")).contains("Curd");

            var maida = api.get(api.loginFresh(), "/api/v1/search/products?q=maida").at("/data");
            assertThat(maida.findValuesAsText("name")).contains("Refined Wheat Flour");
        }

        @Test
        @DisplayName("search is case- and spacing-insensitive")
        void searchToleratesTyping() throws Exception {
            String token = api.loginFresh();
            assertThat(api.get(token, "/api/v1/search/products?q=  PANEER ").at("/data")).isNotEmpty();
        }

        @Test
        @DisplayName("a term nobody configured returns nothing rather than a bad guess")
        void unknownTermReturnsNothing() throws Exception {
            assertThat(api.get(api.loginFresh(), "/api/v1/search/products?q=zzzznotathing").at("/data"))
                    .isEmpty();
        }
    }

    // ── SKUs and offers ──────────────────────────────────────────────────

    @Nested
    @DisplayName("SKUs and offers")
    class Skus {

        @Test
        @DisplayName("a SKU must map to a canonical product that exists")
        void skuMustMapToCanonicalProduct() throws Exception {
            var supplier = newSupplier("ABC Foods");

            int status = api.postStatus(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus", Map.of(
                            "canonicalProductId", 99999999L,
                            "name", "Mystery Paneer",
                            "packSize", 1,
                            "packUnit", "KG",
                            "sellingPrice", 410,
                            "gstRate", 5));

            // Suppliers map onto the platform's products; they do not invent them.
            // If they could, two suppliers' paneer would never appear side by side.
            assertThat(status).isEqualTo(400);
        }

        @Test
        @DisplayName("a price change supersedes the old offer instead of overwriting it")
        void priceChangePreservesHistory() throws Exception {
            var supplier = newSupplier("ABC Foods");
            long paneer = freshProduct("paneer");

            long skuId = api.post(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus", Map.of(
                            "canonicalProductId", paneer,
                            "skuCode", "PNR-1KG",
                            "name", "Amul Paneer 1kg",
                            "brandName", "Amul",
                            "packSize", 1,
                            "packUnit", "KG",
                            "sellingPrice", "410.00",
                            "gstRate", "5"))
                    .at("/data/id").asLong();

            api.patchStatus(supplier.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "438.00"));

            var history = api.get(supplier.token(),
                    "/api/v1/supplier-skus/" + skuId + "/price-history").at("/data");

            // Doc 02 §4: historical commercial values must survive. An order placed
            // at ₹410 last Tuesday has to stay explicable.
            assertThat(history).hasSize(2);
            assertThat(history.get(0).get("sellingPrice").asDouble()).isEqualTo(438.00);
            assertThat(history.get(0).get("effectiveTo").isNull())
                    .describedAs("the current offer is still open")
                    .isTrue();
            assertThat(history.get(1).get("sellingPrice").asDouble()).isEqualTo(410.00);
            assertThat(history.get(1).get("effectiveTo").isNull())
                    .describedAs("the superseded offer is closed")
                    .isFalse();
        }

        @Test
        @DisplayName("renaming a SKU does not manufacture a price-history entry")
        void identityEditDoesNotTouchTheOffer() throws Exception {
            var supplier = newSupplier("ABC Foods");
            long skuId = createSku(supplier, freshProduct("paneer"), "PNR-1KG", "410.00");

            api.patchStatus(supplier.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("name", "Amul Fresh Paneer 1kg"));

            assertThat(api.get(supplier.token(),
                    "/api/v1/supplier-skus/" + skuId + "/price-history").at("/data"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("re-submitting the same price does not create a history entry")
        void unchangedPriceIsNotAChange() throws Exception {
            var supplier = newSupplier("ABC Foods");
            long skuId = createSku(supplier, freshProduct("paneer"), "PNR-1KG", "410.00");

            // Different scale, same value. BigDecimal.equals would say these
            // differ; a supplier re-uploading their price list weekly would then
            // accumulate a fake price change every week.
            api.patchStatus(supplier.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "410.0000"));

            assertThat(api.get(supplier.token(),
                    "/api/v1/supplier-skus/" + skuId + "/price-history").at("/data"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("a duplicate SKU code within a store is rejected")
        void duplicateSkuCodeRejected() throws Exception {
            var supplier = newSupplier("ABC Foods");
            long paneer = freshProduct("paneer");
            createSku(supplier, paneer, "PNR-1KG", "410.00");

            int status = api.postStatus(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus", Map.of(
                            "canonicalProductId", paneer,
                            "skuCode", "PNR-1KG",
                            "name", "Another paneer",
                            "packSize", 1, "packUnit", "KG",
                            "sellingPrice", "500", "gstRate", "5"));

            assertThat(status).isEqualTo(409);
        }

        @Test
        @DisplayName("another supplier cannot read or reprice this store's catalog")
        void catalogIsScopedToTheStore() throws Exception {
            var mine = newSupplier("ABC Foods");
            var theirs = newSupplier("XYZ Traders");
            long skuId = createSku(mine, freshProduct("paneer"), "PNR-1KG", "410.00");

            assertThat(api.getStatus(theirs.token(),
                    "/api/v1/supplier-stores/" + mine.storeId() + "/skus")).isEqualTo(404);

            // The worst scope failure available in this system: repricing a
            // competitor's catalog.
            assertThat(api.patchStatus(theirs.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("sellingPrice", "1"))).isEqualTo(404);

            assertThat(api.get(mine.token(), "/api/v1/supplier-skus/" + skuId + "/price-history")
                    .at("/data")).hasSize(1);
        }
    }

    // ── Comparison ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("supplier comparison")
    class Comparison {

        @Test
        @DisplayName("offers from several suppliers appear against one product")
        void offersAreComparable() throws Exception {
            long paneer = freshProduct("paneer");
            var one = newSupplier("ABC Foods");
            var two = newSupplier("XYZ Traders");
            createSku(one, paneer, "ABC-PNR", "440.00");
            createSku(two, paneer, "XYZ-PNR", "410.00");

            var offers = api.get(api.loginFresh(), "/api/v1/products/" + paneer + "/offers").at("/data");

            // The entire point of a canonical product: two suppliers' SKUs, side
            // by side, comparable.
            assertThat(offers.size()).isGreaterThanOrEqualTo(2);
            assertThat(offers.get(0).get("sellingPrice").asDouble())
                    .describedAs("cheapest first as a deterministic baseline")
                    .isLessThanOrEqualTo(offers.get(1).get("sellingPrice").asDouble());
        }

        @Test
        @DisplayName("an out-of-stock offer is not purchasable")
        void outOfStockIsExcluded() throws Exception {
            long curd = freshProduct("curd");
            var supplier = newSupplier("ABC Foods");
            long skuId = createSku(supplier, curd, "ABC-CURD", "80.00");

            api.patchStatus(supplier.token(), "/api/v1/supplier-skus/" + skuId,
                    Map.of("availability", "OUT_OF_STOCK"));

            assertThat(api.get(api.loginFresh(), "/api/v1/products/" + curd + "/offers").at("/data"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an offline store's offers disappear from comparison")
        void offlineStoreIsExcluded() throws Exception {
            long butter = freshProduct("butter");
            var supplier = newSupplier("ABC Foods");
            createSku(supplier, butter, "ABC-BUTTER", "520.00");

            assertThat(api.get(api.loginFresh(), "/api/v1/products/" + butter + "/offers").at("/data"))
                    .hasSize(1);

            api.patchStatus(supplier.token(), "/api/v1/supplier-stores/" + supplier.storeId(),
                    Map.of("status", "OFFLINE"));

            // Doc 03 §15: an order can only be created against an ACTIVE store, so
            // showing the offer would be offering something that cannot be bought.
            assertThat(api.get(api.loginFresh(), "/api/v1/products/" + butter + "/offers").at("/data"))
                    .isEmpty();
        }

        @Test
        @DisplayName("a suspended supplier's offers disappear even if the store is active")
        void suspendedSupplierIsExcluded() throws Exception {
            long ghee = freshProduct("ghee");
            var supplier = newSupplier("ABC Foods");
            createSku(supplier, ghee, "ABC-GHEE", "620.00");

            jdbc.update("update supplier_organization set lifecycle_status = 'SUSPENDED' where id = ?",
                    supplier.supplierId());

            assertThat(api.get(api.loginFresh(), "/api/v1/products/" + ghee + "/offers").at("/data"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an offer never carries a commission figure")
        void noCommissionInOffers() throws Exception {
            long paneer = freshProduct("paneer");
            var supplier = newSupplier("ABC Foods");
            createSku(supplier, paneer, "ABC-PNR-2", "410.00");

            String body = api.get(api.loginFresh(), "/api/v1/products/" + paneer + "/offers").toString();

            // Guardrail 9. A restaurant comparing suppliers must not be shown, or
            // ranked by, what Mandi earns.
            assertThat(body.toLowerCase()).doesNotContain("commission", "takerate", "margin");
        }
    }

    // ── Bulk import ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("bulk import")
    class Import {

        @Test
        @DisplayName("upload validates and previews without writing anything")
        void uploadDoesNotWriteToCatalog() throws Exception {
            var supplier = newSupplier("ABC Foods");

            String csv = """
                    SKU Code,Product Name,Brand,Pack Size,Unit,Selling Price,GST,Availability
                    IMP-1,Paneer,Amul,1,KG,410.00,5,Available
                    IMP-2,Curd,Amul,1,KG,80.00,5,In Stock
                    """;

            var preview = upload(supplier, "prices.csv", csv);

            assertThat(preview.get("status").asText()).isEqualTo("VALIDATED");
            assertThat(preview.get("totalRows").asInt()).isEqualTo(2);
            assertThat(preview.get("validRows").asInt()).isEqualTo(2);

            // Doc 25: no silent partial import. Nothing exists until a person
            // looks at the preview and confirms.
            assertThat(api.get(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus").at("/data"))
                    .isEmpty();
        }

        @Test
        @DisplayName("confirming applies the valid rows")
        void confirmApplies() throws Exception {
            var supplier = newSupplier("ABC Foods");
            String csv = """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    IMP-10,Paneer,1,KG,"₹1,410.00",5
                    IMP-11,Sugar,50,KG,2400,5
                    """;

            long importId = upload(supplier, "prices.csv", csv).get("importId").asLong();
            var summary = api.post(supplier.token(),
                    "/api/v1/catalog/imports/" + importId + "/confirm", Map.of()).at("/data");

            assertThat(summary.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(summary.get("createdSkus").asInt()).isEqualTo(2);

            var skus = api.get(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus").at("/data");
            assertThat(skus).hasSize(2);
            // "₹1,410.00" parsed, rather than rejected over a currency symbol.
            assertThat(skus.findValuesAsText("skuCode")).containsExactlyInAnyOrder("IMP-10", "IMP-11");
        }

        @Test
        @DisplayName("invalid rows are reported per row and field, and skipped")
        void invalidRowsAreReportedNotGuessed() throws Exception {
            var supplier = newSupplier("ABC Foods");
            String csv = """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST,Availability
                    OK-1,Paneer,1,KG,410,5,Available
                    BAD-1,Paneer,1,KG,call for price,5,Available
                    BAD-2,Definitely Not A Real Product,1,KG,100,5,Available
                    BAD-3,Curd,1,KG,80,250,Available
                    BAD-4,Sugar,50,KG,2400,5,maybe
                    """;

            var preview = upload(supplier, "prices.csv", csv);

            assertThat(preview.get("totalRows").asInt()).isEqualTo(5);
            assertThat(preview.get("validRows").asInt()).isEqualTo(1);
            assertThat(preview.get("invalidRows").asInt()).isEqualTo(4);

            var rows = preview.get("rows");
            // Doc 40 names each of these. The line numbers match the supplier's
            // own spreadsheet, so they can go and fix them.
            assertThat(errorFields(rows, 2)).contains("sellingPrice");
            assertThat(errorFields(rows, 3)).contains("canonicalProduct");
            assertThat(errorFields(rows, 4)).contains("gstRate");
            assertThat(errorFields(rows, 5)).contains("availability");

            long importId = preview.get("importId").asLong();
            var summary = api.post(supplier.token(),
                    "/api/v1/catalog/imports/" + importId + "/confirm", Map.of()).at("/data");

            assertThat(summary.get("importedRows").asInt()).isEqualTo(1);
            assertThat(api.get(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus").at("/data")).hasSize(1);
        }

        @Test
        @DisplayName("a re-upload updates existing SKUs and keeps their price history")
        void reuploadUpdatesAndKeepsHistory() throws Exception {
            var supplier = newSupplier("ABC Foods");

            long first = upload(supplier, "week1.csv", """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    W-1,Paneer,1,KG,410,5
                    """).get("importId").asLong();
            api.post(supplier.token(), "/api/v1/catalog/imports/" + first + "/confirm", Map.of());

            long second = upload(supplier, "week2.csv", """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    W-1,Paneer,1,KG,438,5
                    """).get("importId").asLong();
            var summary = api.post(supplier.token(),
                    "/api/v1/catalog/imports/" + second + "/confirm", Map.of()).at("/data");

            // Updating a weekly price list is the normal way to use this, so a
            // matching SKU code updates rather than colliding.
            assertThat(summary.get("updatedSkus").asInt()).isEqualTo(1);
            assertThat(summary.get("createdSkus").asInt()).isZero();

            var skus = api.get(supplier.token(),
                    "/api/v1/supplier-stores/" + supplier.storeId() + "/skus").at("/data");
            assertThat(skus).hasSize(1);

            long skuId = skus.get(0).get("id").asLong();
            assertThat(api.get(supplier.token(),
                    "/api/v1/supplier-skus/" + skuId + "/price-history").at("/data")).hasSize(2);
        }

        @Test
        @DisplayName("a SKU code repeated within one file is caught before import")
        void duplicateWithinFileIsCaught() throws Exception {
            var supplier = newSupplier("ABC Foods");
            var preview = upload(supplier, "dupes.csv", """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    DUP-1,Paneer,1,KG,410,5
                    DUP-1,Curd,1,KG,80,5
                    """);

            // Caught at preview, where the supplier can fix it, rather than as a
            // failed row after they have confirmed.
            assertThat(preview.get("invalidRows").asInt()).isEqualTo(1);
            assertThat(errorFields(preview.get("rows"), 2)).contains("skuCode");
        }

        @Test
        @DisplayName("column headers are matched loosely")
        void headersAreMatchedLoosely() throws Exception {
            var supplier = newSupplier("ABC Foods");
            // A real supplier's export, not our template.
            var preview = upload(supplier, "theirs.csv", """
                    Item Code,Description,Make,Size,UOM,Rate,Tax Rate
                    L-1,Paneer,Amul,1,KG,410,5
                    """);

            assertThat(preview.get("validRows").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("an import cannot be confirmed twice")
        void confirmIsNotRepeatable() throws Exception {
            var supplier = newSupplier("ABC Foods");
            long importId = upload(supplier, "once.csv", """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    ONCE-1,Paneer,1,KG,410,5
                    """).get("importId").asLong();

            api.post(supplier.token(), "/api/v1/catalog/imports/" + importId + "/confirm", Map.of());

            assertThat(api.postStatus(supplier.token(),
                    "/api/v1/catalog/imports/" + importId + "/confirm", Map.of()))
                    .isEqualTo(409);
        }

        @Test
        @DisplayName("a file with no recognisable product column is rejected outright")
        void unusableFileIsRejected() throws Exception {
            var supplier = newSupplier("ABC Foods");
            // Better one clear file-level error than thousands of identical
            // row-level ones.
            assertThat(uploadStatus(supplier, "wrong.csv", "Foo,Bar\n1,2\n")).isEqualTo(400);
        }

        @Test
        @DisplayName("another supplier cannot upload into this store")
        void importIsScoped() throws Exception {
            var mine = newSupplier("ABC Foods");
            var theirs = newSupplier("XYZ Traders");

            assertThat(uploadStatus(theirs, mine.storeId(), "x.csv", """
                    SKU Code,Product Name,Pack Size,Unit,Price,GST
                    X-1,Paneer,1,KG,1,5
                    """)).isEqualTo(404);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private long createSku(Supplier supplier, long productId, String code, String price)
            throws Exception {
        return api.post(supplier.token(),
                "/api/v1/supplier-stores/" + supplier.storeId() + "/skus", Map.of(
                        "canonicalProductId", productId,
                        "skuCode", code,
                        "name", code,
                        "packSize", 1,
                        "packUnit", "KG",
                        "sellingPrice", price,
                        "gstRate", "5")).at("/data/id").asLong();
    }

    private JsonNode upload(Supplier supplier, String fileName, String csv) throws Exception {
        String body = mvc.perform(multipart(
                        "/api/v1/supplier-stores/" + supplier.storeId() + "/catalog/import")
                        .file(new MockMultipartFile("file", fileName, "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + supplier.token())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).at("/data");
    }

    private int uploadStatus(Supplier supplier, String fileName, String csv) throws Exception {
        return uploadStatus(supplier, supplier.storeId(), fileName, csv);
    }

    private int uploadStatus(Supplier supplier, long storeId, String fileName, String csv)
            throws Exception {
        return mvc.perform(multipart("/api/v1/supplier-stores/" + storeId + "/catalog/import")
                        .file(new MockMultipartFile("file", fileName, "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + supplier.token())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andReturn().getResponse().getStatus();
    }

    private static java.util.List<String> errorFields(JsonNode rows, int rowNumber) {
        for (JsonNode row : rows) {
            if (row.get("rowNumber").asInt() == rowNumber) {
                return row.get("errors").findValuesAsText("field");
            }
        }
        return java.util.List.of();
    }
}
