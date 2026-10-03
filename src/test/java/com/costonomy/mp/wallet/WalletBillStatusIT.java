package com.costonomy.mp.wallet;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.CountingStatementInspector;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.costonomy.mp.wallet.invoice.service.BillTracking;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bill status on wallet payments (D-116): the History's {@code bill} per row, its {@code bills} filter and paging,
 * the banner's {@code billSummary} and each month's {@code billsPending}, the details page's {@code billStatus} and
 * waiver actions, and the statement's Bill, Shop and Bill no. columns. Rows are written straight into the database at
 * exact instants (UTC literals); every read goes through the API.
 */
@AutoConfigureMockMvc
class WalletBillStatusIT extends AbstractIntegrationTest {

    /** 1 September 2026 in India: 31 August 18:30 UTC. */
    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BillTracking tracking;

    private WalletTopUpSupport t;

    @BeforeEach
    void setUp() {
        t = new WalletTopUpSupport(mvc, json, jdbc);
        tracking.setStartForTests(START);
    }

    @AfterEach
    void tearDown() {
        tracking.setStartForTests(null); // the setting's default in tests: blank, tracking off
    }

    // ── seeding ──────────────────────────────────────────────────────────

    private LedgerSeed pay(LedgerSeed seed, String at, com.costonomy.mp.wallet.domain.WalletEntryKind kind,
                           String reference) {
        seed.debit(at, kind, "10", kind.name());
        if (reference != null) {
            jdbc.update("update wallet_transaction set reference = ? where id = ?", reference, seed.lastId);
        }
        return seed;
    }

    /**
     * A bill row with no pages, never due for the retry job (next_try_at far ahead): the database is shared by every
     * test class, and a later class's context runs the job at start-up, which must not send these to its reader.
     */
    private void invoice(long outletId, long entryId, String status, String reviewJson, String vendor, String number) {
        jdbc.update("""
                insert into wallet_entry_invoice (wallet_transaction_id, outlet_id, status, vendor_name, invoice_number,
                       review_json, reviewed_at, next_try_at, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, '2037-12-31 00:00:00', now(6), now(6), 0)
                """, entryId, outletId, status, vendor, number, reviewJson,
                reviewJson == null ? null : java.sql.Timestamp.from(Instant.now()));
    }

    private void waiver(long outletId, long entryId) {
        jdbc.update("""
                insert into wallet_entry_invoice_waiver (wallet_transaction_id, outlet_id, waived_by, waived_at,
                       created_at, updated_at, version)
                values (?, ?, null, now(6), now(6), now(6), 0)
                """, entryId, outletId);
    }

    /** The matrix: every kind of row, each bill state, before and after the start, and a second outlet. */
    private final class Matrix {
        final Buyer buyer;
        final LedgerSeed seed;
        final Map<String, Long> id = new LinkedHashMap<>();

        Matrix() throws Exception {
            buyer = t.newBuyer();
            seed = new LedgerSeed(t, jdbc, buyer);
            long o = buyer.outletId();
            seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1000", "start");
            id.put("topUp", seed.lastId);
            id.put("beforeStart", pay(seed, "2026-08-20 10:00:00.000000", ORDER_PAYMENT, null).lastId);
            id.put("readingBeforeStart", pay(seed, "2026-08-25 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x01").lastId);
            invoice(o, seed.lastId, "READING", null, null, null);
            // One microsecond before the start in India, and the start itself.
            id.put("justBefore", pay(seed, "2026-08-31 18:29:59.999999", ORDER_PAYMENT, null).lastId);
            id.put("atStart", pay(seed, "2026-08-31 18:30:00.000000", ORDER_PAYMENT, null).lastId);
            id.put("pendingOrder", pay(seed, "2026-09-02 10:00:00.000000", ORDER_PAYMENT, null).lastId);
            id.put("pendingQs", pay(seed, "2026-09-03 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x02").lastId);
            // A QuickScan payment whose payout failed: the money came back, so no bill is asked for.
            id.put("returnedQs", pay(seed, "2026-09-04 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x03").lastId);
            seed.credit("2026-09-04 11:00:00.000000", QUICKSCAN_RETURN, "10", "back");
            jdbc.update("update wallet_transaction set reference = ? where id = ?", "quickscan-return-" + o + "x03", seed.lastId);
            id.put("qsReturn", seed.lastId);
            id.put("waived", pay(seed, "2026-09-05 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x04").lastId);
            waiver(o, seed.lastId);
            id.put("reading", pay(seed, "2026-09-06 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x05").lastId);
            invoice(o, seed.lastId, "READING", null, null, null);
            id.put("added", pay(seed, "2026-09-07 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x06").lastId);
            invoice(o, seed.lastId, "READ", null, "Kosta Read", "R-1");
            id.put("reviewed", pay(seed, "2026-09-08 10:00:00.000000", ORDER_PAYMENT, null).lastId);
            invoice(o, seed.lastId, "READ", "{\"supplier\":{\"id\":7,\"name\":\"Kosta Reviewed\"},\"invoiceNumber\":\"REV-9\"}",
                    "Kosta Read", "R-2");
            id.put("unreadable", pay(seed, "2026-09-09 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "x07").lastId);
            invoice(o, seed.lastId, "UNREADABLE", null, null, null);
            id.put("withdrawal", pay(seed, "2026-09-10 10:00:00.000000", WITHDRAWAL, null).lastId);
            seed.credit("2026-09-11 10:00:00.000000", ORDER_REFUND, "5", "refund");
            id.put("orderRefund", seed.lastId);
            seed.credit("2026-09-12 10:00:00.000000", DISPUTE_REFUND, "5", "dispute");
            id.put("disputeRefund", seed.lastId);
            // 19:00 UTC on 30 September is 00:30 on 1 October in India.
            id.put("pendingOctober", pay(seed, "2026-09-30 19:00:00.000000", ORDER_PAYMENT, null).lastId);
            seed.sync();

            // Another outlet with its own pending and reading payments: never counted here.
            other = t.newBuyer();
            var otherSeed = new LedgerSeed(t, jdbc, other);
            otherSeed.credit("2026-09-01 10:00:00.000000", TOP_UP, "100", "start");
            otherId.put("topUp", otherSeed.lastId);
            otherId.put("pending", pay(otherSeed, "2026-09-02 10:00:00.000000", ORDER_PAYMENT, null).lastId);
            otherId.put("reading", pay(otherSeed, "2026-09-03 10:00:00.000000", QUICKSCAN_PAYMENT,
                    "quickscan-" + other.outletId() + "x01").lastId);
            invoice(other.outletId(), otherSeed.lastId, "READING", null, null, null);
            otherSeed.sync();
        }

        Buyer other;
        final Map<String, Long> otherId = new LinkedHashMap<>();

        String key(String name) {
            return "L" + id.get(name);
        }
    }

    // ── calls ────────────────────────────────────────────────────────────

    private Reply history(Buyer buyer, String query) throws Exception {
        return t.call("GET", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions" + (query.isEmpty() ? "" : "?" + query),
                null, null);
    }

    private JsonNode ok(Buyer buyer, String query) throws Exception {
        var reply = history(buyer, query);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static Map<String, String> bills(JsonNode data) {
        var out = new LinkedHashMap<String, String>();
        data.get("items").forEach(item -> out.put(item.get("key").asText(),
                item.get("bill").isNull() ? null : item.get("bill").get("status").asText()));
        return out;
    }

    private static List<String> keys(JsonNode data) {
        var out = new ArrayList<String>();
        data.get("items").forEach(item -> out.add(item.get("key").asText()));
        return out;
    }

    private List<String> walk(Buyer buyer, String query, int size) throws Exception {
        var all = new ArrayList<String>();
        String cursor = null;
        for (int guard = 0; guard < 200; guard++) {
            var data = ok(buyer, query + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor));
            all.addAll(keys(data));
            if (data.get("nextCursor").isNull()) {
                return all;
            }
            cursor = data.get("nextCursor").asText();
        }
        throw new AssertionError("cursor never ended");
    }

    private JsonNode detail(Buyer buyer, long entryId) throws Exception {
        var reply = t.call("GET", buyer.token(),
                "/api/v1/outlets/" + buyer.outletId() + "/wallet/transactions/" + entryId, null, null);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    // ── the list ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("each row's bill: a bill always shows; PENDING only for eligible payments from the start; none for the rest")
    void listStatuses() throws Exception {
        var m = new Matrix();
        var bills = bills(ok(m.buyer, "size=100"));

        assertThat(bills).hasSize(m.id.size());
        assertThat(bills.get(m.key("topUp"))).isNull();
        assertThat(bills.get(m.key("beforeStart"))).isNull();
        assertThat(bills.get(m.key("readingBeforeStart"))).isEqualTo("READING");
        assertThat(bills.get(m.key("justBefore"))).isNull();
        assertThat(bills.get(m.key("atStart"))).isEqualTo("PENDING");
        assertThat(bills.get(m.key("pendingOrder"))).isEqualTo("PENDING");
        assertThat(bills.get(m.key("pendingQs"))).isEqualTo("PENDING");
        assertThat(bills.get(m.key("returnedQs"))).isNull();
        assertThat(bills.get(m.key("qsReturn"))).isNull();
        assertThat(bills.get(m.key("waived"))).isNull();
        assertThat(bills.get(m.key("reading"))).isEqualTo("READING");
        assertThat(bills.get(m.key("added"))).isEqualTo("ADDED");
        assertThat(bills.get(m.key("reviewed"))).isEqualTo("REVIEWED");
        assertThat(bills.get(m.key("unreadable"))).isEqualTo("UNREADABLE");
        assertThat(bills.get(m.key("withdrawal"))).isNull();
        assertThat(bills.get(m.key("orderRefund"))).isNull();
        assertThat(bills.get(m.key("disputeRefund"))).isNull();
        assertThat(bills.get(m.key("pendingOctober"))).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("billSummary: PENDING since the start, eligible and unwaived; READING and UNREADABLE for every bill; this outlet only, whatever the filters")
    void summary() throws Exception {
        var m = new Matrix();
        for (String query : List.of("", "size=1", "kinds=TOP_UP", "months=2026-08", "statuses=RETURNED",
                "bills=REVIEWED", "bills=PENDING&size=1")) {
            var s = ok(m.buyer, query).get("billSummary");
            assertThat(s.get("pending").asInt()).describedAs(query).isEqualTo(4); // atStart, order, qs, October
            // M2: the one before the start counts too, as bills=READING returns it.
            assertThat(s.get("reading").asInt()).describedAs(query).isEqualTo(2);
            assertThat(s.get("unreadable").asInt()).describedAs(query).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("monthTotals[].billsPending: PENDING in each Asia/Kolkata month, filters ignored, totals unchanged")
    void monthTotals() throws Exception {
        var m = new Matrix();
        var plain = ok(m.buyer, "months=2026-10,2026-09,2026-08").get("monthTotals");
        var filtered = ok(m.buyer, "months=2026-10,2026-09,2026-08&bills=REVIEWED&kinds=ORDER_PAYMENT").get("monthTotals");
        assertThat(filtered).isEqualTo(plain);
        assertThat(plain.get(0).get("month").asText()).isEqualTo("2026-10");
        assertThat(plain.get(0).get("billsPending").asInt()).isEqualTo(1);
        assertThat(plain.get(1).get("billsPending").asInt()).isEqualTo(3); // atStart is 1 September in India
        assertThat(plain.get(2).get("billsPending").asInt()).isZero();     // justBefore is 31 August in India
        // The ledger's totals are as they always were.
        assertThat(plain.get(1).get("spent").decimalValue()).isEqualByComparingTo("100");
        assertThat(plain.get(1).get("added").decimalValue()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("tracking off (blank start): nothing is PENDING anywhere, bills that exist still show and count")
    void trackingOff() throws Exception {
        var m = new Matrix();
        tracking.setStartForTests(null);
        var data = ok(m.buyer, "size=100&months=2026-10,2026-09,2026-08");
        var bills = bills(data);
        assertThat(bills.values()).doesNotContain("PENDING");
        assertThat(bills.get(m.key("readingBeforeStart"))).isEqualTo("READING");
        assertThat(bills.get(m.key("reviewed"))).isEqualTo("REVIEWED");
        assertThat(data.get("billSummary").get("pending").asInt()).isZero();
        assertThat(data.get("billSummary").get("reading").asInt()).isEqualTo(2);
        assertThat(data.get("billSummary").get("unreadable").asInt()).isEqualTo(1);
        data.get("monthTotals").forEach(month -> assertThat(month.get("billsPending").asInt()).isZero());
        assertThat(keys(ok(m.buyer, "bills=PENDING"))).isEmpty();
    }

    @Test
    @DisplayName("bills= keeps the rows with those statuses, composes with kinds and months, and rejects anything else with a plain 400")
    void filter() throws Exception {
        var m = new Matrix();
        assertThat(keys(ok(m.buyer, "bills=PENDING"))).containsExactly(
                m.key("pendingOctober"), m.key("pendingQs"), m.key("pendingOrder"), m.key("atStart"));
        assertThat(keys(ok(m.buyer, "bills=reading,UNREADABLE"))).containsExactly(
                m.key("unreadable"), m.key("reading"), m.key("readingBeforeStart"));
        assertThat(keys(ok(m.buyer, "bills=ADDED"))).containsExactly(m.key("added"));
        assertThat(keys(ok(m.buyer, "bills=REVIEWED"))).containsExactly(m.key("reviewed"));
        assertThat(keys(ok(m.buyer, "bills=PENDING&kinds=ORDER_PAYMENT&months=2026-09"))).containsExactly(
                m.key("pendingOrder"), m.key("atStart"));
        assertThat(keys(ok(m.buyer, "bills=PENDING&statuses=RETURNED"))).isEmpty();

        for (String bad : List.of("NOT_REQUIRED", "MISSING", "PENDING,foo")) {
            var reply = history(m.buyer, "bills=" + bad);
            assertThat(reply.status()).describedAs(bad).isEqualTo(400);
            assertThat(reply.body().at("/error/message").asText()).contains("is not a bill status")
                    .contains("PENDING, READING, ADDED, REVIEWED or UNREADABLE");
        }
    }

    @Test
    @DisplayName("a filtered list pages exactly: more than two pages, rows sharing an instant, nothing repeated or skipped")
    void filterPaging() throws Exception {
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "1000", "start");
        for (int i = 0; i < 15; i++) {
            String at = "2026-09-%02d 10:00:00.123456".formatted(2 + i % 5);
            pay(seed, at, ORDER_PAYMENT, null);                       // pending
            pay(seed, at, QUICKSCAN_PAYMENT, "quickscan-" + buyer.outletId() + "x9" + i);
            waiver(buyer.outletId(), seed.lastId);                    // not pending
            pay(seed, at, ORDER_PAYMENT, null);
            invoice(buyer.outletId(), seed.lastId, "READ", null, null, null); // ADDED
        }
        seed.sync();

        var pending = walk(buyer, "bills=PENDING", 4);
        var expected = jdbc.queryForList("""
                select concat('L', t.id) from wallet_transaction t
                 where t.wallet_id = ? and t.kind = 'ORDER_PAYMENT'
                   and not exists (select 1 from wallet_entry_invoice i where i.wallet_transaction_id = t.id)
                 order by t.created_at desc, t.id desc
                """, String.class, seed.walletId);
        assertThat(expected).hasSize(15);
        assertThat(pending).doesNotHaveDuplicates().isEqualTo(expected);

        var both = walk(buyer, "bills=PENDING,ADDED&kinds=ORDER_PAYMENT", 7);
        assertThat(both).hasSize(30).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the bill status costs the same number of statements for a page of 2 rows as for 40: no query per row")
    void oneQueryPerPage() throws Exception {
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "1000", "start");
        for (int i = 0; i < 10; i++) {
            String at = "2026-09-%02d 10:00:00.000000".formatted(2 + i);
            pay(seed, at, ORDER_PAYMENT, null);
            pay(seed, at, QUICKSCAN_PAYMENT, "quickscan-" + buyer.outletId() + "x8" + i);
            invoice(buyer.outletId(), seed.lastId, i % 2 == 0 ? "READ" : "UNREADABLE", null, "Shop", "N" + i);
            pay(seed, at, QUICKSCAN_PAYMENT, "quickscan-" + buyer.outletId() + "x7" + i);
            waiver(buyer.outletId(), seed.lastId);
            pay(seed, at, ORDER_PAYMENT, null);
            invoice(buyer.outletId(), seed.lastId, "READING", null, null, null);
        }
        seed.sync();

        for (String filter : List.of("", "&bills=PENDING,READING,ADDED,UNREADABLE")) {
            ok(buyer, "months=2026-09&size=2" + filter); // warm up
            CountingStatementInspector.reset();
            var small = ok(buyer, "months=2026-09&size=2" + filter);
            int smallCount = CountingStatementInspector.count();
            CountingStatementInspector.reset();
            var large = ok(buyer, "months=2026-09&size=40" + filter);
            int largeCount = CountingStatementInspector.count();

            assertThat(small.get("items")).hasSize(2);
            assertThat(large.get("items").size()).isGreaterThanOrEqualTo(30);
            assertThat(largeCount).describedAs("statements for 40 rows vs 2 rows" + filter).isEqualTo(smallCount);
        }
    }

    // ── the wallet's recent[] (the Wallet screen's 'Recent' card) ─────────

    private JsonNode walletData(Buyer buyer) throws Exception {
        var reply = t.wallet(buyer);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    /** "L<id>" to the item's {@code bill} node, failing if an item has no {@code bill} field at all. */
    private static Map<String, JsonNode> recentBillNodes(JsonNode wallet) {
        var out = new LinkedHashMap<String, JsonNode>();
        wallet.get("recent").forEach(item -> {
            assertThat(item.has("bill")).describedAs("recent item %s has a bill field", item).isTrue();
            out.put("L" + item.get("id").asLong(), item.get("bill"));
        });
        return out;
    }

    private static Map<String, String> recentBills(JsonNode wallet) {
        var out = new LinkedHashMap<String, String>();
        recentBillNodes(wallet).forEach((key, bill) -> out.put(key, bill.isNull() ? null : bill.get("status").asText()));
        return out;
    }

    /** Every recent[] item's {@code bill} is exactly the History list's for the same entry. */
    private void assertRecentAgreesWithHistory(Buyer buyer) throws Exception {
        var recent = recentBillNodes(walletData(buyer));
        assertThat(recent).isNotEmpty();
        var history = new LinkedHashMap<String, JsonNode>();
        ok(buyer, "size=100").get("items").forEach(item -> history.put(item.get("key").asText(), item.get("bill")));
        for (var e : recent.entrySet()) {
            assertThat(history).describedAs(e.getKey()).containsKey(e.getKey());
            assertThat(e.getValue()).describedAs("recent vs History for " + e.getKey()).isEqualTo(history.get(e.getKey()));
        }
    }

    @Test
    @DisplayName("wallet recent[]: each item's bill is the History's for the same entry (every status and null); another outlet's are its own")
    void recentCarriesBill() throws Exception {
        var m = new Matrix();
        var bills = recentBills(walletData(m.buyer));
        // The ten newest entries.
        assertThat(bills.keySet()).containsExactly(m.key("pendingOctober"), m.key("disputeRefund"), m.key("orderRefund"),
                m.key("withdrawal"), m.key("unreadable"), m.key("reviewed"), m.key("added"), m.key("reading"),
                m.key("waived"), m.key("qsReturn"));
        assertThat(bills.get(m.key("pendingOctober"))).isEqualTo("PENDING");
        assertThat(bills.get(m.key("unreadable"))).isEqualTo("UNREADABLE");
        assertThat(bills.get(m.key("reviewed"))).isEqualTo("REVIEWED");
        assertThat(bills.get(m.key("added"))).isEqualTo("ADDED");
        assertThat(bills.get(m.key("reading"))).isEqualTo("READING");
        assertThat(bills.get(m.key("waived"))).isNull();       // 'No bill needed': no chip
        assertThat(bills.get(m.key("qsReturn"))).isNull();
        assertThat(bills.get(m.key("withdrawal"))).isNull();
        assertThat(bills.get(m.key("orderRefund"))).isNull();
        assertThat(bills.get(m.key("disputeRefund"))).isNull();
        assertRecentAgreesWithHistory(m.buyer);

        var other = recentBills(walletData(m.other));
        assertThat(other.keySet()).containsExactly("L" + m.otherId.get("reading"), "L" + m.otherId.get("pending"),
                "L" + m.otherId.get("topUp"));
        assertThat(other.get("L" + m.otherId.get("reading"))).isEqualTo("READING");
        assertThat(other.get("L" + m.otherId.get("pending"))).isEqualTo("PENDING");
        assertThat(other.get("L" + m.otherId.get("topUp"))).isNull();
        assertRecentAgreesWithHistory(m.other);
    }

    @Test
    @DisplayName("wallet recent[]: the tracking start to the microsecond, a bill before it, returned and cancelled payments, a dispute; tracking off")
    void recentTrackingStart() throws Exception {
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1000", "start"); // the 11th newest: not in recent[]
        long beforeStart = pay(seed, "2026-08-20 10:00:00.000000", ORDER_PAYMENT, null).lastId;
        long readingBefore = pay(seed, "2026-08-25 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "r01").lastId;
        invoice(o, readingBefore, "READING", null, null, null);
        long justBefore = pay(seed, "2026-08-31 18:29:59.999999", ORDER_PAYMENT, null).lastId;
        long atStart = pay(seed, "2026-08-31 18:30:00.000000", ORDER_PAYMENT, null).lastId;
        long returned = pay(seed, "2026-09-02 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "r02").lastId;
        seed.credit("2026-09-02 11:00:00.000000", QUICKSCAN_RETURN, "10", "back");
        jdbc.update("update wallet_transaction set reference = ? where id = ?", "quickscan-return-" + o + "r02", seed.lastId);
        long orderA = seed.order("RC-A-" + o);
        long orderB = seed.order("RC-B-" + o);
        long cancelled = seed.add("2026-09-03 10:00:00.000000", "DEBIT", ORDER_PAYMENT, "10", "c", orderA, null).lastId;
        long disputed = seed.add("2026-09-03 10:00:01.000000", "DEBIT", ORDER_PAYMENT, "10", "d", orderB, null).lastId;
        seed.add("2026-09-04 10:00:00.000000", "CREDIT", ORDER_REFUND, "10", "c back", orderA, null);
        seed.add("2026-09-04 10:00:01.000000", "CREDIT", DISPUTE_REFUND, "4", "d back", orderB, null);
        seed.sync();

        var bills = recentBills(walletData(buyer));
        assertThat(bills).hasSize(10);
        assertThat(bills.get("L" + beforeStart)).isNull();
        assertThat(bills.get("L" + readingBefore)).isEqualTo("READING");
        assertThat(bills.get("L" + justBefore)).isNull();
        assertThat(bills.get("L" + atStart)).isEqualTo("PENDING");
        assertThat(bills.get("L" + returned)).isNull();
        assertThat(bills.get("L" + cancelled)).isNull();
        assertThat(bills.get("L" + disputed)).isEqualTo("PENDING");
        assertRecentAgreesWithHistory(buyer);

        tracking.setStartForTests(null);
        var off = recentBills(walletData(buyer));
        assertThat(off.values()).doesNotContain("PENDING");
        assertThat(off.get("L" + readingBefore)).isEqualTo("READING");
        assertThat(off.get("L" + atStart)).isNull();
        assertRecentAgreesWithHistory(buyer);
    }

    @Test
    @DisplayName("wallet recent[]: the bill costs the same number of statements for 2 entries as for 10: no query per row")
    void recentOneQuery() throws Exception {
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "1000", "start");
        pay(seed, "2026-09-02 10:00:00.000000", ORDER_PAYMENT, null);
        seed.sync();
        walletData(buyer); // warm up
        CountingStatementInspector.reset();
        assertThat(walletData(buyer).get("recent")).hasSize(2);
        int small = CountingStatementInspector.count();

        for (int i = 0; i < 4; i++) {
            String at = "2026-09-%02d 10:00:00.000000".formatted(3 + i);
            pay(seed, at, ORDER_PAYMENT, null);
            pay(seed, at, QUICKSCAN_PAYMENT, "quickscan-" + o + "q" + i);
            invoice(o, seed.lastId, i % 2 == 0 ? "READ" : "UNREADABLE", null, null, null);
            pay(seed, at, QUICKSCAN_PAYMENT, "quickscan-" + o + "w" + i);
            waiver(o, seed.lastId);
        }
        seed.sync();
        CountingStatementInspector.reset();
        var large = walletData(buyer);
        int largeCount = CountingStatementInspector.count();
        assertThat(large.get("recent")).hasSize(10);
        assertThat(recentBills(large).values()).contains("PENDING", "ADDED", "UNREADABLE");
        assertThat(largeCount).describedAs("statements for 10 recent entries vs 2").isEqualTo(small);
    }

    // ── the details page ─────────────────────────────────────────────────

    @Test
    @DisplayName("details: billStatus by the same rules, NOT_REQUIRED when waived; canWaiveBill and canUndoWaiver")
    void details() throws Exception {
        var m = new Matrix();
        Map<String, Object[]> expected = new LinkedHashMap<>();
        // name -> {billStatus, canAddBill, canWaiveBill, canUndoWaiver}
        expected.put("topUp", new Object[]{null, false, false, false});
        expected.put("beforeStart", new Object[]{null, true, false, false}); // L1: only "Add bill" before the start
        expected.put("atStart", new Object[]{"PENDING", true, true, false});
        expected.put("pendingQs", new Object[]{"PENDING", true, true, false});
        expected.put("returnedQs", new Object[]{null, true, false, false});
        expected.put("waived", new Object[]{"NOT_REQUIRED", true, false, true});
        expected.put("reading", new Object[]{"READING", true, false, false});
        expected.put("readingBeforeStart", new Object[]{"READING", true, false, false});
        expected.put("added", new Object[]{"ADDED", true, false, false});
        expected.put("reviewed", new Object[]{"REVIEWED", true, false, false});
        expected.put("unreadable", new Object[]{"UNREADABLE", true, false, false});
        expected.put("withdrawal", new Object[]{null, false, false, false});
        for (var e : expected.entrySet()) {
            var d = detail(m.buyer, m.id.get(e.getKey()));
            var want = e.getValue();
            assertThat(d.get("billStatus").isNull() ? null : d.get("billStatus").asText())
                    .describedAs(e.getKey()).isEqualTo(want[0]);
            assertThat(d.at("/actions/canAddBill").asBoolean()).describedAs(e.getKey()).isEqualTo(want[1]);
            assertThat(d.at("/actions/canWaiveBill").asBoolean()).describedAs(e.getKey()).isEqualTo(want[2]);
            assertThat(d.at("/actions/canUndoWaiver").asBoolean()).describedAs(e.getKey()).isEqualTo(want[3]);
            // Every field it had is still there.
            assertThat(d.has("invoice")).isTrue();
            assertThat(d.has("references")).isTrue();
        }
    }

    // ── review fixes (D-116 review: H1, M1, M2, M3, L2) ──────────────────

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private com.costonomy.mp.wallet.WalletTopUpSupport.Reply waive(Buyer buyer, long entryId) throws Exception {
        return InvoiceTestSupport.call(mvc, json, "PUT", buyer.token(), InvoiceTestSupport.path(buyer.outletId(), entryId) + "/waiver");
    }

    @Test
    @DisplayName("H1: an order payment whose order was cancelled (ORDER_REFUND) asks for no bill in the list, filter, counts, months, details or statement; a DISPUTE_REFUND keeps it PENDING; a bill on a cancelled one still shows")
    void cancelledOrderNeedsNoBill() throws Exception {
        tracking.setStartForTests(LocalDate.now(IST).minusDays(5));
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        var paidAt = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String at = UTC.format(paidAt);
        String later = daysAgo(1);
        seed.credit(daysAgo(20), TOP_UP, "1000", "topup");
        long orderA = seed.order("H1-A-" + o);
        long orderB = seed.order("H1-B-" + o);
        long orderC = seed.order("H1-C-" + o);
        // In time order, so the running balance reconciles in the statement.
        long cancelled = seed.add(at, "DEBIT", ORDER_PAYMENT, "10", "cancelled", orderA, null).lastId;
        long disputed = seed.add(at, "DEBIT", ORDER_PAYMENT, "10", "disputed", orderB, null).lastId;
        long billed = seed.add(at, "DEBIT", ORDER_PAYMENT, "10", "billedCancelled", orderC, null).lastId;
        invoice(o, billed, "READ", null, "Shop C", "B-3");
        seed.add(later, "CREDIT", ORDER_REFUND, "10", "cancelledBack", orderA, null);
        seed.add(later, "CREDIT", DISPUTE_REFUND, "4", "disputedBack", orderB, null);
        seed.add(later, "CREDIT", ORDER_REFUND, "10", "billedBack", orderC, null);
        seed.sync();

        var month = java.time.YearMonth.from(paidAt.atZone(IST));
        var data = ok(buyer, "size=100&months=" + month);
        var bills = bills(data);
        assertThat(bills.get("L" + cancelled)).isNull();
        assertThat(bills.get("L" + disputed)).isEqualTo("PENDING");
        assertThat(bills.get("L" + billed)).isEqualTo("ADDED");
        assertThat(data.at("/billSummary/pending").asInt()).isEqualTo(1);
        assertThat(data.at("/monthTotals/0/billsPending").asInt()).isEqualTo(1);
        assertThat(keys(ok(buyer, "bills=PENDING"))).containsExactly("L" + disputed);
        assertThat(keys(ok(buyer, "bills=ADDED"))).containsExactly("L" + billed);

        var c = detail(buyer, cancelled);
        assertThat(c.get("billStatus").isNull()).isTrue();
        assertThat(c.at("/actions/canWaiveBill").asBoolean()).isFalse();
        assertThat(detail(buyer, disputed).get("billStatus").asText()).isEqualTo("PENDING");
        assertThat(detail(buyer, disputed).at("/actions/canWaiveBill").asBoolean()).isTrue();
        assertThat(detail(buyer, billed).get("billStatus").asText()).isEqualTo("ADDED");
        assertThat(waive(buyer, cancelled).status()).isEqualTo(422);

        var csv = statement(buyer, "CSV");
        assertThat(rowOf(csv, "cancelled")).endsWith(",cancelled,,,");
        assertThat(rowOf(csv, "disputed")).endsWith(",disputed,Pending,,");
        assertThat(rowOf(csv, "billedCancelled")).endsWith(",billedCancelled,Added,Shop C,B-3");
    }

    @Test
    @DisplayName("M2: reading and unreadable count every bill the bills= filter returns (before the start, returned, cancelled, waived too); a bill wins over a waiver")
    void countsMatchFilters() throws Exception {
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1000", "start");
        invoice(o, pay(seed, "2026-08-20 10:00:00.000000", ORDER_PAYMENT, null).lastId, "UNREADABLE", null, null, null);
        invoice(o, pay(seed, "2026-09-04 10:00:00.000000", QUICKSCAN_PAYMENT, "quickscan-" + o + "m01").lastId,
                "UNREADABLE", null, null, null);
        seed.credit("2026-09-04 11:00:00.000000", QUICKSCAN_RETURN, "10", "back");
        jdbc.update("update wallet_transaction set reference = ? where id = ?", "quickscan-return-" + o + "m01", seed.lastId);
        long order = seed.order("M2-" + o);
        invoice(o, seed.add("2026-09-05 10:00:00.000000", "DEBIT", ORDER_PAYMENT, "10", "c", order, null).lastId,
                "UNREADABLE", null, null, null);
        seed.add("2026-09-06 10:00:00.000000", "CREDIT", ORDER_REFUND, "10", "c back", order, null);
        invoice(o, pay(seed, "2026-09-07 10:00:00.000000", ORDER_PAYMENT, null).lastId, "UNREADABLE", null, null, null);
        // Both rows, as a race before M1 could leave them: the bill decides, and is counted.
        long both = pay(seed, "2026-09-08 10:00:00.000000", ORDER_PAYMENT, null).lastId;
        invoice(o, both, "READING", null, null, null);
        waiver(o, both);
        pay(seed, "2026-09-09 10:00:00.000000", ORDER_PAYMENT, null); // PENDING
        seed.sync();

        var unreadable = walk(buyer, "bills=UNREADABLE", 2);
        assertThat(unreadable).hasSize(4);
        var data = ok(buyer, "");
        assertThat(data.at("/billSummary/unreadable").asInt()).isEqualTo(unreadable.size());
        assertThat(data.at("/billSummary/reading").asInt()).isEqualTo(walk(buyer, "bills=READING", 2).size()).isEqualTo(1);
        assertThat(data.at("/billSummary/pending").asInt()).isEqualTo(walk(buyer, "bills=PENDING", 2).size()).isEqualTo(1);
        assertThat(bills(data).get("L" + both)).isEqualTo("READING");
        assertThat(detail(buyer, both).get("billStatus").asText()).isEqualTo("READING");
        assertThat(detail(buyer, both).at("/actions/canWaiveBill").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("M3: an UNREADABLE bill filled in by hand is REVIEWED in the list, filter, counts, details and statement")
    void unreadableReviewedIsReviewed() throws Exception {
        tracking.setStartForTests(LocalDate.now(IST).minusDays(5));
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        seed.credit(daysAgo(20), TOP_UP, "1000", "topup");
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "open");
        long open = seed.lastId;
        invoice(o, open, "UNREADABLE", null, null, null);
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "byHand");
        long byHand = seed.lastId;
        invoice(o, byHand, "UNREADABLE", "{\"supplier\":{\"id\":null,\"name\":\"Corner Fish\"},\"invoiceNumber\":\"H-1\"}",
                null, null);
        seed.sync();

        var data = ok(buyer, "");
        assertThat(bills(data).get("L" + open)).isEqualTo("UNREADABLE");
        assertThat(bills(data).get("L" + byHand)).isEqualTo("REVIEWED");
        assertThat(data.at("/billSummary/unreadable").asInt()).isEqualTo(1);
        assertThat(keys(ok(buyer, "bills=UNREADABLE"))).containsExactly("L" + open);
        assertThat(keys(ok(buyer, "bills=REVIEWED"))).containsExactly("L" + byHand);
        assertThat(detail(buyer, byHand).get("billStatus").asText()).isEqualTo("REVIEWED");
        assertThat(rowOf(statement(buyer, "CSV"), "byHand")).endsWith(",byHand,Reviewed,Corner Fish,H-1");
    }

    @Test
    @DisplayName("L2: a later page (cursor) has billSummary null and still each month's billsPending")
    void summaryOnFirstPageOnly() throws Exception {
        var m = new Matrix();
        var first = ok(m.buyer, "size=2");
        assertThat(first.get("billSummary").isNull()).isFalse();
        var second = ok(m.buyer, "size=2&cursor=" + first.get("nextCursor").asText());
        assertThat(second.has("billSummary") && !second.get("billSummary").isNull()).isFalse();
        var withMonths = ok(m.buyer, "size=2&months=2026-09&cursor=" + first.get("nextCursor").asText());
        assertThat(withMonths.at("/monthTotals/0/billsPending").asInt()).isEqualTo(3);
    }

    // ── the statement ────────────────────────────────────────────────────

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);

    private static String daysAgo(int days) {
        return UTC.format(Instant.now().minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
    }

    private String statement(Buyer buyer, String format) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get("/api/v1/outlets/" + buyer.outletId()
                                + "/wallet/statement?range=LAST_30&format=" + format)
                        .header("Authorization", "Bearer " + buyer.token()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).describedAs(response.getContentAsString()).isEqualTo(200);
        return new String(response.getContentAsByteArray(),
                "PDF".equals(format) ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
    }

    private static String rowOf(String csv, String note) {
        for (String line : csv.split("\r\n")) {
            if (line.contains("," + note + ",")) {
                return line;
            }
        }
        throw new AssertionError("no row with note " + note);
    }

    @Test
    @DisplayName("statement: Bill, Shop and Bill no. by the same rules (reviewed values win), ledger columns unchanged, one query")
    void statementColumns() throws Exception {
        tracking.setStartForTests(LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(5));
        var buyer = t.newBuyer();
        var seed = new LedgerSeed(t, jdbc, buyer);
        long o = buyer.outletId();
        seed.credit(daysAgo(20), TOP_UP, "1000", "topup");
        seed.debit(daysAgo(10), ORDER_PAYMENT, "10", "before");
        seed.debit(daysAgo(10), ORDER_PAYMENT, "10", "beforeWaived");
        waiver(o, seed.lastId);
        seed.debit(daysAgo(10), ORDER_PAYMENT, "10", "beforeBill");
        invoice(o, seed.lastId, "READ", "{\"supplier\":{\"id\":null,\"name\":\" \"},\"invoiceNumber\":\"RB-1\"}",
                "Read Shop", "R-0");
        seed.debit(daysAgo(2), ORDER_PAYMENT, "10", "pending");
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "waived");
        waiver(o, seed.lastId);
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "added");
        invoice(o, seed.lastId, "READ", null, "Kosta Read", "R-1");
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "reviewed");
        invoice(o, seed.lastId, "READ", "{\"supplier\":{\"id\":7,\"name\":\"Kosta Reviewed\"},\"invoiceNumber\":\"REV-9\"}",
                "Kosta Read", "R-2");
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "reading");
        invoice(o, seed.lastId, "READING", null, null, null);
        seed.debit(daysAgo(2), QUICKSCAN_PAYMENT, "10", "unreadable");
        invoice(o, seed.lastId, "UNREADABLE", null, null, null);
        pay(seed, daysAgo(2), QUICKSCAN_PAYMENT, "quickscan-" + o + "x55");
        jdbc.update("update wallet_transaction set reason = 'returnedQs' where id = ?", seed.lastId);
        seed.credit(daysAgo(2), QUICKSCAN_RETURN, "10", "qsReturn");
        jdbc.update("update wallet_transaction set reference = ? where id = ?", "quickscan-return-" + o + "x55", seed.lastId);
        seed.sync();

        var csv = statement(buyer, "CSV");
        assertThat(rowOf(csv, "topup")).endsWith(",Credit,1000.00,1000.00,topup,,,");
        assertThat(rowOf(csv, "before")).endsWith(",Debit,10.00,990.00,before,,,");
        assertThat(rowOf(csv, "beforeWaived")).endsWith(",beforeWaived,No bill needed,,");
        assertThat(rowOf(csv, "beforeBill")).endsWith(",beforeBill,Reviewed,Read Shop,RB-1"); // a blank reviewed shop falls back
        assertThat(rowOf(csv, "pending")).endsWith(",pending,Pending,,");
        assertThat(rowOf(csv, "waived")).endsWith(",waived,No bill needed,,");
        assertThat(rowOf(csv, "added")).endsWith(",added,Added,Kosta Read,R-1");
        assertThat(rowOf(csv, "reviewed")).endsWith(",reviewed,Reviewed,Kosta Reviewed,REV-9");
        assertThat(rowOf(csv, "reading")).endsWith(",reading,Reading,,");
        assertThat(rowOf(csv, "unreadable")).endsWith(",unreadable,Unreadable,,");
        assertThat(rowOf(csv, "returnedQs")).endsWith(",returnedQs,,,");
        assertThat(rowOf(csv, "qsReturn")).endsWith(",qsReturn,,,");
        // Still reconciles, and the summary is the ledger's.
        assertThat(csv).contains("Opening balance (INR),0.00").contains("Total added (INR),1010.00")
                .contains("Total spent (INR),100.00").contains("Closing balance (INR),910.00");

        var pdf = statement(buyer, "PDF");
        assertThat(pdf).contains("(Bill) Tj").contains("(Pending) Tj").contains("(No bill needed) Tj")
                .contains("(Kosta Reviewed) Tj").contains("(REV-9) Tj").contains("(Unreadable) Tj");

        // One query for the bill columns, however many rows.
        CountingStatementInspector.reset();
        statement(buyer, "CSV");
        int before = CountingStatementInspector.count();
        for (int i = 0; i < 12; i++) {
            seed.debit(daysAgo(1), QUICKSCAN_PAYMENT, "1", "more" + i);
            invoice(o, seed.lastId, i % 2 == 0 ? "READ" : "READING", null, "S", "N");
            seed.debit(daysAgo(1), ORDER_PAYMENT, "1", "morePending" + i);
        }
        seed.sync();
        CountingStatementInspector.reset();
        var more = statement(buyer, "CSV");
        assertThat(CountingStatementInspector.count()).isEqualTo(before);
        assertThat(rowOf(more, "morePending3")).endsWith(",Pending,,");
    }
}
