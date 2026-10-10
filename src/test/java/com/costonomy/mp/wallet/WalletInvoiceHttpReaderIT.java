package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.invoice.CostAppStub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static com.costonomy.mp.wallet.InvoiceTestSupport.*;
import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The real HTTP reader wired into the app and pointed at a local stub of the cost app (D-113). The stub's answer
 * carries item-master, supplier and user data: none of it may reach our table or our API.
 */
@AutoConfigureMockMvc
class WalletInvoiceHttpReaderIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";
    private static final CostAppStub STUB = new CostAppStub();
    private static final Path STORE = tempDir();
    /** The outlet-map settings, read by the app on each use (D-115); the outlet-map test changes them. */
    private static volatile String outletMap = "";
    private static volatile String outletFallback = "true";

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mp-invoices-http-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reader(DynamicPropertyRegistry registry) {
        registry.add("costonomy.mp.invoices.reader.provider", () -> "HTTP");
        registry.add("costonomy.mp.invoices.reader.base-url", STUB::baseUrl);
        registry.add("costonomy.mp.invoices.reader.username", () -> CostAppStub.USERNAME);
        registry.add("costonomy.mp.invoices.reader.password", () -> CostAppStub.PASSWORD);
        registry.add("costonomy.mp.invoices.reader.outlet", () -> "5");
        registry.add("costonomy.mp.invoices.reader.user-id", () -> "6");
        registry.add("costonomy.mp.invoices.storage.local.directory", STORE::toString);
        registry.add("costonomy.mp.invoices.cost-outlet-map", () -> outletMap);
        registry.add("costonomy.mp.invoices.cost-outlet-fallback", () -> outletFallback);
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.costonomy.mp.wallet.invoice.service.InvoiceLookupService lookups;

    private Buyer buyer;
    private long entry;

    @BeforeEach
    void setUp() throws Exception {
        outletMap = "";
        outletFallback = "true";
        STUB.reset();
        STUB.requireAuth(true);
        var t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit(AT, TOP_UP, "9000", "start");
        seed.debit(AT, ORDER_PAYMENT, "2820", "Order paid from wallet");
        entry = seed.lastId;
    }

    private JsonNode current() throws Exception {
        return call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry)).data();
    }

    private void awaitStatus(String status) {
        await().atMost(15, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(current().get("status").asText()).isEqualTo(status));
    }

    @Test
    @DisplayName("signs in, then the request has the issued token, the configured outlet and user and a `file` part per page; the first good entry is read")
    void requestAndReading() throws Exception {
        var created = upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart(),
                part("b.pdf", "application/pdf", pdf()));
        assertThat(created.status()).isEqualTo(201);
        awaitStatus("READ");

        var seen = STUB.seen("/item-purchase/invoice/extract").get(0);
        assertThat(STUB.issuedTokens()).contains(seen.authorization().substring("Bearer ".length()));
        assertThat(seen.query()).isEqualTo("pageName=WalletBill&outlet=5&userId=6");
        assertThat(seen.bodyText().split("name=\"file\"", -1)).hasSize(3);
        // The made-up file names are ours, never the customer's.
        assertThat(seen.bodyText()).contains("filename=\"page-1.jpg\"").contains("filename=\"page-2.pdf\"")
                .doesNotContain("receipt.jpg");

        var d = current();
        assertThat(d.at("/reading/vendorName").asText()).isEqualTo("KOSTA Delights");
        assertThat(d.at("/reading/items/0/name").asText()).isNotEqualTo("WRONG FIRST");
        assertThat(d.at("/reading/total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(d.at("/check/matches").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("nothing of sku, supplier, user or cost data is stored in reading_json or returned by the API")
    void internalDataNeverStoredOrReturned() throws Exception {
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitStatus("READ");

        String stored = jdbc.queryForObject("select reading_json from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId());
        String api = call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry)).body().toString();
        String details = t2().call("GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId()
                + "/wallet/transactions/" + entry, null, null).body().toString();
        String everything = jdbc.queryForList("select concat_ws('|', vendor_name, invoice_number, invoice_date_text,"
                + " currency, reading_json, error_text) from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId()).toString();

        for (String where : new String[]{stored, api, details, everything}) {
            for (String forbidden : new String[]{CostAppStub.SKU_MARKER, CostAppStub.SUPPLIER_MARKER,
                    CostAppStub.USER_MARKER, CostAppStub.COST_MARKER, "36ABCDE1234F1Z5", "itemMaster", "yieldPercentage",
                    "createdBy", "brandName", "hsn", "contactName", "taxId", "paymentTerms"}) {
                assertThat(where).describedAs("must not contain '%s'", forbidden).doesNotContain(forbidden);
            }
        }
        // The matches are kept, only as id, name, unit, price and category.
        var reading = json.readTree(stored);
        assertThat(reading.at("/items/0/skuMatch/id").asLong()).isEqualTo(9465);
        // Money is kept as text in the JSON column (D-115), so MySQL does not turn it into a double.
        assertThat(new java.math.BigDecimal(reading.at("/items/0/skuMatch/unitPrice").asText())).isEqualByComparingTo("360");
        assertThat(reading.at("/supplierMatch/name").asText()).isEqualTo("Kosta Delights - Sea Food");
        assertThat(reading.at("/items/2/skuMatch").isMissingNode() || reading.at("/items/2/skuMatch").isNull()).isTrue();
        assertThat(stored).contains("KOSTA Delights");
    }

    private WalletTopUpSupport t2() {
        return new WalletTopUpSupport(mvc, json, jdbc);
    }

    @Test
    @DisplayName("an answer whose entries all have an error is not a reading: the bill stays READING")
    void errorEntryIsNotARead() throws Exception {
        STUB.answer(200, "[{\"error\":\"Could not read this page\",\"totalAmount\":2820,\"vendorName\":\"Ghost\"}]");
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> assertThat(STUB.calls()).isGreaterThanOrEqualTo(1));
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(current().get("attempts").asInt()).isEqualTo(1));

        var d = current();
        assertThat(d.get("status").asText()).isEqualTo("READING");
        assertThat(d.get("reading").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select vendor_name from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId())).isNull();
        assertThat(jdbc.queryForObject("select reading_json from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId())).isNull();
    }

    @Test
    @DisplayName("lookups go to the cost app as GETs for the configured cost outlet, never the caller's, and keep only allowed fields")
    void lookupsThroughTheCostApp() throws Exception {
        String base = "/api/v1/outlets/" + buyer.outletId() + "/invoice-lookups/";
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(lookups, "cache")).clear();
        var skus = call(mvc, json, "GET", buyer.token(), base + "skus?q=prawns&limit=2");
        assertThat(skus.status()).describedAs(skus.body().toString()).isEqualTo(200);
        assertThat(skus.data()).hasSize(2);
        assertThat(skus.data().get(0).get("name").asText()).isEqualTo("Prawns 16/20");
        assertThat(skus.data().get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("360");
        assertThat(skus.data().get(1).get("name").asText()).isEqualTo("PRAWNS 21/25");
        var suppliers = call(mvc, json, "GET", buyer.token(), base + "suppliers?q=kosta");
        assertThat(suppliers.data()).hasSize(1);
        assertThat(suppliers.data().get(0).get("id").asLong()).isEqualTo(2001);

        assertThat(STUB.seen("/sku/list/expand")).hasSize(1);
        assertThat(STUB.seen("/supplier/list")).hasSize(1);
        for (var seen : STUB.seen()) {
            if (seen.path().startsWith("/auth/")) {
                continue;
            }
            assertThat(seen.method()).describedAs(seen.path()).isEqualTo("GET");
            assertThat(seen.query()).isEqualTo("outlet=5&userId=6&status=false")
                    .doesNotContain("outlet=" + buyer.outletId());
        }
        for (String body : new String[]{skus.body().toString(), suppliers.body().toString()}) {
            for (String forbidden : new String[]{CostAppStub.SKU_MARKER, CostAppStub.SUPPLIER_MARKER,
                    CostAppStub.USER_MARKER, CostAppStub.COST_MARKER, "createdBy", "phone"}) {
                assertThat(body).doesNotContain(forbidden);
            }
        }
        // Another outlet's lookups are refused before anything is asked of the cost app.
        var other = t2().newBuyer();
        int before = STUB.calls();
        var refused = call(mvc, json, "GET", other.token(), base + "skus");
        assertThat(refused.status()).isIn(403, 404);
        assertThat(STUB.calls()).isEqualTo(before);
        assertThat(call(mvc, json, "GET", null, base + "skus").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("no token or password ever reaches an answer of ours")
    void secretsNeverInResponses() throws Exception {
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitStatus("READ");
        String base = "/api/v1/outlets/" + buyer.outletId();
        var bodies = new java.util.ArrayList<String>();
        bodies.add(call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry)).body().toString());
        bodies.add(call(mvc, json, "GET", buyer.token(), base + "/invoice-lookups/skus").body().toString());
        bodies.add(call(mvc, json, "GET", buyer.token(), base + "/invoice-lookups/suppliers").body().toString());
        bodies.add(t2().call("GET", buyer.token(), base + "/wallet/transactions/" + entry, null, null).body().toString());
        var secrets = new java.util.ArrayList<>(STUB.issuedTokens());
        secrets.add(CostAppStub.PASSWORD);
        assertThat(STUB.issuedTokens()).isNotEmpty();
        for (String body : bodies) {
            for (String secret : secrets) {
                assertThat(body).doesNotContain(secret);
            }
        }
    }

    @Test
    @DisplayName("D-115 (M2): an outlet with no cost outlet: lookups 403 without asking the cost app, and its bill is read without any match; once mapped, its own cost outlet is used")
    void outletMap() throws Exception {
        outletFallback = "false";
        outletMap = "999999:7";                                             // some other outlet only
        String base = "/api/v1/outlets/" + buyer.outletId() + "/invoice-lookups/";
        int before = STUB.calls();
        var refused = call(mvc, json, "GET", buyer.token(), base + "skus");
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("INVOICE_LOOKUP_NOT_AVAILABLE");
        assertThat(refused.body().at("/error/message").asText())
                .isEqualTo("Supplier and SKU lists are not available for this outlet. You can still type a name.");
        assertThat(STUB.calls()).isEqualTo(before);

        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        awaitStatus("READ");
        assertThat(STUB.seen("/item-purchase/invoice/extract").get(0).query())
                .isEqualTo("pageName=WalletBill&outlet=5&userId=6");             // the reader's own outlet
        var d = current();
        assertThat(d.at("/reading/supplierMatch").isNull()).isTrue();
        assertThat(d.at("/reading/items/0/skuMatch").isNull()).isTrue();
        assertThat(d.at("/draft/supplier/id").isNull()).isTrue();
        assertThat(d.at("/draft/items/0/sku").isNull()).isTrue();
        String stored = jdbc.queryForObject("select reading_json from wallet_entry_invoice where outlet_id = ?",
                String.class, buyer.outletId());
        assertThat(stored).doesNotContain("Prawns 16/20").doesNotContain("Kosta Delights - Sea Food");

        // Mapped to cost outlet 7: lookups ask for 7, cached for 7 only.
        outletMap = buyer.outletId() + ":7,999999:8";
        STUB.lists("7", "[" + CostAppStub.supplier(4001, "Seven's Supplier", false) + "]", "[]");
        var suppliers = call(mvc, json, "GET", buyer.token(), base + "suppliers");
        assertThat(suppliers.status()).describedAs(suppliers.body().toString()).isEqualTo(200);
        assertThat(suppliers.data()).hasSize(1);
        assertThat(suppliers.data().get(0).get("id").asLong()).isEqualTo(4001);
        assertThat(STUB.seen("/supplier/list").get(STUB.seen("/supplier/list").size() - 1).query())
                .isEqualTo("outlet=7&userId=6&status=false");
    }
}
