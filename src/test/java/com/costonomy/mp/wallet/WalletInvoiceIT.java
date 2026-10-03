package com.costonomy.mp.wallet;

import com.costonomy.mp.storage.invoice.InvoiceLinkSigner;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static com.costonomy.mp.wallet.InvoiceTestSupport.*;
import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The shop's bill on a wallet payment (D-113), end to end against the fake reader and local-disk
 * storage: upload, view, remove, the rules about who and what, and the signed page links.
 */
@AutoConfigureMockMvc
class WalletInvoiceIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";
    static final Path STORE = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mp-invoices-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void invoiceStorage(DynamicPropertyRegistry registry) {
        registry.add("costonomy.mp.invoices.storage.local.directory", STORE::toString);
        registry.add("costonomy.mp.invoices.link-secret", () -> "it-only-link-secret");
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InvoiceLinkSigner signer;

    private WalletTopUpSupport t;
    private Buyer buyer;
    private LedgerSeed seed;
    private long topUpId;
    private long quickScanId;

    @BeforeEach
    void setUp() throws Exception {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit(AT, TOP_UP, "9000", "start");
        topUpId = seed.lastId;
        seed.debit(AT, QUICKSCAN_PAYMENT, "2820", "QuickScan payment");
        quickScanId = seed.lastId;
    }

    private Reply up(Buyer who, Buyer outlet, long entry, MockMultipartFile... files) throws Exception {
        return upload(mvc, json, who == null ? null : who.token(), outlet.outletId(), entry, files);
    }

    private Reply get(Buyer who, long entry) throws Exception {
        return call(mvc, json, "GET", who.token(), path(buyer.outletId(), entry));
    }

    private JsonNode awaitStatus(long entry, String status) {
        await().atMost(10, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(get(buyer, entry).data().path("status").asText()).isEqualTo(status));
        try {
            return get(buyer, entry).data();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long extraEntry(String amount) {
        seed.debit(AT, QUICKSCAN_PAYMENT, amount, "QuickScan payment");
        return seed.lastId;
    }

    @Test
    @DisplayName("upload: 201 READING with the page, no total yet; then READ with the reading and a matching check")
    void happyPath() throws Exception {
        var reply = up(buyer, buyer, quickScanId, part("my receipt (1).jpg", "image/jpeg", jpeg(200)));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var d = reply.data();
        assertThat(d.get("status").asText()).isEqualTo("READING");
        assertThat(d.get("pageCount").asInt()).isEqualTo(1);
        assertThat(d.get("uploadedBy").asLong()).isEqualTo(t.userId(buyer.token()));
        assertThat(Instant.parse(d.get("createdAt").asText())).isBefore(Instant.now().plusSeconds(5));
        assertThat(d.get("reading").isNull()).isTrue();
        assertThat(d.get("error").isNull()).isTrue();
        assertThat(d.get("attempts").asInt()).isZero();
        var page = d.at("/pages/0");
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("contentType").asText()).isEqualTo("image/jpeg");
        assertThat(page.get("sizeBytes").asLong()).isEqualTo(208);
        assertThat(page.get("url").asText()).contains("/invoice-files/");
        assertThat(Instant.parse(page.get("expiresAt").asText())).isAfter(Instant.now().plusSeconds(200));
        assertThat(d.at("/check/paid").decimalValue()).isEqualByComparingTo("2820");
        assertThat(d.at("/check/billTotal").isNull()).isTrue();
        assertThat(d.at("/check/matches").isNull()).isTrue();
        assertThat(d.at("/check/difference").isNull()).isTrue();

        var read = awaitStatus(quickScanId, "READ");
        assertThat(read.at("/reading/vendorName").asText()).isEqualTo("KOSTA Delights");
        assertThat(read.at("/reading/invoiceNumber").asText()).isEqualTo("1631");
        assertThat(read.at("/reading/invoiceDate").asText()).isEqualTo("04/09/26");
        assertThat(read.at("/reading/customerName").asText()).isEqualTo("Delicia");
        assertThat(read.at("/reading/currency").asText()).isEqualTo("INR");
        assertThat(read.at("/reading/items")).hasSize(3);
        assertThat(read.at("/reading/items/0/name").asText()).isEqualTo("16/20 prawns");
        assertThat(read.at("/reading/items/0/unit").asText()).isEqualTo("KG");
        assertThat(read.at("/reading/items/0/unitPrice").decimalValue()).isEqualByComparingTo("560");
        assertThat(read.at("/reading/items/0/total").decimalValue()).isEqualByComparingTo("1120");
        assertThat(read.at("/reading/total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(read.at("/check/matches").asBoolean()).isTrue();
        assertThat(read.at("/check/difference").decimalValue()).isEqualByComparingTo("0");
        assertThat(read.get("attempts").asInt()).isEqualTo(1);
        assertThat(read.get("error").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select vendor_name from wallet_entry_invoice where outlet_id = ?", String.class, buyer.outletId()))
                .isEqualTo("KOSTA Delights");
    }

    @Test
    @DisplayName("nothing private leaks: no storage key, bucket or file name in the response, and keys never use the file name")
    void noKeysInResponseAndKeyLayout() throws Exception {
        var reply = up(buyer, buyer, quickScanId, part("my receipt (1).jpg", "image/jpeg", jpeg(10)));
        String body = reply.body().toString();
        assertThat(body).doesNotContain("invoices/").doesNotContain("storage").doesNotContain("my receipt")
                .doesNotContain("sha256");
        String key = jdbc.queryForObject("select storage_key from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", String.class, buyer.outletId());
        assertThat(key).matches("invoices/" + buyer.outletId() + "/\\d{4}-\\d{2}/[0-9a-f-]{36}-p1\\.jpg");
        assertThat(jdbc.queryForObject("select sha256 from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", String.class, buyer.outletId())).hasSize(64);
    }

    @Test
    @DisplayName("two pages (a photo and a PDF) are stored, listed in order, and each link serves its own bytes")
    void twoPages() throws Exception {
        byte[] first = jpeg(300);
        var reply = up(buyer, buyer, quickScanId, part("a.jpg", "image/jpeg", first),
                part("b.pdf", "application/pdf", pdf()));

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.data().get("pageCount").asInt()).isEqualTo(2);
        assertThat(reply.data().at("/pages/1/contentType").asText()).isEqualTo("application/pdf");
        assertThat(reply.data().at("/pages/1/page").asInt()).isEqualTo(2);
        assertThat(filesUnder(STORE.resolve("invoices/" + buyer.outletId()))).hasSize(2);

        for (int i = 0; i < 2; i++) {
            String url = reply.data().at("/pages/" + i + "/url").asText();
            var served = mvc.perform(MockMvcRequestBuilders.get(url.substring(url.indexOf("/invoice-files/"))))
                    .andReturn().getResponse();
            assertThat(served.getStatus()).isEqualTo(200);
            assertThat(served.getContentAsByteArray()).isEqualTo(i == 0 ? first : pdf());
            assertThat(served.getContentType()).isEqualTo(i == 0 ? "image/jpeg" : "application/pdf");
            assertThat(served.getHeader("Cache-Control")).contains("no-store");
            assertThat(served.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        }
    }

    @Test
    @DisplayName("only an order payment or a QuickScan payment takes a bill: a top-up is 422")
    void wrongKind() throws Exception {
        var reply = up(buyer, buyer, topUpId, jpegPart());
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("INVOICE_NOT_ALLOWED");
        assertThat(reply.body().at("/error/message").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();
    }

    @Test
    @DisplayName("another outlet's entry, a missing one and a malformed id are 404 on all three verbs")
    void tenancy() throws Exception {
        var other = t.newBuyer();
        // The other buyer's own outlet, my entry id.
        assertThat(up(other, other, quickScanId, jpegPart()).status()).isEqualTo(404);
        assertThat(call(mvc, json, "GET", other.token(), path(other.outletId(), quickScanId)).status()).isEqualTo(404);
        assertThat(call(mvc, json, "DELETE", other.token(), path(other.outletId(), quickScanId)).status()).isEqualTo(404);
        // My outlet's path with the other buyer's token is refused, and nothing is created.
        assertThat(up(other, buyer, quickScanId, jpegPart()).status()).isIn(403, 404);
        // Missing and malformed.
        assertThat(up(buyer, buyer, 99999999L, jpegPart()).status()).isEqualTo(404);
        assertThat(call(mvc, json, "GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId()
                + "/wallet/transactions/not-a-number/invoice").status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();

        // And a bill of mine is not readable or removable from the other outlet.
        assertThat(up(buyer, buyer, quickScanId, jpegPart()).status()).isEqualTo(201);
        assertThat(call(mvc, json, "GET", other.token(), path(other.outletId(), quickScanId)).status()).isEqualTo(404);
        assertThat(call(mvc, json, "DELETE", other.token(), path(other.outletId(), quickScanId)).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isEqualTo(1);
    }

    @Test
    @DisplayName("no token is 401 on all three verbs")
    void unauthenticated() throws Exception {
        assertThat(up(null, buyer, quickScanId, jpegPart()).status()).isEqualTo(401);
        assertThat(call(mvc, json, "GET", null, path(buyer.outletId(), quickScanId)).status()).isEqualTo(401);
        assertThat(call(mvc, json, "DELETE", null, path(buyer.outletId(), quickScanId)).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("a file is judged by its bytes: a text file called .jpg, an SVG, a GIF are refused with a plain message and nothing is kept")
    void badMagicBytes() throws Exception {
        for (var bad : new MockMultipartFile[]{
                part("bill.jpg", "image/jpeg", "this is not a picture at all".getBytes()),
                part("bill.png", "image/png", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()),
                part("bill.pdf", "application/pdf", "GIF89a......".getBytes())}) {
            var reply = up(buyer, buyer, quickScanId, jpegPart(), bad);
            assertThat(reply.status()).describedAs(bad.getOriginalFilename()).isEqualTo(415);
            assertThat(reply.code()).isEqualTo("INVOICE_FILE_TYPE");
            assertThat(reply.body().at("/error/message").asText()).contains("JPEG").contains("PDF");
        }
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", Integer.class, buyer.outletId())).isZero();
    }

    @Test
    @DisplayName("a real JPEG is accepted even when it claims to be a PDF")
    void declaredTypeIsIgnored() throws Exception {
        var reply = up(buyer, buyer, quickScanId, part("x.pdf", "application/pdf", jpeg(10)));
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.data().at("/pages/0/contentType").asText()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("0 files and 6 files are 400; 5 are fine; a file over 5 MB is 400")
    void pageAndSizeLimits() throws Exception {
        assertThat(up(buyer, buyer, quickScanId).status()).isEqualTo(400);
        var six = new MockMultipartFile[6];
        java.util.Arrays.fill(six, jpegPart());
        var tooMany = up(buyer, buyer, quickScanId, six);
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.body().at("/error/message").asText()).contains("1 to 5");

        var big = part("big.jpg", "image/jpeg", jpeg(5 * 1024 * 1024));
        var tooBig = up(buyer, buyer, quickScanId, big);
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(tooBig.body().at("/error/message").asText()).contains("5 MB");
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();

        var five = new MockMultipartFile[5];
        java.util.Arrays.fill(five, jpegPart());
        var ok = up(buyer, buyer, quickScanId, five);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.data().get("pageCount").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("a second upload for the same entry is 409 INVOICE_EXISTS and keeps the first bill")
    void secondUpload() throws Exception {
        assertThat(up(buyer, buyer, quickScanId, jpegPart()).status()).isEqualTo(201);
        int filesBefore = filesUnder(STORE.resolve("invoices/" + buyer.outletId())).size();
        var second = up(buyer, buyer, quickScanId, jpegPart());
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("INVOICE_EXISTS");
        assertThat(filesUnder(STORE.resolve("invoices/" + buyer.outletId()))).hasSize(filesBefore);
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isEqualTo(1);
    }

    @Test
    @DisplayName("GET with no bill is 404 INVOICE_NOT_FOUND")
    void getWithoutBill() throws Exception {
        var reply = get(buyer, quickScanId);
        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.code()).isEqualTo("INVOICE_NOT_FOUND");
    }

    @Test
    @DisplayName("DELETE is 204, removes the rows and every stored file, is audited, and a second DELETE is 404")
    void deleteRemovesEverything() throws Exception {
        var keysBefore = filesUnder(STORE.resolve("invoices/" + buyer.outletId()));
        var reply = up(buyer, buyer, quickScanId, jpegPart(), part("b.pdf", "application/pdf", pdf()));
        assertThat(reply.status()).isEqualTo(201);
        var created = jdbc.queryForList("select storage_key from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", String.class, buyer.outletId());
        assertThat(created).hasSize(2);
        for (String key : created) {
            assertThat(Files.exists(STORE.resolve(key))).isTrue();
        }
        awaitStatus(quickScanId, "READ");

        var deleted = call(mvc, json, "DELETE", buyer.token(), path(buyer.outletId(), quickScanId));
        assertThat(deleted.status()).isEqualTo(204);

        for (String key : created) {
            assertThat(Files.exists(STORE.resolve(key))).describedAs(key).isFalse();
        }
        assertThat(filesUnder(STORE.resolve("invoices/" + buyer.outletId()))).hasSameSizeAs(keysBefore);
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class, buyer.outletId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", Integer.class, buyer.outletId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action = 'WALLET_INVOICE_REMOVED' and actor_id = ?",
                Integer.class, t.userId(buyer.token()))).isEqualTo(1);
        assertThat(get(buyer, quickScanId).code()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(call(mvc, json, "DELETE", buyer.token(), path(buyer.outletId(), quickScanId)).status())
                .isEqualTo(404);
        // The entry can take a new bill afterwards.
        assertThat(up(buyer, buyer, quickScanId, jpegPart()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("check: a bill total within half a rupee of the payment matches, one further away does not")
    void mismatchCheck() throws Exception {
        // The fake reader always reads 2820. Entry paid 2820.40 matches; 2000 and 2821 do not.
        long close = extraEntry("2820.40");
        long far = extraEntry("2000");
        long off = extraEntry("2821");
        for (long entry : new long[]{close, far, off}) {
            assertThat(up(buyer, buyer, entry, jpegPart()).status()).isEqualTo(201);
            awaitStatus(entry, "READ");
        }
        assertThat(get(buyer, close).data().at("/check/matches").asBoolean()).isTrue();
        assertThat(get(buyer, close).data().at("/check/difference").decimalValue()).isEqualByComparingTo("-0.40");
        var farCheck = get(buyer, far).data().at("/check");
        assertThat(farCheck.get("matches").asBoolean()).isFalse();
        assertThat(farCheck.get("paid").decimalValue()).isEqualByComparingTo("2000");
        assertThat(farCheck.get("billTotal").decimalValue()).isEqualByComparingTo("2820");
        assertThat(farCheck.get("difference").decimalValue()).isEqualByComparingTo("820");
        assertThat(get(buyer, off).data().at("/check/matches").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the 21st bill of the day for an outlet is 429 with a plain sentence")
    void dailyCap() throws Exception {
        for (int i = 0; i < 20; i++) {
            long entry = i == 0 ? quickScanId : extraEntry("10");
            assertThat(up(buyer, buyer, entry, jpegPart()).status()).describedAs("bill %d", i + 1).isEqualTo(201);
        }
        long twentyFirst = extraEntry("10");
        var reply = up(buyer, buyer, twentyFirst, jpegPart());
        assertThat(reply.status()).isEqualTo(429);
        assertThat(reply.code()).isEqualTo("INVOICE_LIMIT_REACHED");
        assertThat(reply.body().at("/error/message").asText()).contains("today");
        // Another outlet is not affected.
        var other = t.newBuyer();
        var otherSeed = new LedgerSeed(t, jdbc, other);
        otherSeed.credit(AT, TOP_UP, "100", "start");
        otherSeed.debit(AT, QUICKSCAN_PAYMENT, "10", "QuickScan payment");
        assertThat(up(other, other, otherSeed.lastId, jpegPart()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("the transaction details gain actions.canAddBill and an invoice summary once there is a bill")
    void detailsShowBill() throws Exception {
        String detail = "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions/";
        var before = t.call("GET", buyer.token(), detail + quickScanId, null, null).data();
        assertThat(before.at("/actions/canAddBill").asBoolean()).isTrue();
        assertThat(before.get("invoice").isNull()).isTrue();
        assertThat(t.call("GET", buyer.token(), detail + topUpId, null, null).data()
                .at("/actions/canAddBill").asBoolean()).isFalse();
        assertThat(t.call("GET", buyer.token(), detail + topUpId, null, null).data().get("invoice").isNull()).isTrue();

        assertThat(up(buyer, buyer, quickScanId, jpegPart()).status()).isEqualTo(201);
        awaitStatus(quickScanId, "READ");

        var after = t.call("GET", buyer.token(), detail + quickScanId, null, null).data();
        assertThat(after.at("/invoice/status").asText()).isEqualTo("READ");
        assertThat(after.at("/invoice/vendorName").asText()).isEqualTo("KOSTA Delights");
        assertThat(after.at("/invoice/total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(after.at("/invoice/thumbnailUrl").asText()).contains("/invoice-files/");
        assertThat(after.at("/invoice").size()).isEqualTo(4);
        // Existing fields are still there.
        assertThat(after.get("kind").asText()).isEqualTo("QUICKSCAN_PAYMENT");
        assertThat(after.at("/actions/canPayAgain").isBoolean()).isTrue();
        assertThat(after.get("references")).isNotNull();
    }

    @Test
    @DisplayName("page links: a link works until it expires; expired, tampered, foreign-outlet and arbitrary tokens are 404")
    void pageLinks() throws Exception {
        var reply = up(buyer, buyer, quickScanId, jpegPart());
        String url = reply.data().at("/pages/0/url").asText();
        String token = url.substring(url.indexOf("/invoice-files/") + "/invoice-files/".length());
        long pageId = jdbc.queryForObject("select p.id from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", Long.class, buyer.outletId());

        assertThat(fetch(token)).isEqualTo(200);
        // Expired a second ago, validly signed.
        assertThat(fetch(signer.sign(buyer.outletId(), pageId, Instant.now().minusSeconds(1)))).isEqualTo(404);
        // Valid and fresh, but naming another outlet for this page.
        assertThat(fetch(signer.sign(buyer.outletId() + 1000, pageId, Instant.now().plusSeconds(60)))).isEqualTo(404);
        // A page that does not exist.
        assertThat(fetch(signer.sign(buyer.outletId(), pageId + 5000, Instant.now().plusSeconds(60)))).isEqualTo(404);
        // Tampered: the signature of another token, a flipped character, a stripped signature.
        String other = signer.sign(buyer.outletId(), pageId, Instant.now().plusSeconds(1));
        assertThat(fetch(token.substring(0, token.indexOf('.')) + other.substring(other.indexOf('.')))).isEqualTo(404);
        // (The first signature character: the last one carries padding bits that do not change the bytes.)
        int sig = token.indexOf('.') + 1;
        char c = token.charAt(sig);
        assertThat(fetch(token.substring(0, sig) + (c == 'A' ? 'B' : 'A') + token.substring(sig + 1))).isEqualTo(404);
        assertThat(fetch(token.substring(0, token.indexOf('.')))).isEqualTo(404);
        // A storage key is not a token.
        String key = jdbc.queryForObject("select storage_key from wallet_entry_invoice_page p join wallet_entry_invoice i on i.id = p.invoice_id where i.outlet_id = ?", String.class, buyer.outletId());
        assertThat(fetch(key.replace("/", "%2F"))).isIn(400, 404); // the firewall may refuse an encoded slash first
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/invoice-files/../" + key)).andReturn().getResponse()
                .getStatus()).isGreaterThanOrEqualTo(400);
        // And the public image route does not serve bills.
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/files/" + key)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    private int fetch(String token) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/invoice-files/" + token)).andReturn().getResponse().getStatus();
    }
}
