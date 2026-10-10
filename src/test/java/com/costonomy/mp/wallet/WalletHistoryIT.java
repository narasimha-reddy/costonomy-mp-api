package com.costonomy.mp.wallet;

import com.costonomy.mp.payment.provider.MockPaymentProvider;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.wallet.WalletTopUpSupport.Buyer;
import com.costonomy.mp.wallet.WalletTopUpSupport.Reply;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
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
import java.util.HashSet;
import java.util.List;

import static com.costonomy.mp.wallet.domain.WalletEntryKind.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wallet transaction history (D-108): pagination that cannot drop or repeat a row,
 * filters, Asia/Kolkata months, totals, returned top-ups, withdrawal progress and tenancy.
 * Ledger rows are written straight into the database at exact instants; the reads go
 * through the API.
 */
@AutoConfigureMockMvc
class WalletHistoryIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockPaymentProvider provider;

    private WalletTopUpSupport t;

    @BeforeEach
    void setUp() {
        t = new WalletTopUpSupport(mvc, json, jdbc);
    }

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

    private static List<String> keys(JsonNode data) {
        var out = new ArrayList<String>();
        data.get("items").forEach(item -> out.add(item.get("key").asText()));
        return out;
    }

    private static List<String> months(JsonNode array) {
        var out = new ArrayList<String>();
        array.forEach(m -> out.add(m.asText()));
        return out;
    }

    /** Every page, following the cursor, as the client would. */
    private List<String> walk(Buyer buyer, String query, int size) throws Exception {
        var all = new ArrayList<String>();
        String cursor = null;
        for (int guard = 0; guard < 200; guard++) {
            var data = ok(buyer, query + (query.isEmpty() ? "" : "&") + "size=" + size
                    + (cursor == null ? "" : "&cursor=" + cursor));
            all.addAll(keys(data));
            if (data.get("nextCursor").isNull()) {
                return all;
            }
            cursor = data.get("nextCursor").asText();
        }
        throw new AssertionError("cursor never ended");
    }

    @Nested
    @DisplayName("pagination")
    class Pagination {

        @Test
        @DisplayName("rows sharing one timestamp are neither repeated nor skipped across pages")
        void equalTimestamps() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1000", "start");
            for (int i = 0; i < 30; i++) {
                seed.debit("2026-08-15 10:00:00.123456", ORDER_PAYMENT, "1", "same instant " + i);
            }
            seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "5", "later");
            seed.sync();

            var all = walk(buyer, "", 7);

            assertThat(all).hasSize(32).doesNotHaveDuplicates();
            var ids = jdbc.queryForList("select concat('L', id) from wallet_transaction where wallet_id = ? "
                    + "order by created_at desc, id desc", String.class, seed.walletId);
            assertThat(all).isEqualTo(ids);
        }

        @Test
        @DisplayName("rows arriving while a customer scrolls do not shift the pages they have yet to read")
        void newRowsDoNotShiftPages() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-07-01 10:00:00.000000", TOP_UP, "1000", "start");
            for (int i = 0; i < 12; i++) {
                seed.debit("2026-07-02 10:00:0" + (i % 10) + ".000000", ORDER_PAYMENT, "1", "d" + i);
            }
            seed.sync();

            var first = ok(buyer, "size=5");
            var seen = new ArrayList<>(keys(first));
            // Three movements land at the front while the customer is on page one.
            seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1", "new1");
            seed.credit("2026-08-01 10:00:00.000000", TOP_UP, "1", "new2");
            seed.credit("2026-08-02 10:00:00.000000", TOP_UP, "1", "new3");
            String cursor = first.get("nextCursor").asText();
            while (cursor != null) {
                var page = ok(buyer, "size=5&cursor=" + cursor);
                seen.addAll(keys(page));
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            }

            var expected = jdbc.queryForList("select concat('L', id) from wallet_transaction where wallet_id = ? "
                    + "and created_at < '2026-08-01' order by created_at desc, id desc", String.class, seed.walletId);
            assertThat(seen).doesNotHaveDuplicates().isEqualTo(expected);
        }

        @Test
        @DisplayName("returned top-ups and ledger rows at the same instant interleave without loss or repeat")
        void mixedSources() throws Exception {
            var buyer = t.newBuyer();
            long user = t.userId(buyer.token());
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-08-01 09:00:00.000000", TOP_UP, "500", "start");
            for (int i = 0; i < 4; i++) {
                seed.debit("2026-08-10 10:00:00.000000", QUICKSCAN_PAYMENT, "1", "q" + i);
                seed.returnedTopUp(user, "2026-08-10 10:00:00.000000", "100", "card", "1007");
            }
            seed.credit("2026-08-11 10:00:00.000000", TOP_UP, "1", "after");
            seed.sync();

            for (int size : new int[]{1, 2, 3, 4, 9}) {
                var all = walk(buyer, "", size);
                assertThat(all).describedAs("size " + size).hasSize(10).doesNotHaveDuplicates();
                // Newest first; at one instant ledger rows come before returned top-ups, highest id first.
                assertThat(all.get(0)).startsWith("L");
                assertThat(all.subList(1, 5)).allMatch(k -> k.startsWith("L"));
                assertThat(all.subList(5, 9)).allMatch(k -> k.startsWith("T"));
                assertThat(all.get(9)).startsWith("L");
            }
        }

        @Test
        @DisplayName("size defaults to 20, is capped at 100 by refusal, and a bad cursor is refused")
        void sizeAndCursor() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.bulkCredits("2026-08-01 10:00:00.000000", 25).sync();

            var page = ok(buyer, "");
            assertThat(page.get("items")).hasSize(20);
            assertThat(page.get("nextCursor").isNull()).isFalse();
            assertThat(ok(buyer, "size=100").get("items")).hasSize(25);
            assertThat(ok(buyer, "size=100").get("nextCursor").isNull()).isTrue();
            assertThat(history(buyer, "size=101").status()).isEqualTo(400);
            assertThat(history(buyer, "size=0").status()).isEqualTo(400);
            assertThat(history(buyer, "cursor=not-a-cursor").status()).isEqualTo(400);
            assertThat(history(buyer, "cursor=" + "MTox").status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("months are Asia/Kolkata months")
    class Months {

        @Test
        @DisplayName("an entry at 19:00 UTC on 30 September belongs to October; the IST midnight is exact")
        void boundary() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-09-30 18:29:59.999999", TOP_UP, "100", "last instant of September");
            long sept = seed.lastId;
            seed.credit("2026-09-30 18:30:00.000000", TOP_UP, "10", "first instant of October");
            long oct1 = seed.lastId;
            seed.credit("2026-09-30 19:00:00.000000", TOP_UP, "1", "half past midnight");
            long oct2 = seed.lastId;
            seed.credit("2026-10-31 18:29:59.999999", TOP_UP, "1000", "last instant of October");
            long oct3 = seed.lastId;
            seed.credit("2026-10-31 18:30:00.000000", TOP_UP, "10000", "first instant of November");
            long nov = seed.lastId;
            seed.sync();

            assertThat(keys(ok(buyer, "months=2026-09"))).containsExactly("L" + sept);
            assertThat(keys(ok(buyer, "months=2026-10"))).containsExactly("L" + oct3, "L" + oct2, "L" + oct1);
            assertThat(keys(ok(buyer, "months=2026-11"))).containsExactly("L" + nov);
            assertThat(months(ok(buyer, "").get("availableMonths")))
                    .containsExactly("2026-11", "2026-10", "2026-09");
        }

        @Test
        @DisplayName("month totals are the ledger's credits and debits for the month, whatever else is filtered")
        void totals() throws Exception {
            var buyer = t.newBuyer();
            long user = t.userId(buyer.token());
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-08-15 10:00:00.000000", TOP_UP, "1000.50", "aug");
            seed.debit("2026-09-05 10:00:00.000000", ORDER_PAYMENT, "300.25", "sep spend 1");
            seed.debit("2026-09-06 10:00:00.000000", QUICKSCAN_PAYMENT, "50.00", "sep spend 2");
            seed.credit("2026-09-07 10:00:00.000000", TOP_UP, "200.00", "sep add");
            seed.credit("2026-09-08 10:00:00.000000", ORDER_REFUND, "25.75", "sep refund");
            seed.debit("2026-09-30 19:00:00.000000", ORDER_PAYMENT, "99.00", "actually october");
            // A returned top-up: money left the bank and came back. Not a credit, not a debit.
            seed.returnedTopUp(user, "2026-09-09 10:00:00.000000", "777.00", "upi", null);
            seed.sync();

            var data = ok(buyer, "months=2026-09,2026-08");
            var totals = data.get("monthTotals");
            assertThat(totals).hasSize(2);
            assertThat(totals.get(0).get("month").asText()).isEqualTo("2026-09");
            assertThat(totals.get(0).get("added").decimalValue()).isEqualByComparingTo("225.75");
            assertThat(totals.get(0).get("spent").decimalValue()).isEqualByComparingTo("350.25");
            assertThat(totals.get(1).get("month").asText()).isEqualTo("2026-08");
            assertThat(totals.get(1).get("added").decimalValue()).isEqualByComparingTo("1000.50");
            assertThat(totals.get(1).get("spent").decimalValue()).isEqualByComparingTo("0");
            // The returned top-up is listed but is in no total.
            assertThat(data.get("items")).hasSize(6);

            // A filter on kinds changes the list, not the month's figures.
            var filtered = ok(buyer, "months=2026-09&kinds=QUICKSCAN_PAYMENT");
            assertThat(filtered.get("items")).hasSize(1);
            assertThat(filtered.get("monthTotals").get(0).get("added").decimalValue()).isEqualByComparingTo("225.75");
            assertThat(filtered.get("monthTotals").get(0).get("spent").decimalValue()).isEqualByComparingTo("350.25");
            // October has only the debit that IST puts there.
            var october = ok(buyer, "months=2026-10").get("monthTotals").get(0);
            assertThat(october.get("spent").decimalValue()).isEqualByComparingTo("99.00");
            assertThat(october.get("added").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("without a months filter, totals cover the months on the page")
        void totalsForPageMonths() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-07-15 10:00:00.000000", TOP_UP, "100", "jul");
            seed.credit("2026-08-15 10:00:00.000000", TOP_UP, "10", "aug");
            seed.credit("2026-09-15 10:00:00.000000", TOP_UP, "1", "sep");
            seed.sync();

            var page = ok(buyer, "size=2");

            assertThat(page.get("monthTotals")).hasSize(2);
            assertThat(page.get("monthTotals").get(0).get("month").asText()).isEqualTo("2026-09");
            assertThat(page.get("monthTotals").get(1).get("month").asText()).isEqualTo("2026-08");
            // Every month with history is offered, on every page.
            assertThat(months(page.get("availableMonths"))).containsExactly("2026-09", "2026-08", "2026-07");
        }

        @Test
        @DisplayName("months with nothing are not offered, and a wallet with no history is empty, not an error")
        void availableMonths() throws Exception {
            var buyer = t.newBuyer();
            var empty = ok(buyer, "");
            assertThat(empty.get("items")).isEmpty();
            assertThat(empty.get("availableMonths")).isEmpty();
            assertThat(empty.get("monthTotals")).isEmpty();
            assertThat(empty.get("nextCursor").isNull()).isTrue();

            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-01-15 10:00:00.000000", TOP_UP, "1", "jan");
            seed.credit("2026-04-15 10:00:00.000000", TOP_UP, "1", "apr");
            seed.sync();
            assertThat(months(ok(buyer, "").get("availableMonths"))).containsExactly("2026-04", "2026-01");
            // A month asked for with nothing in it is a month of zeros.
            var none = ok(buyer, "months=2026-02");
            assertThat(none.get("items")).isEmpty();
            assertThat(none.get("monthTotals").get(0).get("added").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("bad months are refused, and so are too many")
        void badMonths() throws Exception {
            var buyer = t.newBuyer();
            assertThat(history(buyer, "months=2026-13").status()).isEqualTo(400);
            assertThat(history(buyer, "months=2026-9").status()).isEqualTo(400);
            assertThat(history(buyer, "months=September").status()).isEqualTo(400);
            var many = new ArrayList<String>();
            for (int i = 1; i <= 25; i++) {
                many.add(java.time.YearMonth.of(2024, 1).plusMonths(i).toString());
            }
            assertThat(history(buyer, "months=" + String.join(",", many)).status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("filters")
    class Filters {

        private Buyer buyer;
        private long augTopUp;
        private long augPay;
        private long sepTopUp;
        private long sepPay;
        private long sepWithdrawalOnItsWay;
        private long sepWithdrawalSent;
        private long sepReturned;

        private void seedMixed() throws Exception {
            buyer = t.newBuyer();
            long user = t.userId(buyer.token());
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-08-10 10:00:00.000000", TOP_UP, "1000", "aug top-up");
            augTopUp = seed.lastId;
            seed.debit("2026-08-11 10:00:00.000000", ORDER_PAYMENT, "100", "aug pay");
            augPay = seed.lastId;
            seed.credit("2026-09-10 10:00:00.000000", TOP_UP, "500", "sep top-up");
            sepTopUp = seed.lastId;
            seed.debit("2026-09-11 10:00:00.000000", ORDER_PAYMENT, "50", "sep pay");
            sepPay = seed.lastId;
            long onItsWay = seed.refund("PROCESSING");
            seed.add("2026-09-12 10:00:00.000000", "DEBIT", WITHDRAWAL, "20", "on its way", null, onItsWay);
            sepWithdrawalOnItsWay = seed.lastId;
            long sent = seed.refund("COMPLETED");
            seed.add("2026-09-13 10:00:00.000000", "DEBIT", WITHDRAWAL, "10", "sent", null, sent);
            sepWithdrawalSent = seed.lastId;
            seed.sync();
            sepReturned = seed.returnedTopUp(user, "2026-09-14 10:00:00.000000", "300", "card", "4242");
        }

        @Test
        @DisplayName("months x kinds x statuses, every combination")
        void combinations() throws Exception {
            seedMixed();
            var all = List.of("T" + sepReturned, "L" + sepWithdrawalSent, "L" + sepWithdrawalOnItsWay,
                    "L" + sepPay, "L" + sepTopUp, "L" + augPay, "L" + augTopUp);
            assertThat(keys(ok(buyer, ""))).isEqualTo(all);

            // months
            assertThat(keys(ok(buyer, "months=2026-08"))).containsExactly("L" + augPay, "L" + augTopUp);
            assertThat(keys(ok(buyer, "months=2026-09"))).hasSize(5);
            assertThat(keys(ok(buyer, "months=2026-08,2026-09"))).isEqualTo(all);

            // kinds
            assertThat(keys(ok(buyer, "kinds=ORDER_PAYMENT"))).containsExactly("L" + sepPay, "L" + augPay);
            assertThat(keys(ok(buyer, "kinds=TOP_UP"))).containsExactly("T" + sepReturned, "L" + sepTopUp, "L" + augTopUp);
            assertThat(keys(ok(buyer, "kinds=WITHDRAWAL,ORDER_PAYMENT")))
                    .containsExactly("L" + sepWithdrawalSent, "L" + sepWithdrawalOnItsWay, "L" + sepPay, "L" + augPay);
            assertThat(keys(ok(buyer, "kinds=order_payment"))).containsExactly("L" + sepPay, "L" + augPay);

            // statuses
            assertThat(keys(ok(buyer, "statuses=RETURNED"))).containsExactly("T" + sepReturned);
            assertThat(keys(ok(buyer, "statuses=IN_PROGRESS"))).containsExactly("L" + sepWithdrawalOnItsWay);
            assertThat(keys(ok(buyer, "statuses=COMPLETED")))
                    .containsExactly("L" + sepWithdrawalSent, "L" + sepPay, "L" + sepTopUp, "L" + augPay, "L" + augTopUp);
            assertThat(keys(ok(buyer, "statuses=COMPLETED,IN_PROGRESS"))).hasSize(6);
            assertThat(keys(ok(buyer, "statuses=FAILED"))).isEmpty();
            assertThat(keys(ok(buyer, "statuses=COMPLETED,RETURNED"))).hasSize(6).contains("T" + sepReturned)
                    .doesNotContain("L" + sepWithdrawalOnItsWay);

            // combinations
            assertThat(keys(ok(buyer, "months=2026-09&kinds=TOP_UP&statuses=RETURNED"))).containsExactly("T" + sepReturned);
            assertThat(keys(ok(buyer, "months=2026-08&kinds=TOP_UP&statuses=RETURNED"))).isEmpty();
            assertThat(keys(ok(buyer, "months=2026-09&kinds=TOP_UP&statuses=COMPLETED"))).containsExactly("L" + sepTopUp);
            assertThat(keys(ok(buyer, "months=2026-09&kinds=WITHDRAWAL&statuses=IN_PROGRESS")))
                    .containsExactly("L" + sepWithdrawalOnItsWay);
            assertThat(keys(ok(buyer, "months=2026-09&kinds=WITHDRAWAL&statuses=COMPLETED")))
                    .containsExactly("L" + sepWithdrawalSent);
            assertThat(keys(ok(buyer, "months=2026-08&kinds=WITHDRAWAL"))).isEmpty();
            // Kinds that can never be returned or in progress.
            assertThat(keys(ok(buyer, "kinds=ORDER_PAYMENT&statuses=RETURNED"))).isEmpty();
            assertThat(keys(ok(buyer, "kinds=ORDER_PAYMENT&statuses=IN_PROGRESS"))).isEmpty();
            assertThat(keys(ok(buyer, "months=2026-09&kinds=ORDER_PAYMENT,TOP_UP&statuses=COMPLETED,RETURNED")))
                    .containsExactly("T" + sepReturned, "L" + sepPay, "L" + sepTopUp);
        }

        @Test
        @DisplayName("filters hold across pages too")
        void filteredPagination() throws Exception {
            seedMixed();
            assertThat(walk(buyer, "months=2026-09&kinds=TOP_UP,ORDER_PAYMENT", 1))
                    .containsExactly("T" + sepReturned, "L" + sepPay, "L" + sepTopUp);
            assertThat(walk(buyer, "statuses=COMPLETED", 2)).hasSize(5).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("unknown kinds and statuses are refused, and empty values mean no filter")
        void unknownValues() throws Exception {
            seedMixed();
            assertThat(history(buyer, "kinds=SALARY").status()).isEqualTo(400);
            assertThat(history(buyer, "statuses=PENDING").status()).isEqualTo(400);
            assertThat(keys(ok(buyer, "months=&kinds=&statuses="))).hasSize(7);
            assertThat(keys(ok(buyer, "kinds=,ORDER_PAYMENT,"))).hasSize(2);
        }
    }

    @Nested
    @DisplayName("what each row says")
    class Rows {

        @Test
        @DisplayName("a withdrawal is on its way until its refund completes, whatever went wrong on the way")
        void withdrawalProgress() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-09-01 10:00:00.000000", REFUND, "1000", "refund");
            var expected = new java.util.LinkedHashMap<String, String>();
            int minute = 0;
            for (String refund : List.of("REQUESTED", "PROCESSING", "FAILED", "NEEDS_REVIEW", "COMPLETED")) {
                long id = seed.refund(refund);
                seed.add("2026-09-02 10:0" + (minute++) + ":00.000000", "DEBIT", WITHDRAWAL, "10", "w " + refund, null, id);
                expected.put("L" + seed.lastId, refund);
            }
            seed.sync();

            var items = ok(buyer, "kinds=WITHDRAWAL").get("items");

            assertThat(items).hasSize(5);
            for (JsonNode item : items) {
                String refund = expected.get(item.get("key").asText());
                assertThat(item.get("refundStatus").asText()).isEqualTo(refund);
                assertThat(item.get("status").asText())
                        .describedAs(refund).isEqualTo("COMPLETED".equals(refund) ? "COMPLETED" : "IN_PROGRESS");
                assertThat(item.get("direction").asText()).isEqualTo("DEBIT");
                assertThat(item.get("instrument").isNull()).isTrue();
            }
            assertThat(keys(ok(buyer, "statuses=IN_PROGRESS"))).hasSize(4);
        }

        @Test
        @DisplayName("a returned top-up is a TOP_UP with status RETURNED and no balance; ledger rows keep theirs")
        void returnedTopUp() throws Exception {
            var buyer = t.newBuyer();
            long user = t.userId(buyer.token());
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "500", "Wallet top-up");
            seed.sync();
            long returned = seed.returnedTopUp(user, "2026-09-02 10:00:00.000000", "250.50", "card", "1007");
            // A top-up still being returned, and one that failed, are not on the history.
            jdbc.update("update wallet_top_up set status = 'REFUND_PENDING' where id = ?", seed.returnedTopUp(
                    user, "2026-09-03 10:00:00.000000", "1", "upi", null));
            jdbc.update("update wallet_top_up set status = 'FAILED' where id = ?", seed.returnedTopUp(
                    user, "2026-09-04 10:00:00.000000", "1", "upi", null));

            var items = ok(buyer, "").get("items");

            assertThat(items).hasSize(2);
            var back = items.get(0);
            assertThat(back.get("key").asText()).isEqualTo("T" + returned);
            assertThat(back.get("kind").asText()).isEqualTo("TOP_UP");
            assertThat(back.get("status").asText()).isEqualTo("RETURNED");
            assertThat(back.get("balanceAfter").isNull()).isTrue();
            assertThat(back.get("amount").decimalValue()).isEqualByComparingTo("250.50");
            assertThat(back.get("instrument").asText()).isEqualTo("Card •1007");
            assertThat(back.get("supplierOrderId").isNull()).isTrue();
            assertThat(back.get("at").asText()).startsWith("2026-09-02T10:00:00");
            var ledger = items.get(1);
            assertThat(ledger.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(ledger.get("balanceAfter").decimalValue()).isEqualByComparingTo("500");
            // The balance and the month totals do not know about the returned payment.
            assertThat(t.balance(buyer)).isEqualByComparingTo("500");
            assertThat(ok(buyer, "months=2026-09").get("monthTotals").get(0).get("added").decimalValue())
                    .isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("every item carries the fields the client draws from")
        void itemShape() throws Exception {
            var buyer = t.newBuyer();
            var seed = new LedgerSeed(t, jdbc, buyer);
            seed.credit("2026-09-01 10:00:00.000000", TOP_UP, "500", "Wallet top-up");
            seed.add("2026-09-02 10:00:00.000000", "DEBIT", ORDER_PAYMENT, "120.25", "Order paid", 987654L, null);
            seed.sync();

            var item = ok(buyer, "").get("items").get(0);

            assertThat(item.fieldNames()).toIterable().containsExactlyInAnyOrder("key", "id", "direction", "kind",
                    "amount", "balanceAfter", "supplierOrderId", "reason", "status", "refundStatus", "instrument", "at",
                    "bill");
            // D-116: present and null while bill tracking is off (the tests' default).
            assertThat(item.get("bill").isNull()).isTrue();
            assertThat(item.get("id").asLong()).isEqualTo(seed.lastId);
            assertThat(item.get("direction").asText()).isEqualTo("DEBIT");
            assertThat(item.get("kind").asText()).isEqualTo("ORDER_PAYMENT");
            assertThat(item.get("amount").decimalValue()).isEqualByComparingTo("120.25");
            assertThat(item.get("balanceAfter").decimalValue()).isEqualByComparingTo("379.75");
            assertThat(item.get("supplierOrderId").asLong()).isEqualTo(987654L);
            assertThat(item.get("reason").asText()).isEqualTo("Order paid");
            assertThat(item.get("refundStatus").isNull()).isTrue();
            assertThat(item.get("at").asText()).startsWith("2026-09-02T10:00:00");
        }
    }

    @Nested
    @DisplayName("where a top-up's money came from")
    class Instrument {

        @Test
        @DisplayName("a real top-up records how it was paid, and the history says so; older ones say nothing")
        void recordedAtCredit() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "500.00");
            long id = created.get("topUpId").asLong();
            var payment = provider.completeCheckout(created.get("razorpayOrderId").asText());
            assertThat(t.confirm(buyer, id, payment.providerPaymentId()).status()).isEqualTo(200);

            assertThat(jdbc.queryForObject("select payment_method from wallet_top_up where id = ?", String.class, id))
                    .isEqualTo("card");
            assertThat(jdbc.queryForObject("select payment_detail from wallet_top_up where id = ?", String.class, id))
                    .isEqualTo("1111");
            var items = ok(buyer, "").get("items");
            assertThat(items).hasSize(1);
            assertThat(items.get(0).get("kind").asText()).isEqualTo("TOP_UP");
            assertThat(items.get(0).get("instrument").asText()).isEqualTo("Card •1111");

            // Other methods, and a top-up from before the columns existed.
            jdbc.update("update wallet_top_up set payment_method = 'upi', payment_detail = null where id = ?", id);
            assertThat(ok(buyer, "").get("items").get(0).get("instrument").asText()).isEqualTo("UPI");
            jdbc.update("update wallet_top_up set payment_method = 'netbanking' where id = ?", id);
            assertThat(ok(buyer, "").get("items").get(0).get("instrument").asText()).isEqualTo("Netbanking");
            jdbc.update("update wallet_top_up set payment_method = null where id = ?", id);
            assertThat(ok(buyer, "").get("items").get(0).get("instrument").isNull()).isTrue();
            // A method we have not heard of is not guessed at.
            jdbc.update("update wallet_top_up set payment_method = 'cheque' where id = ?", id);
            assertThat(ok(buyer, "").get("items").get(0).get("instrument").isNull()).isTrue();
        }

        @Test
        @DisplayName("the response never carries anything longer than a last four")
        void neverAFullNumber() throws Exception {
            var buyer = t.newBuyer();
            var created = t.createOk(buyer, "100.00");
            var payment = provider.completeCheckout(created.get("razorpayOrderId").asText());
            t.confirm(buyer, created.get("topUpId").asLong(), payment.providerPaymentId());

            String body = history(buyer, "").body().toString();

            // No run of 13 to 19 digits: the length of a card number.
            assertThat(body).doesNotContainPattern("\\d{13,19}")
                    .contains("•1111");
        }
    }

    @Nested
    @DisplayName("who may look")
    class Tenancy {

        @Test
        @DisplayName("another outlet's customer gets a 404, no token gets a 401, and rows never cross outlets")
        void isolation() throws Exception {
            var owner = t.newBuyer();
            var stranger = t.newBuyer();
            var ownerSeed = new LedgerSeed(t, jdbc, owner);
            ownerSeed.credit("2026-09-01 10:00:00.000000", TOP_UP, "500", "owner money").sync();
            var strangerSeed = new LedgerSeed(t, jdbc, stranger);
            strangerSeed.credit("2026-09-01 10:00:00.000000", TOP_UP, "7", "stranger money").sync();
            long user = t.userId(owner.token());
            ownerSeed.returnedTopUp(user, "2026-09-02 10:00:00.000000", "100", "card", "1111");

            var reply = t.call("GET", stranger.token(),
                    "/api/v1/outlets/" + owner.outletId() + "/wallet/transactions", null, null);
            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.body().toString()).doesNotContain("owner money");
            assertThat(t.call("GET", stranger.token(), "/api/v1/outlets/" + owner.outletId()
                    + "/wallet/transactions?months=2026-09&kinds=TOP_UP", null, null).status()).isEqualTo(404);
            assertThat(t.call("GET", null, "/api/v1/outlets/" + owner.outletId() + "/wallet/transactions", null, null)
                    .status()).isEqualTo(401);

            // Each sees exactly their own.
            assertThat(ok(owner, "").get("items")).hasSize(2);
            var mine = ok(stranger, "").get("items");
            assertThat(mine).hasSize(1);
            assertThat(mine.get(0).get("reason").asText()).isEqualTo("stranger money");
            assertThat(new HashSet<>(keys(ok(stranger, "")))).doesNotContainAnyElementsOf(keys(ok(owner, "")));
            // A cursor is only a position. Carried to another outlet it reads that outlet's own rows
            // older than the position, and never the rows of the outlet it came from.
            String cursor = ok(owner, "size=1").get("nextCursor").asText();
            assertThat(keys(ok(stranger, "cursor=" + cursor)))
                    .isSubsetOf(keys(ok(stranger, ""))).doesNotContainAnyElementsOf(keys(ok(owner, "")));
            // Nor do a stranger's totals include the owner's money.
            assertThat(ok(stranger, "months=2026-09").get("monthTotals").get(0).get("added").decimalValue())
                    .isEqualByComparingTo("7");
        }
    }
}
