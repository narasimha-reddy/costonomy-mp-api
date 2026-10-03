package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.wallet.invoice.costapi.CostApiSession;
import com.costonomy.mp.wallet.invoice.costapi.CostApiUnavailableException;
import com.costonomy.mp.wallet.invoice.reader.HttpInvoiceReader;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReadException;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader.InvoiceFile;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReader.ReadContext;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real HTTP reader against a local stub of the cost app. No real service is ever called. */
class HttpInvoiceReaderTest {

    // Not a credential: a made-up string for the stub to look for.
    private static final String TOKEN = "stub-token-for-tests-only";
    /** Marketplace outlet 1, mapped to cost outlet 77 (D-115). */
    private static final ReadContext CTX = new ReadContext(1, 2, 77L);
    private static CostAppStub stub;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void start() {
        stub = new CostAppStub();
    }

    @AfterAll
    static void stop() {
        stub.close();
    }

    @BeforeEach
    void reset() {
        stub.reset();
    }

    private HttpInvoiceReader reader(Duration timeout) {
        return new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl() + "/", () -> TOKEN), "0", "0",
                timeout);
    }

    private static List<InvoiceFile> pages() {
        return List.of(new InvoiceFile("page-1.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2}),
                new InvoiceFile("page-2.pdf", "application/pdf", "%PDF-1.4 body".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("(a) the request carries the bearer token, the query parameters and one multipart `file` part per page")
    void request() {
        reader(Duration.ofSeconds(5)).read(pages(), CTX);

        var seen = stub.seen().get(0);
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/item-purchase/invoice/extract");
        assertThat(seen.query()).isEqualTo("pageName=WalletBill&outlet=77&userId=0");
        assertThat(seen.authorization()).isEqualTo("Bearer " + TOKEN);
        assertThat(seen.contentType()).startsWith("multipart/form-data; boundary=");
        String body = seen.bodyText();
        assertThat(body.split("name=\"file\"", -1)).hasSize(3); // two parts
        assertThat(body).contains("filename=\"page-1.jpg\"\r\nContent-Type: image/jpeg");
        assertThat(body).contains("filename=\"page-2.pdf\"\r\nContent-Type: application/pdf");
        assertThat(body).contains("%PDF-1.4 body");
    }

    @Test
    @DisplayName("(c) the first entry without an error is the reading; the failed one before it is skipped")
    void firstWithoutError() {
        var reading = reader(Duration.ofSeconds(5)).read(pages(), CTX).reading();

        assertThat(reading.vendorName()).isEqualTo("KOSTA Delights");
        assertThat(reading.vendorAddress()).isEqualTo("Hyderabad");
        assertThat(reading.invoiceNumber()).isEqualTo("1631");
        assertThat(reading.invoiceDate()).isEqualTo("04/09/26");
        assertThat(reading.customerName()).isEqualTo("Delicia");
        assertThat(reading.currency()).isEqualTo("INR");
        assertThat(reading.total()).isEqualByComparingTo("2820");
        assertThat(reading.subtotal()).isEqualByComparingTo("2820");
        assertThat(reading.items()).extracting(InvoiceReading.Item::name)
                .containsExactly("16/20 prawns", "21/25 prawns", "30/50 prawns");
        var first = reading.items().get(0);
        assertThat(first.quantity()).isEqualByComparingTo("2");
        assertThat(first.unit()).isEqualTo("KG");
        assertThat(first.unitPrice()).isEqualByComparingTo("560");
        assertThat(first.total()).isEqualByComparingTo("1120");
        // Numbers written as text are read too.
        assertThat(reading.items().get(1).total()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("(b) only the allowed fields are kept: the bill's own, and of the cost app's sku and supplier only the match fields")
    void noInternalDataKept() throws Exception {
        var reading = reader(Duration.ofSeconds(5)).read(pages(), CTX).reading();
        String serialized = json.writeValueAsString(reading);

        for (String forbidden : new String[]{CostAppStub.SKU_MARKER, CostAppStub.SUPPLIER_MARKER,
                CostAppStub.USER_MARKER, CostAppStub.COST_MARKER, "\"sku\"", "\"supplier\"", "36ABCDE1234F1Z5",
                "createdBy", "updatedBy", "itemMaster", "yieldPercentage", "hsn", "paymentTerms", "customerAddress",
                "brandName", "phone", "taxId", "contactName", "masterItemId", "warnings", "111"}) {
            assertThat(serialized).describedAs("reading must not contain '%s'", forbidden).doesNotContain(forbidden);
        }
        // And the shape is exactly ours.
        var root = json.readTree(serialized);
        assertThat(names(root)).containsExactlyInAnyOrder("vendorName", "vendorAddress", "invoiceNumber",
                "invoiceDate", "customerName", "currency", "items", "subtotal", "tax", "delivery", "total",
                "supplierMatch", "invoiceCount");
        assertThat(names(root.get("items").get(0))).containsExactlyInAnyOrder("name", "quantity", "unit",
                "unitPrice", "total", "amount", "tax", "skuMatch");
        assertThat(names(root.at("/items/0/skuMatch"))).containsExactlyInAnyOrder("id", "name", "unit", "unitPrice",
                "categoryName");
        assertThat(names(root.get("supplierMatch"))).containsExactlyInAnyOrder("id", "name");
    }

    @Test
    @DisplayName("the matches: SKU id, name, unit, price per unit from itemPrice (not unitPrice), category; supplier id and name")
    void matches() {
        var reading = reader(Duration.ofSeconds(5)).read(pages(), CTX).reading();
        var first = reading.items().get(0);
        assertThat(first.skuMatch()).isEqualTo(new InvoiceReading.SkuMatch(9465L, "Prawns 16/20", "KG",
                new java.math.BigDecimal("360"), "Seafood"));
        assertThat(first.amount()).isEqualByComparingTo("1120");
        assertThat(first.tax()).isEqualByComparingTo("0");
        assertThat(reading.items().get(1).skuMatch().id()).isEqualTo(152L);
        assertThat(reading.items().get(2).skuMatch()).isNull();
        assertThat(reading.supplierMatch()).isEqualTo(new InvoiceReading.SupplierMatch(2001L, "Kosta Delights - Sea Food"));
    }

    @Test
    @DisplayName("a match without an id or name is dropped; long names are capped")
    void matchesCapped() {
        String longName = "P".repeat(400);
        stub.answer(200, "[{\"vendorName\":\"V\",\"totalAmount\":1,\"supplier\":{\"supplierName\":\"No id\"},"
                + "\"items\":[{\"itemName\":\"a\",\"sku\":{\"id\":5,\"skuName\":\"" + longName + "\",\"unit\":\"KG\"}},"
                + "{\"itemName\":\"b\",\"sku\":{\"id\":6}},{\"itemName\":\"c\",\"sku\":{\"skuName\":\"x\"}}]}]");
        var reading = reader(Duration.ofSeconds(5)).read(pages(), CTX).reading();
        assertThat(reading.supplierMatch()).isNull();
        assertThat(reading.items().get(0).skuMatch().name()).hasSize(InvoiceReading.MAX_NAME);
        assertThat(reading.items().get(0).skuMatch().unitPrice()).isNull();
        assertThat(reading.items().get(1).skuMatch()).isNull();
        assertThat(reading.items().get(2).skuMatch()).isNull();
    }

    private static java.util.Set<String> names(com.fasterxml.jackson.databind.JsonNode node) {
        var fields = new java.util.TreeSet<String>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    @Test
    @DisplayName("(d) an answer where every entry has an error, or an empty array, is no bill; it is not a reading")
    void errorEntriesAreNotAReading() {
        stub.answer(200, "[{\"error\":\"unreadable\",\"totalAmount\":5},{\"error\":\"also bad\"}]");
        var result = reader(Duration.ofSeconds(5)).read(pages(), CTX);
        assertThat(result.reading()).isNull();
        assertThat(result.problem()).isNotBlank();

        stub.answer(200, "[]");
        assertThat(reader(Duration.ofSeconds(5)).read(pages(), CTX).reading()).isNull();
    }

    @Test
    @DisplayName("a wrapper entry that only carries nested invoices is looked through")
    void nestedInvoices() {
        stub.answer(200, "[{\"fileName\":\"a\",\"invoices\":[{\"error\":\"x\"},{\"vendorName\":\"Nested\",\"totalAmount\":10,\"items\":[]}]}]");
        assertThat(reader(Duration.ofSeconds(5)).read(pages(), CTX).reading().vendorName()).isEqualTo("Nested");
    }

    @Test
    @DisplayName("D-115 (M3, M4): a 5xx is 'cost app unavailable', never retried at once, and the body is never quoted")
    void fiveHundredIsUnavailableAndNotRetried() {
        stub.answer(503, "{\"trace\":\"internal stack with " + CostAppStub.SKU_MARKER + "\"}");
        assertThatThrownBy(() -> reader(Duration.ofSeconds(5)).read(pages(), CTX))
                .isInstanceOf(CostApiUnavailableException.class)
                .hasMessageContaining("503")
                .hasMessageNotContaining(CostAppStub.SKU_MARKER)
                .satisfies(e -> assertThat(((CostApiUnavailableException) e).extractionMayHaveRun()).isTrue());
        assertThat(stub.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("D-115 (M3): 403, 408, 429 and 5xx are the cost app unavailable (no attempt); 400, 404, 413, 415, 422 are the bill's failure")
    void statusMapping() {
        for (int status : new int[]{403, 408, 429, 500, 502, 503, 504}) {
            stub.reset();
            stub.answer(status, "{}");
            assertThatThrownBy(() -> reader(Duration.ofSeconds(5)).read(pages(), CTX)).describedAs("status " + status)
                    .isInstanceOf(CostApiUnavailableException.class)
                    .satisfies(e -> assertThat(((CostApiUnavailableException) e).extractionMayHaveRun())
                            .describedAs("may have run, status " + status).isEqualTo(status == 408 || status >= 500));
            assertThat(stub.calls()).isEqualTo(1);
        }
        for (int status : new int[]{400, 404, 413, 415, 422}) {
            stub.reset();
            stub.answer(status, "{}");
            assertThatThrownBy(() -> reader(Duration.ofSeconds(5)).read(pages(), CTX)).describedAs("status " + status)
                    .isInstanceOf(InvoiceReadException.class)
                    .isNotInstanceOf(CostApiUnavailableException.class);
            assertThat(stub.calls()).isEqualTo(1);
        }
        stub.reset();
        stub.answer(401, "{}");   // a static token refused: the session says unavailable, nothing more is sent
        assertThatThrownBy(() -> reader(Duration.ofSeconds(5)).read(pages(), CTX))
                .isInstanceOf(CostApiUnavailableException.class);
        assertThat(stub.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("D-115 (M4): a timeout is raised after one call, never re-posted at once")
    void timeout() {
        stub.answer(() -> new CostAppStub.Reply(200, CostAppStub.GOOD, 1500));
        var reader = new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl(), () -> TOKEN), "0", "0",
                Duration.ofMillis(300), Duration.ZERO, Duration.ofMinutes(5));
        assertThatThrownBy(() -> reader.read(pages(), CTX))
                .isInstanceOf(InvoiceReadException.class).hasMessageContaining("in time");
        assertThat(stub.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("D-115 (M4): the timeout grows with the pages: 60 s, plus 45 s per extra page, at most 5 minutes")
    void timeoutScalesWithPages() throws Exception {
        var reader = new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl(), () -> TOKEN), "0", "0",
                Duration.ofSeconds(60), Duration.ofSeconds(45), Duration.ofMinutes(5));
        var timeoutFor = HttpInvoiceReader.class.getDeclaredMethod("timeoutFor", int.class);
        timeoutFor.setAccessible(true);
        assertThat(timeoutFor.invoke(reader, 1)).isEqualTo(Duration.ofSeconds(60));
        assertThat(timeoutFor.invoke(reader, 2)).isEqualTo(Duration.ofSeconds(105));
        assertThat(timeoutFor.invoke(reader, 5)).isEqualTo(Duration.ofSeconds(240));
        assertThat(timeoutFor.invoke(reader, 9)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("D-115 (M2): a bill whose outlet has no cost outlet is read with the reader's own outlet and without any match")
    void unmappedOutletHasNoMatches() {
        var reading = new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl(), () -> TOKEN), "5", "6",
                Duration.ofSeconds(5)).read(pages(), new ReadContext(9, 2)).reading();
        assertThat(stub.seen().get(0).query()).isEqualTo("pageName=WalletBill&outlet=5&userId=6");
        assertThat(reading.vendorName()).isEqualTo("KOSTA Delights");
        assertThat(reading.supplierMatch()).isNull();
        assertThat(reading.items()).hasSize(3).allSatisfy(i -> assertThat(i.skuMatch()).isNull());
    }

    @Test
    @DisplayName("D-115 (H1, L2, L3): unreadable values are dropped, text amounts parsed right, several bills counted")
    void valuesMadeToFit() {
        stub.answer(200, "[{\"vendorName\":\"A\",\"currency\":\"Indian Rupees (INR)\",\"totalAmount\":1000000000000000,"
                + "\"subtotal\":\"Rs. 500\",\"taxAmount\":\"₹ 1,120.50\",\"deliveryCharges\":\"1e3\",\"items\":[{\"itemName\":\"x\","
                + "\"quantity\":\"0.12345\",\"totalPrice\":\"Rs.500\",\"unitPrice\":\".5.5\"}]},"
                + "{\"vendorName\":\"B\",\"totalAmount\":5,\"items\":[]},{\"error\":\"bad page\"}]");
        var reading = reader(Duration.ofSeconds(5)).read(pages(), CTX).reading();
        assertThat(reading.vendorName()).isEqualTo("A");
        assertThat(reading.currency()).isEqualTo("INR");
        assertThat(reading.total()).isNull();
        assertThat(reading.subtotal()).isEqualByComparingTo("500");
        assertThat(reading.tax()).isEqualByComparingTo("1120.50");
        assertThat(reading.delivery()).isNull();
        assertThat(reading.items().get(0).quantity()).isEqualByComparingTo("0.1235");
        assertThat(reading.items().get(0).total()).isEqualByComparingTo("500");
        assertThat(reading.items().get(0).unitPrice()).isNull();
        assertThat(reading.invoiceCount()).isEqualTo(2);
        assertThat(reader(Duration.ofSeconds(5)).read(pages(), CTX).reading().invoiceCount()).isEqualTo(2);
        stub.answer(200, CostAppStub.GOOD);
        assertThat(reader(Duration.ofSeconds(5)).read(pages(), CTX).reading().invoiceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("D-115 (M7): building and sending the extract request never logs a token or the password, at any level")
    void secretsNeverLoggedAtTheCallSite() {
        var secrets = new java.util.ArrayList<String>();
        try (var logs = new LogCapture()) {
            reader(Duration.ofSeconds(5)).read(pages(), CTX);                  // static token
            stub.requireAuth(true);
            var session = new CostApiSession(stub.baseUrl(), CostAppStub.USERNAME, CostAppStub.PASSWORD, () -> null,
                    Duration.ofSeconds(5), java.time.Clock.systemUTC());
            var signedIn = new HttpInvoiceReader(session, "77", "6", Duration.ofSeconds(5));
            signedIn.read(pages(), CTX);                                         // sign in
            stub.revokeAccess();
            signedIn.read(pages(), CTX);                                         // 401: refresh and send again
            stub.answer(503, "{}");
            assertThatThrownBy(() -> signedIn.read(pages(), CTX)).isInstanceOf(CostApiUnavailableException.class);
            secrets.addAll(stub.issuedTokens());
            secrets.add(CostAppStub.PASSWORD);
            secrets.add(TOKEN);
            assertThat(logs.lines()).anyMatch(l -> l.contains("Cost app extraction for invoice 2 answered"));
            logs.assertNoneContain(secrets);
        }
        assertThat(secrets).hasSizeGreaterThan(3);
    }

    @Test
    @DisplayName("the static token override is read on every call, so a replaced one is used at once")
    void tokenReadPerCall() {
        var token = new AtomicReference<>("first-token");
        var reader = new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl(), token::get), "0", "0",
                Duration.ofSeconds(5));
        reader.read(pages(), CTX);
        token.set("second-token");
        reader.read(pages(), CTX);
        assertThat(stub.seen()).extracting(CostAppStub.Seen::authorization)
                .containsExactly("Bearer first-token", "Bearer second-token");
        assertThat(stub.logins()).isZero();
    }

    @Test
    @DisplayName("the token never shows in toString, and no sign-in at all is a plain failure that sends nothing")
    void tokenMasked() {
        assertThat(reader(Duration.ofSeconds(5)).toString()).doesNotContain(TOKEN);
        var none = new HttpInvoiceReader(CostApiSession.withStaticToken(stub.baseUrl(), () -> ""), "0", "0",
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> none.read(pages(), CTX)).isInstanceOf(CostApiUnavailableException.class)
                .hasMessageContaining("no cost-app sign-in");
        assertThat(stub.calls()).isZero();
    }
}
