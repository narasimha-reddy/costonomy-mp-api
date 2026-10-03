package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static com.costonomy.mp.wallet.InvoiceTestSupport.*;
import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The bill review (D-114) end to end against the fake reader: the draft the screen starts from, saving the user's
 * version with the money computed here, the rules on when and by whom, and the fake pickers.
 */
@AutoConfigureMockMvc
class WalletInvoiceReviewIT extends AbstractIntegrationTest {

    private static final String AT = "2026-09-10 10:00:00.000000";
    private static final Path STORE = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("mp-invoices-review-it");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void invoiceStorage(DynamicPropertyRegistry registry) {
        registry.add("costonomy.mp.invoices.storage.local.directory", STORE::toString);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private WalletTopUpSupport t;
    private Buyer buyer;
    private long entry;

    @BeforeEach
    void setUp() throws Exception {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit(AT, TOP_UP, "9000", "start");
        seed.debit(AT, QUICKSCAN_PAYMENT, "2820", "QuickScan payment");
        entry = seed.lastId;
    }

    private JsonNode read() throws Exception {
        assertThat(upload(mvc, json, buyer.token(), buyer.outletId(), entry, jpegPart()).status()).isEqualTo(201);
        await().atMost(10, TimeUnit.SECONDS).pollInterval(50, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(get().data().path("status").asText()).isEqualTo("READ"));
        return get().data();
    }

    private Reply get() throws Exception {
        return call(mvc, json, "GET", buyer.token(), path(buyer.outletId(), entry));
    }

    private Reply put(String token, long outletId, long entryId, Object body) throws Exception {
        return put(token, outletId, entryId, body, null);
    }

    private Reply put(String token, long outletId, long entryId, Object body, String idempotencyKey) throws Exception {
        return putRaw(token, outletId, entryId, body instanceof String text ? text : json.writeValueAsString(body),
                idempotencyKey);
    }

    private Reply putRaw(String token, long outletId, long entryId, String content, String idempotencyKey)
            throws Exception {
        var request = MockMvcRequestBuilders.put(path(outletId, entryId) + "/review")
                .contentType(MediaType.APPLICATION_JSON).content(content);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    /** What the screen sends: the draft as it came, plus the version, with the user's edits applied by the caller. */
    private ObjectNode fromDraft(JsonNode invoice) {
        var body = (ObjectNode) invoice.get("draft").deepCopy();
        body.put("version", invoice.get("version").asLong());
        return body;
    }

    @Test
    @DisplayName("after reading: version, a draft from the reading (matches, today, PENDING, money), no review")
    void draft() throws Exception {
        var d = read();

        assertThat(d.get("version").isNumber()).isTrue();
        assertThat(d.get("review").isNull()).isTrue();
        var draft = d.get("draft");
        assertThat(draft.at("/supplier/id").asLong()).isEqualTo(2001);
        assertThat(draft.at("/supplier/name").asText()).isEqualTo("Kosta Delights - Sea Food");
        assertThat(draft.get("invoiceNumber").asText()).isEqualTo("1631");
        assertThat(draft.get("invoiceDate").asText()).isEqualTo("04/09/26");
        assertThat(draft.get("stockInDate").asText()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString());
        assertThat(draft.get("paymentStatus").asText()).isEqualTo("PENDING");
        assertThat(draft.get("items")).hasSize(3);
        assertThat(draft.at("/items/0/lineNo").asInt()).isEqualTo(1);
        assertThat(draft.at("/items/0/fromInvoice/name").asText()).isEqualTo("16/20 prawns");
        assertThat(draft.at("/items/0/sku/id").asLong()).isEqualTo(9465);
        assertThat(draft.at("/items/0/sku/unitPrice").decimalValue()).isEqualByComparingTo("360");
        assertThat(draft.at("/items/0/itemPrice").decimalValue()).isEqualByComparingTo("560");
        assertThat(draft.at("/items/0/deviation").asText()).isEqualTo("ABOVE");
        assertThat(draft.at("/items/1/sku/id").asLong()).isEqualTo(152);
        assertThat(draft.at("/items/2/sku/id").asLong()).isEqualTo(9001);
        assertThat(draft.get("total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(draft.get("delivery").decimalValue()).isEqualByComparingTo("0");
        assertThat(draft.get("deliveryOverridden").asBoolean()).isFalse();
        // The reading carries the matches too.
        assertThat(d.at("/reading/supplierMatch/id").asLong()).isEqualTo(2001);
        assertThat(d.at("/reading/items/0/skuMatch/name").asText()).isEqualTo("Prawns 16/20");
    }

    @Test
    @DisplayName("save a review: lines edited, removed and added, money computed here, reading untouched, audited")
    void saveReview() throws Exception {
        var d = read();
        String readingBefore = d.get("reading").toString();
        var body = fromDraft(d);
        var items = (ArrayNode) body.get("items");
        items.remove(1);                                                  // delete line 2
        ((ObjectNode) items.get(1)).set("sku", json.readTree(              // re-match line 3
                "{\"id\":9002,\"name\":\"Prawns 30/50\",\"unit\":\"KG\",\"unitPrice\":250}"));
        ((ObjectNode) items.get(0)).put("ignoredDeviation", true);
        ((ObjectNode) items.get(0)).put("tax", "56.10");
        items.addObject().put("quantity", 1).put("amount", "99.99")      // a line the user added, a new SKU
                .putNull("lineNo").set("sku", json.readTree("{\"id\":null,\"name\":\"Ice\",\"unit\":\"KG\"}"));
        body.put("delivery", "50");
        body.put("paymentStatus", "COMPLETED");
        body.put("total", "1");                                           // computed fields sent back are ignored
        body.put("somethingNew", "ignored");
        ((ObjectNode) body.get("supplier")).put("name", "Kosta Delights - Sea Food");

        var reply = put(buyer.token(), buyer.outletId(), entry, body);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var r = reply.data();
        // D-115 (K1): the draft from the reading is still there next to the review.
        assertThat(r.get("draft")).isEqualTo(d.get("draft"));
        assertThat(r.get("version").asLong()).isGreaterThan(d.get("version").asLong());
        var review = r.get("review");
        assertThat(review.get("items")).hasSize(3);
        assertThat(review.at("/items/0/ignoredDeviation").asBoolean()).isTrue();
        assertThat(review.at("/items/0/lineTotal").decimalValue()).isEqualByComparingTo("1176.10");
        assertThat(review.at("/items/1/lineNo").asInt()).isEqualTo(3);
        assertThat(review.at("/items/1/fromInvoice/name").asText()).isEqualTo("30/50 prawns");
        assertThat(review.at("/items/1/sku/id").asLong()).isEqualTo(9002);
        assertThat(review.at("/items/2/fromInvoice").isNull()).isTrue();
        assertThat(review.at("/items/2/sku/id").isNull()).isTrue();
        assertThat(review.get("subtotal").decimalValue()).isEqualByComparingTo("2019.99");   // 1120 + 800 + 99.99
        assertThat(review.get("tax").decimalValue()).isEqualByComparingTo("56.10");
        assertThat(review.get("delivery").decimalValue()).isEqualByComparingTo("50");
        assertThat(review.get("deliveryOverridden").asBoolean()).isTrue();
        assertThat(review.get("total").decimalValue()).isEqualByComparingTo("2126.09");
        assertThat(review.get("paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(review.get("reviewedBy").asLong()).isEqualTo(t.userId(buyer.token()));
        assertThat(review.get("reviewedAt").asText()).isNotBlank();
        // The check now uses the reviewed total.
        assertThat(r.at("/check/billTotal").decimalValue()).isEqualByComparingTo("2126.09");
        assertThat(r.at("/check/matches").asBoolean()).isFalse();
        assertThat(r.at("/check/difference").decimalValue()).isEqualByComparingTo("-693.91");
        // The reading is as it was.
        assertThat(r.get("reading").toString()).isEqualTo(readingBefore);
        assertThat(jdbc.queryForObject("select total from wallet_entry_invoice where outlet_id = ?",
                java.math.BigDecimal.class, buyer.outletId())).isEqualByComparingTo("2820");
        assertThat(jdbc.queryForObject("select reviewed_by from wallet_entry_invoice where outlet_id = ?",
                Long.class, buyer.outletId())).isEqualTo(t.userId(buyer.token()));
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where action = 'WALLET_INVOICE_REVIEW' and actor_id = ? and new_state = 'REVIEWED'",
                Integer.class, t.userId(buyer.token()))).isEqualTo(1);
        // D-115 (M6): the audit says what the total was and became, and the versions.
        assertThat(jdbc.queryForObject("select reason from audit_log where action = 'WALLET_INVOICE_REVIEW' and actor_id = ?",
                String.class, t.userId(buyer.token()))).isEqualTo("total unreviewed (read 2820.00) -> 2126.09, version "
                + d.get("version").asLong() + " -> " + r.get("version").asLong());
        // D-115 (M6): the check against the reading stays, whatever the review says.
        assertThat(r.at("/check/readingTotal").decimalValue()).isEqualByComparingTo("2820");
        assertThat(r.at("/check/matchesReading").asBoolean()).isTrue();
        // D-115 (L1): the same figures, with the same scale, on GET as on PUT; money is kept as text in the JSON column.
        assertThat(get().data().get("review").toString()).isEqualTo(review.toString());
        assertThat(review.get("total").toString()).isEqualTo("2126.09");
        assertThat(jdbc.queryForObject("select review_json from wallet_entry_invoice where outlet_id = ?", String.class,
                buyer.outletId())).contains("\"2126.09\"");
        // GET shows the same; the transaction page shows the reviewed supplier and total.
        assertThat(get().data().get("review")).isEqualTo(review);
        var details = t.call("GET", buyer.token(), "/api/v1/outlets/" + buyer.outletId()
                + "/wallet/transactions/" + entry, null, null).data();
        assertThat(details.at("/invoice/total").decimalValue()).isEqualByComparingTo("2126.09");
        assertThat(details.at("/invoice/vendorName").asText()).isEqualTo("Kosta Delights - Sea Food");
    }

    @Test
    @DisplayName("a stale version is a 409 INVOICE_CHANGED; with the current one a review can be saved again")
    void staleVersion() throws Exception {
        var d = read();
        var body = fromDraft(d);
        assertThat(put(buyer.token(), buyer.outletId(), entry, body).status()).isEqualTo(200);

        var stale = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("INVOICE_CHANGED");
        assertThat(stale.body().at("/error/message").asText())
                .isEqualTo("This bill was changed since you opened it. Reload it and try again.");

        var current = get().data();
        var again = (ObjectNode) current.get("review").deepCopy();
        again.put("version", current.get("version").asLong());
        again.put("paymentStatus", "COMPLETED");
        var second = put(buyer.token(), buyer.outletId(), entry, again);
        assertThat(second.status()).describedAs(second.body().toString()).isEqualTo(200);
        assertThat(second.data().at("/review/paymentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'WALLET_INVOICE_REVIEW' and old_state = 'REVIEWED' and actor_id = ?",
                Integer.class, t.userId(buyer.token()))).isEqualTo(1);
        // D-115 (M6): the earlier review's total is kept in the history, with who, when and its version.
        var history = json.readTree(jdbc.queryForObject("select review_history_json from wallet_entry_invoice where outlet_id = ?",
                String.class, buyer.outletId()));
        assertThat(history).hasSize(1);
        assertThat(history.at("/0/total").asText()).isEqualTo("2820.00");
        assertThat(history.at("/0/by").asLong()).isEqualTo(t.userId(buyer.token()));
        assertThat(history.at("/0/version").asLong()).isEqualTo(current.get("version").asLong());
        assertThat(history.at("/0/at").asText()).isNotBlank();
    }

    @Test
    @DisplayName("while the bill is being read: 409 INVOICE_STILL_READING, and no draft")
    void whileReading() throws Exception {
        var d = read();
        jdbc.update("update wallet_entry_invoice set status = 'READING' where outlet_id = ?", buyer.outletId());
        var now = get().data();
        assertThat(now.get("draft").isNull()).isTrue();
        var body = fromDraft(d);
        body.put("version", now.get("version").asLong());
        var reply = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("INVOICE_STILL_READING");
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR with a plain sentence per field; nothing saved")
    void validation() throws Exception {
        var d = read();
        var body = fromDraft(d);
        ((ObjectNode) body.get("supplier")).put("name", "");
        body.put("stockInDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(3).toString());
        body.put("paymentStatus", "PAID");
        ((ObjectNode) body.at("/items/0")).put("quantity", 0);
        ((ObjectNode) body.at("/items/1")).put("amount", "12.345");
        body.remove("version");

        var missingVersion = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(missingVersion.status()).isEqualTo(400);
        assertThat(missingVersion.body().at("/error/details/fields/version").asText()).isEqualTo("Send the bill's version.");

        body.put("version", d.get("version").asLong());
        var reply = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        var fields = reply.body().at("/error/details/fields");
        assertThat(fields.get("supplier.name").asText()).isEqualTo("Enter the supplier's name.");
        assertThat(fields.get("stockInDate").asText()).isEqualTo("The stock-in date cannot be later than tomorrow.");
        assertThat(fields.get("paymentStatus").asText()).isEqualTo("Choose PENDING or COMPLETED.");
        assertThat(fields.get("items[0].quantity").asText()).isEqualTo("Enter a quantity above 0.");
        assertThat(fields.get("items[1].amount").asText()).isEqualTo("Use at most 12 digits and 2 decimals.");
        assertThat(get().data().get("review").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select review_json from wallet_entry_invoice where outlet_id = ?", String.class,
                buyer.outletId())).isNull();
    }

    @Test
    @DisplayName("an unreadable bill: an empty draft, filled in by hand; at least one line is needed")
    void unreadableFilledByHand() throws Exception {
        read();
        jdbc.update("update wallet_entry_invoice set status = 'UNREADABLE', reading_json = null, total = null, "
                + "error_text = 'We could not read this bill. You can still view the photo.' where outlet_id = ?",
                buyer.outletId());
        var d = get().data();
        var draft = d.get("draft");
        assertThat(draft.at("/supplier/name").asText()).isEmpty();
        assertThat(draft.at("/supplier/id").isNull()).isTrue();
        assertThat(draft.get("items")).isEmpty();
        assertThat(d.at("/check/billTotal").isNull()).isTrue();

        var body = fromDraft(d);
        ((ObjectNode) body.get("supplier")).put("name", "Corner Fish Shop");
        var empty = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().at("/error/details/fields/items").asText()).isEqualTo("Add at least one item.");

        ((ArrayNode) body.get("items")).addObject().put("quantity", "2.5").put("amount", "2800").put("tax", "20")
                .put("unit", "KG").set("sku", json.readTree("{\"name\":\"Prawns\"}"));
        ((ObjectNode) ((ArrayNode) body.get("items")).get(0)).put("lineNo", 1); // not on any bill
        assertThat(put(buyer.token(), buyer.outletId(), entry, body).body().at("/error/details/fields/items[0].lineNo")
                .asText()).isEqualTo("This line is not on the bill.");
        ((ObjectNode) ((ArrayNode) body.get("items")).get(0)).putNull("lineNo");
        var reply = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/review/total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(reply.data().at("/check/matches").asBoolean()).isTrue();
        assertThat(reply.data().get("status").asText()).isEqualTo("UNREADABLE");
    }

    @Test
    @DisplayName("same door as the bill: another outlet's entry is 404, no bill is 404, no token is 401")
    void access() throws Exception {
        var d = read();
        var body = fromDraft(d);
        var other = t.newBuyer();
        assertThat(put(other.token(), other.outletId(), entry, body).status()).isEqualTo(404);
        assertThat(put(other.token(), buyer.outletId(), entry, body).status()).isIn(403, 404);
        assertThat(put(null, buyer.outletId(), entry, body).status()).isEqualTo(401);
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.debit(AT, QUICKSCAN_PAYMENT, "10", "QuickScan payment");
        var none = put(buyer.token(), buyer.outletId(), seed.lastId, body);
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.code()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(get().data().get("review").isNull()).isTrue();
    }

    @Test
    @DisplayName("the fake pickers: Kosta Delights and the prawn SKUs; limits checked; the same door")
    void fakeLookups() throws Exception {
        String base = "/api/v1/outlets/" + buyer.outletId() + "/invoice-lookups/";
        var suppliers = call(mvc, json, "GET", buyer.token(), base + "suppliers?q=kosta");
        assertThat(suppliers.status()).isEqualTo(200);
        assertThat(suppliers.data()).hasSize(1);
        assertThat(suppliers.data().get(0).get("id").asLong()).isEqualTo(2001);
        assertThat(suppliers.data().get(0).get("name").asText()).isEqualTo("Kosta Delights - Sea Food");

        var skus = call(mvc, json, "GET", buyer.token(), base + "skus?q=PRAWNS&supplierId=2001");
        assertThat(skus.data()).extracting(n -> n.get("name").asText())
                .containsExactly("Prawns 16/20", "PRAWNS 21/25", "Prawns 30/40", "Prawns 30/50");
        assertThat(skus.data().get(0).get("id").asLong()).isEqualTo(9465);
        assertThat(skus.data().get(0).get("unit").asText()).isEqualTo("KG");
        assertThat(skus.data().get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("360");
        assertThat(skus.data().get(0).get("categoryName").asText()).isEqualTo("Seafood");
        assertThat(call(mvc, json, "GET", buyer.token(), base + "skus").data()).hasSize(6);

        assertThat(call(mvc, json, "GET", buyer.token(), base + "skus?limit=51").status()).isEqualTo(400);
        assertThat(call(mvc, json, "GET", buyer.token(), base + "suppliers?q=" + "x".repeat(61)).status()).isEqualTo(400);
        var other = t.newBuyer();
        assertThat(call(mvc, json, "GET", other.token(), base + "skus").status()).isIn(403, 404);
        assertThat(call(mvc, json, "GET", null, base + "skus").status()).isEqualTo(401);
    }

    // ── D-115 ────────────────────────────────────────────────────────────

    /** Replaces the stored reading with the H2 bill: no line tax, 180 of tax only at the bottom, 2820 in all. */
    private void billLevelTaxReading() {
        jdbc.update("""
                update wallet_entry_invoice set total = 2820, tax = 180, subtotal = 2640, reading_json = ?
                 where outlet_id = ?""", """
                {"vendorName":"KOSTA Delights","invoiceNumber":"1631","invoiceDate":"04/09/26","currency":"INR",
                 "items":[{"name":"16/20 prawns","quantity":"2","unit":"KG","unitPrice":"560","total":"1120","amount":"1120","tax":"0",
                           "skuMatch":{"id":9465,"name":"Prawns 16/20","unit":"KG","unitPrice":"360"}},
                          {"name":"21/25 prawns","quantity":"2","unit":"KG","unitPrice":"450","total":"900","amount":"900",
                           "skuMatch":{"id":152,"name":"PRAWNS 21/25","unit":"KG","unitPrice":"300"}},
                          {"name":"30/50 prawns","quantity":"2","unit":"KG","unitPrice":"310","total":"620","amount":"620","tax":"0",
                           "skuMatch":{"id":9001,"name":"Prawns 30/40","unit":"KG","unitPrice":"270"}}],
                 "subtotal":"2640","tax":"180","total":"2820"}""", buyer.outletId());
    }

    @Test
    @DisplayName("D-115 (H2): tax printed only at the bottom: draft total 2820, the check matches before and after saving the untouched draft")
    void billLevelTax() throws Exception {
        read();
        billLevelTaxReading();
        var d = get().data();
        var draft = d.get("draft");
        assertThat(draft.get("subtotal").decimalValue()).isEqualByComparingTo("2640");
        assertThat(draft.get("taxOverride").decimalValue()).isEqualByComparingTo("180");
        assertThat(draft.get("tax").decimalValue()).isEqualByComparingTo("180");
        assertThat(draft.get("total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(d.at("/check/matches").asBoolean()).isTrue();

        var reply = put(buyer.token(), buyer.outletId(), entry, fromDraft(d));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/review/tax").decimalValue()).isEqualByComparingTo("180");
        assertThat(reply.data().at("/review/taxOverride").decimalValue()).isEqualByComparingTo("180");
        assertThat(reply.data().at("/review/total").decimalValue()).isEqualByComparingTo("2820");
        assertThat(reply.data().at("/check/matches").asBoolean()).isTrue();
        assertThat(reply.data().at("/check/difference").decimalValue()).isEqualByComparingTo("0");
        assertThat(get().data().at("/check/matches").asBoolean()).isTrue();

        // Clearing it goes back to the sum of the line taxes (0 here), and the check says so.
        var cleared = fromDraft(get().data());
        cleared.set("items", get().data().at("/review/items"));
        cleared.putNull("taxOverride");
        cleared.put("version", get().data().get("version").asLong());
        var again = put(buyer.token(), buyer.outletId(), entry, cleared);
        assertThat(again.data().at("/review/total").decimalValue()).isEqualByComparingTo("2640");
        assertThat(again.data().at("/check/matches").asBoolean()).isFalse();
        assertThat(again.data().at("/check/matchesReading").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("D-115 (K2): a retried save whose answer was lost: same key and body is 200 with the current bill, no new version, no 409; same key, other body is 422")
    void idempotentRetry() throws Exception {
        var d = read();
        var body = fromDraft(d);
        body.put("paymentStatus", "COMPLETED");
        var first = put(buyer.token(), buyer.outletId(), entry, body, "save-key-1");
        assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(200);
        long saved = first.data().get("version").asLong();

        var retry = put(buyer.token(), buyer.outletId(), entry, body, "save-key-1");   // the lost answer, asked again
        assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(200);
        assertThat(retry.data().get("version").asLong()).isEqualTo(saved);
        assertThat(retry.data().get("review")).isEqualTo(first.data().get("review"));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'WALLET_INVOICE_REVIEW' and actor_id = ?",
                Integer.class, t.userId(buyer.token()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select review_idem_version from wallet_entry_invoice where outlet_id = ?",
                Long.class, buyer.outletId())).isEqualTo(saved);

        body.put("paymentStatus", "PENDING");
        var reused = put(buyer.token(), buyer.outletId(), entry, body, "save-key-1");
        assertThat(reused.status()).isEqualTo(422);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // Without the key the old version is stale, as before.
        assertThat(put(buyer.token(), buyer.outletId(), entry, body).code()).isEqualTo("INVOICE_CHANGED");
        assertThat(put(buyer.token(), buyer.outletId(), entry, body, "k".repeat(129)).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("D-115 (L10): two saves at once from the same version: exactly one wins, the other is 409 INVOICE_CHANGED")
    void concurrentSaves() throws Exception {
        var d = read();
        var a = fromDraft(d);
        a.put("paymentStatus", "COMPLETED");
        var b = fromDraft(d);
        b.put("delivery", "10");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        try {
            var fa = pool.submit(() -> {
                go.await();
                return put(buyer.token(), buyer.outletId(), entry, a, "key-a");
            });
            var fb = pool.submit(() -> {
                go.await();
                return put(buyer.token(), buyer.outletId(), entry, b, "key-b");
            });
            go.countDown();
            var ra = fa.get(20, TimeUnit.SECONDS);
            var rb = fb.get(20, TimeUnit.SECONDS);
            assertThat(java.util.List.of(ra.status(), rb.status())).containsExactlyInAnyOrder(200, 409);
            var loser = ra.status() == 409 ? ra : rb;
            assertThat(loser.code()).isEqualTo("INVOICE_CHANGED");
            assertThat(get().data().get("version").asLong()).isEqualTo(d.get("version").asLong() + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("D-115 (M5): a user who can see the outlet but not pay from its wallet can read the bill, not upload, review or remove it (403)")
    void viewOnlyCannotWrite() throws Exception {
        var d = read();
        String staff = t.api().loginFresh();
        t.grant(t.userId(staff), buyer.outletId(), "REST_RECEIVING_STAFF");

        assertThat(call(mvc, json, "GET", staff, path(buyer.outletId(), entry)).status()).isEqualTo(200);
        assertThat(call(mvc, json, "GET", staff, "/api/v1/outlets/" + buyer.outletId() + "/invoice-lookups/skus").status())
                .isEqualTo(200);
        var review = put(staff, buyer.outletId(), entry, fromDraft(d));
        assertThat(review.status()).isEqualTo(403);
        assertThat(review.code()).isEqualTo("FORBIDDEN");
        assertThat(call(mvc, json, "DELETE", staff, path(buyer.outletId(), entry)).status()).isEqualTo(403);
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.debit(AT, QUICKSCAN_PAYMENT, "10", "QuickScan payment");
        assertThat(upload(mvc, json, staff, buyer.outletId(), seed.lastId, jpegPart()).status()).isEqualTo(403);
        assertThat(get().data().get("review").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from wallet_entry_invoice where outlet_id = ?", Integer.class,
                buyer.outletId())).isEqualTo(1);
    }

    @Test
    @DisplayName("D-115 (K3, K5): fromInvoice and deliveryOverridden in a request are ignored; a value of the wrong type names its field; an empty body names the body")
    void requestDetails() throws Exception {
        var d = read();
        var body = fromDraft(d);
        body.put("deliveryOverridden", true);
        ((ObjectNode) body.at("/items/0")).set("fromInvoice", json.readTree("{\"name\":\"Something else\",\"total\":1}"));
        var ok = put(buyer.token(), buyer.outletId(), entry, body);
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(200);
        assertThat(ok.data().at("/review/deliveryOverridden").asBoolean()).isFalse();
        assertThat(ok.data().at("/review/items/0/fromInvoice/name").asText()).isEqualTo("16/20 prawns");

        var bad = fromDraft(get().data());
        bad.put("version", get().data().get("version").asLong());
        ((ObjectNode) bad.at("/items/1")).put("quantity", "not-a-number-7731");
        var malformed = put(buyer.token(), buyer.outletId(), entry, bad);
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(malformed.body().at("/error/details/fields/items[1].quantity").asText())
                .isEqualTo("This value could not be read. Check it and try again.");
        assertThat(malformed.body().toString()).doesNotContain("not-a-number-7731");

        var empty = putRaw(buyer.token(), buyer.outletId(), entry, "", null);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(empty.body().at("/error/details/fields/body").asText()).isEqualTo("Send the reviewed bill.");
    }
}
