package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Buyer;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.CreditWalletSupport.Seller;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The supplier's receivables reads (plan B3): the home totals, the restaurant list, the ageing, and the payment
 * feeds. All server-computed, all India time from the credit clock, all by {@code CreditDueState}'s rule (never
 * the status column), so these tests pin the days and the money.
 */
@AutoConfigureMockMvc
class CreditSupplierReadsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @MockBean(name = "creditClock") private Clock creditClock;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    private CreditWalletSupport s;
    private CreditEdgeSupport edge;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        edge = new CreditEdgeSupport(mvc, json, jdbc);
        freezeAt("2026-03-10T12:00:00");
    }

    private void freezeAt(String istDateTime) {
        Instant at = ZonedDateTime.parse(istDateTime + "+05:30[Asia/Kolkata]").toInstant();
        when(creditClock.getZone()).thenReturn(IST);
        when(creditClock.instant()).thenReturn(at);
    }

    private Reply get(String token, String path) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private JsonNode ok(Reply reply) {
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static String base(Seller seller) {
        return "/api/v1/supplier-stores/" + seller.storeId() + "/credit";
    }

    private JsonNode receivables(Seller seller) throws Exception {
        return ok(get(seller.token(), base(seller) + "/receivables"));
    }

    /** Another restaurant's line on the same supplier store. */
    private Line lineOn(Seller seller, String limit) throws Exception {
        Buyer buyer = s.newBuyer();
        long agreementId = s.api.post(buyer.token(), "/api/v1/credit/requests", Map.of(
                "supplierStoreId", seller.storeId(), "outletId", buyer.outletId(),
                "requestedLimit", limit, "requestedDays", 30, "purpose", "PROCUREMENT")).at("/data/id").asLong();
        s.api.post(seller.token(), "/api/v1/credit/agreements/" + agreementId + "/approve", Map.of());
        return new Line(buyer, seller, agreementId);
    }

    /** An invoice of {@code price x 100} dated relative to the frozen today, in the given status. */
    private long invoiceDue(Line line, String price, int dueInDays, int graceDays, String status) throws Exception {
        long id = s.invoice(line, price, 100);
        LocalDate due = TODAY.plusDays(dueInDays);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ?, status = ? where id = ?",
                due, due.plusDays(graceDays), status, id);
        if (status.equals("PAID")) {
            jdbc.update("update credit_invoice set paid_amount = amount where id = ?", id);
        }
        return id;
    }

    /** Records a supplier payment that moved at the given India time; returns the payment id. */
    private long payAt(Line line, long invoice, String amount, String istDateTime) throws Exception {
        Instant at = ZonedDateTime.parse(istDateTime + "+05:30[Asia/Kolkata]").toInstant();
        var reply = edge.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", amount, "method", "BANK_TRANSFER",
                        "reference", "UTR12345678", "paidAt", at.toString()));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data().get("id").asLong();
    }

    private static void money(JsonNode node, String expected) {
        assertThat(node.isNumber()).describedAs("a JSON number: %s", node).isTrue();
        assertThat(node.decimalValue()).isEqualByComparingTo(expected);
    }

    /**
     * Two restaurants on one store. Today is 2026-03-10.
     * First:  6500 overdue (marked, 10 late), 100 overdue (unmarked, past grace), 200 in grace, 300 due today,
     *         400 due in 6 days, 500 due in 7, 600 left of 1000 due in 3; plus a paid and a written-off one.
     * Second: 1200 overdue (31 late), 1300 overdue (7 late), 1400 in grace (1 late, grace 2).
     */
    private record Fixture(Line first, Line second, long inGraceInvoice) {
    }

    private Fixture twoRestaurants() throws Exception {
        Line first = s.creditLine("200000");
        Line second = lineOn(first.seller(), "200000");
        invoiceDue(first, "65", -10, 5, "OVERDUE");
        invoiceDue(first, "1", -8, 5, "ISSUED");
        long grace = invoiceDue(first, "2", -2, 5, "ISSUED");
        invoiceDue(first, "3", 0, 5, "ISSUED");
        invoiceDue(first, "4", 6, 5, "ISSUED");
        invoiceDue(first, "5", 7, 5, "ISSUED");
        long partial = invoiceDue(first, "10", 3, 5, "PARTIALLY_PAID");
        jdbc.update("update credit_invoice set paid_amount = 400 where id = ?", partial);
        invoiceDue(first, "7", -20, 5, "PAID");
        invoiceDue(first, "8", -20, 5, "WRITTEN_OFF");
        invoiceDue(second, "12", -31, 0, "ISSUED");
        invoiceDue(second, "13", -7, 0, "ISSUED");
        invoiceDue(second, "14", -1, 2, "ISSUED");
        return new Fixture(first, second, grace);
    }

    // ── receivables ──────────────────────────────────────────────────────

    @Nested
    class Receivables {

        @Test
        @DisplayName("an empty store returns zeros and empty lists, not an error")
        void emptyStore() throws Exception {
            Seller seller = s.newSeller();
            var d = receivables(seller);
            for (String f : List.of("totalReceivable", "overdue", "inGrace", "dueToday", "dueThisWeek",
                    "collectedThisMonth")) {
                money(d.get(f), "0");
            }
            for (String f : List.of("extended", "drawn", "availableToLend")) {
                money(d.at("/exposure/" + f), "0");
            }
            for (String f : List.of("restaurants", "linesActive", "linesSuspended", "requestsPending",
                    "claimsWaiting", "overdueRestaurants")) {
                assertThat(d.at("/counts/" + f).asInt()).describedAs(f).isZero();
            }
            assertThat(d.get("pendingActions")).isEmpty();
            assertThat(ok(get(seller.token(), base(seller) + "/receivables/restaurants")).get("items")).isEmpty();
            var ageing = ok(get(seller.token(), base(seller) + "/ageing"));
            assertThat(ageing.get("buckets")).hasSize(4);
            ageing.get("buckets").forEach(b -> {
                money(b.get("amount"), "0");
                assertThat(b.get("topRestaurants")).isEmpty();
            });
            assertThat(ok(get(seller.token(), base(seller) + "/payments")).get("items")).isEmpty();
        }

        @Test
        @DisplayName("totals by due state: overdue by the date rule (not the status column), settled invoices left out")
        void totals() throws Exception {
            var f = twoRestaurants();
            var d = receivables(f.first().seller());
            assertThat(d.get("asOf").asText()).isEqualTo("2026-03-10");
            money(d.get("totalReceivable"), "12500.00");
            money(d.get("overdue"), "9100.00");
            money(d.get("inGrace"), "1600.00");
            money(d.get("dueToday"), "300.00");
            money(d.get("dueThisWeek"), "1300.00");
            assertThat(d.at("/counts/restaurants").asInt()).isEqualTo(2);
            assertThat(d.at("/counts/overdueRestaurants").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("the total equals the sum of every agreement's due, and the rows add up to it")
        void totalsEqualSumOverAgreements() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            var d = receivables(seller);

            BigDecimal due = BigDecimal.ZERO;
            for (JsonNode a : ok(get(seller.token(), base(seller) + "/agreements"))) {
                due = due.add(a.get("due").decimalValue());
            }
            assertThat(d.get("totalReceivable").decimalValue()).isEqualByComparingTo(due);

            BigDecimal owed = BigDecimal.ZERO;
            BigDecimal overdue = BigDecimal.ZERO;
            for (JsonNode row : ok(get(seller.token(), base(seller) + "/receivables/restaurants")).get("items")) {
                owed = owed.add(row.get("owed").decimalValue());
                overdue = overdue.add(row.get("overdue").decimalValue());
            }
            assertThat(owed).isEqualByComparingTo(d.get("totalReceivable").decimalValue());
            assertThat(overdue).isEqualByComparingTo(d.get("overdue").decimalValue());
        }

        @Test
        @DisplayName("exposure: extended is the limit of ACTIVE lines, drawn what they use, availableToLend what is left")
        void exposure() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            var d = receivables(seller);
            BigDecimal drawn = jdbc.queryForObject("select sum(utilized_amount) from credit_agreement "
                    + "where supplier_store_id = ?", BigDecimal.class, seller.storeId());
            money(d.at("/exposure/extended"), "400000.00");
            assertThat(d.at("/exposure/drawn").decimalValue()).isEqualByComparingTo(drawn);
            assertThat(d.at("/exposure/availableToLend").decimalValue())
                    .isEqualByComparingTo(new BigDecimal("400000").subtract(drawn));

            // A suspended line keeps its debt in the receivable but is no longer credit extended.
            jdbc.update("update credit_agreement set status = 'SUSPENDED' where id = ?", f.second().agreementId());
            var after = receivables(seller);
            money(after.get("totalReceivable"), "12500.00");
            money(after.at("/exposure/extended"), "200000.00");
            assertThat(after.at("/counts/linesActive").asInt()).isEqualTo(1);
            assertThat(after.at("/counts/linesSuspended").asInt()).isEqualTo(1);
            assertThat(after.at("/counts/restaurants").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("pending counts and actions come from the server: requests, claims waiting, overdue, line at limit")
        void pendingActions() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            // a request nobody has answered
            Buyer asker = s.newBuyer();
            s.api.post(asker.token(), "/api/v1/credit/requests", Map.of(
                    "supplierStoreId", seller.storeId(), "outletId", asker.outletId(),
                    "requestedLimit", "5000", "requestedDays", 30, "purpose", "PROCUREMENT"));
            // a claim waiting
            var claim = new HashMap<>(edge.claimBody("10"));
            claim.put("paidOn", TODAY.toString());
            long open = jdbc.queryForObject("select id from credit_invoice where credit_agreement_id = ? "
                    + "order by id limit 1", Long.class, f.first().agreementId());
            assertThat(edge.call("POST", f.first().buyer().token(), "/api/v1/credit/invoices/" + open + "/claims",
                    UUID.randomUUID().toString(), claim).status()).isEqualTo(201);
            // a line used up to its limit
            jdbc.update("update credit_agreement set utilized_amount = approved_limit where id = ?",
                    f.second().agreementId());

            var d = receivables(seller);
            assertThat(d.at("/counts/requestsPending").asInt()).isEqualTo(1);
            assertThat(d.at("/counts/claimsWaiting").asInt()).isEqualTo(1);
            Map<String, Integer> kinds = new HashMap<>();
            d.get("pendingActions").forEach(a -> kinds.put(a.get("kind").asText(), a.get("count").asInt()));
            assertThat(kinds).containsOnly(Map.entry("CLAIMS_WAITING", 1), Map.entry("REQUESTS_PENDING", 1),
                    Map.entry("OVERDUE_RESTAURANTS", 2), Map.entry("LINE_AT_LIMIT", 1));
        }

        @Test
        @DisplayName("collected this month is the IST calendar month: 23:50 on the 28th is February, 00:10 on the 1st is March")
        void collectedThisMonth() throws Exception {
            freezeAt("2026-02-27T09:00:00");
            Line line = s.creditLine("200000");
            long a = s.invoice(line, "10", 100);
            long b = s.invoice(line, "10", 100);
            Line other = s.creditLine("200000");
            long o = s.invoice(other, "10", 100);
            freezeAt("2026-03-10T12:00:00");
            payAt(line, a, "100.00", "2026-02-28T23:50:00");
            payAt(line, a, "200.00", "2026-03-01T00:10:00");
            payAt(line, b, "50.50", "2026-03-10T09:00:00");
            // another store's payment is not ours
            payAt(other, o, "999.00", "2026-03-05T09:00:00");

            money(receivables(line.seller()).get("collectedThisMonth"), "250.50");
            freezeAt("2026-02-28T23:59:00");
            money(receivables(line.seller()).get("collectedThisMonth"), "100.00");
        }

        @Test
        @DisplayName("IST midnight: due today at 23:59, in grace and past due at 00:01 (UTC is still the 10th)")
        void istMidnight() throws Exception {
            Line line = s.creditLine("200000");
            invoiceDue(line, "3", 0, 5, "ISSUED");
            freezeAt("2026-03-10T23:59:00");
            var before = receivables(line.seller());
            money(before.get("dueToday"), "300.00");
            money(before.get("inGrace"), "0");
            freezeAt("2026-03-11T00:01:00");
            var after = receivables(line.seller());
            assertThat(after.get("asOf").asText()).isEqualTo("2026-03-11");
            money(after.get("dueToday"), "0");
            money(after.get("inGrace"), "300.00");
            money(after.get("dueThisWeek"), "0");
        }

        @Test
        @DisplayName("money keeps its cents: 0.01 and 99999999.99 are exact in totals, rows and ageing")
        void precision() throws Exception {
            Line line = s.creditLine("1000000000");
            long tiny = s.invoice(line, "0.01", 1);
            long big = s.invoice(line, "99999999.99", 1);
            jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?",
                    TODAY.minusDays(3), TODAY.minusDays(3), tiny);
            jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?",
                    TODAY.plusDays(2), TODAY.plusDays(2), big);
            var d = receivables(line.seller());
            money(d.get("totalReceivable"), "100000000.00");
            money(d.get("overdue"), "0.01");
            money(d.get("dueThisWeek"), "99999999.99");
            var ageing = ok(get(line.seller().token(), base(line.seller()) + "/ageing"));
            money(ageing.at("/buckets/0/amount"), "99999999.99");
            money(ageing.at("/buckets/1/amount"), "0.01");
            var rows = ok(get(line.seller().token(), base(line.seller()) + "/receivables/restaurants")).get("items");
            money(rows.get(0).get("owed"), "100000000.00");
        }

        @Test
        @DisplayName("isolation: another store's seller, and the restaurant itself, get a 404 on every store read")
        void isolation() throws Exception {
            var f = twoRestaurants();
            Seller mine = f.first().seller();
            Seller stranger = s.newSeller();
            for (String path : List.of("/receivables", "/receivables/restaurants", "/ageing", "/payments")) {
                assertThat(get(stranger.token(), base(mine) + path).status()).describedAs(path).isEqualTo(404);
                assertThat(get(f.first().buyer().token(), base(mine) + path).status()).describedAs(path).isEqualTo(404);
            }
            // and the stranger's own numbers contain none of ours
            money(receivables(stranger).get("totalReceivable"), "0");
            assertThat(ok(get(stranger.token(), base(stranger) + "/receivables/restaurants")).get("items")).isEmpty();
        }
    }

    // ── restaurants list ─────────────────────────────────────────────────

    @Nested
    class Restaurants {

        @Test
        @DisplayName("a row per line with owed, overdue, next due, worst due state and claims waiting")
        void rows() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            var items = ok(get(seller.token(), base(seller) + "/receivables/restaurants?sort=overdue")).get("items");
            assertThat(items).hasSize(2);
            // overdue first: first line has 6600 overdue, second 2500
            var a = items.get(0);
            assertThat(a.get("agreementId").asLong()).isEqualTo(f.first().agreementId());
            assertThat(a.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(a.get("outletName").asText()).isEqualTo("Banjara Hills");
            money(a.get("owed"), "8600.00");
            money(a.get("overdue"), "6600.00");
            money(a.get("nextDueAmount"), "6500.00");
            assertThat(a.get("nextDueDate").asText()).isEqualTo(TODAY.minusDays(10).toString());
            assertThat(a.get("dueState").asText()).isEqualTo("OVERDUE");
            assertThat(a.get("claimsWaiting").asInt()).isZero();
            money(a.get("limit"), "200000.00");
            var b = items.get(1);
            money(b.get("owed"), "3900.00");
            money(b.get("overdue"), "2500.00");
            money(b.get("nextDueAmount"), "1200.00");
        }

        @Test
        @DisplayName("worst due state: a line with only an in-grace invoice is IN_GRACE; nothing open has none")
        void dueStates() throws Exception {
            Line grace = s.creditLine("200000");
            invoiceDue(grace, "2", -2, 5, "ISSUED");
            invoiceDue(grace, "4", 6, 5, "ISSUED");
            Line quiet = lineOn(grace.seller(), "200000");
            invoiceDue(quiet, "3", 40, 5, "PAID");
            var items = ok(get(grace.seller().token(), base(grace.seller()) + "/receivables/restaurants?sort=owed"))
                    .get("items");
            var byId = new HashMap<Long, JsonNode>();
            items.forEach(n -> byId.put(n.get("agreementId").asLong(), n));
            assertThat(byId.get(grace.agreementId()).get("dueState").asText()).isEqualTo("IN_GRACE");
            assertThat(byId.get(quiet.agreementId()).get("dueState").isNull()).isTrue();
            money(byId.get(quiet.agreementId()).get("owed"), "0");
        }

        @Test
        @DisplayName("filter by status and by name; a bad sort is a 400")
        void filters() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            jdbc.update("update credit_agreement set status = 'SUSPENDED' where id = ?", f.second().agreementId());
            jdbc.update("update outlet set name = 'Zebra Grill' where id = ?", f.second().buyer().outletId());
            var suspended = ok(get(seller.token(), base(seller) + "/receivables/restaurants?status=SUSPENDED"));
            assertThat(suspended.get("items")).hasSize(1);
            assertThat(suspended.get("items").get(0).get("agreementId").asLong()).isEqualTo(f.second().agreementId());
            var q = ok(get(seller.token(), base(seller) + "/receivables/restaurants?q=zebra"));
            assertThat(q.get("items")).hasSize(1);
            assertThat(q.get("total").asInt()).isEqualTo(1);
            assertThat(get(seller.token(), base(seller) + "/receivables/restaurants?sort=bogus").status())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("paging is stable: ties break by agreement id, pages never repeat or skip a row")
        void paging() throws Exception {
            Line first = s.creditLine("200000");
            Seller seller = first.seller();
            List<Line> lines = new ArrayList<>(List.of(first));
            for (int i = 0; i < 4; i++) {
                lines.add(lineOn(seller, "200000"));
            }
            String[] prices = {"1", "3", "3", "2", "1"};
            for (int i = 0; i < lines.size(); i++) {
                invoiceDue(lines.get(i), prices[i], 10 + i, 5, "ISSUED");
            }
            for (String sort : List.of("overdue", "owed", "nextDue")) {
                var all = ok(get(seller.token(), base(seller) + "/receivables/restaurants?sort=" + sort + "&size=50"));
                List<Long> expected = new ArrayList<>();
                all.get("items").forEach(n -> expected.add(n.get("agreementId").asLong()));
                assertThat(expected).describedAs(sort).hasSize(5).doesNotHaveDuplicates();
                List<Long> paged = new ArrayList<>();
                for (int page = 0; page < 3; page++) {
                    var p = ok(get(seller.token(),
                            base(seller) + "/receivables/restaurants?sort=" + sort + "&size=2&page=" + page));
                    assertThat(p.get("total").asInt()).isEqualTo(5);
                    assertThat(p.get("hasNext").asBoolean()).isEqualTo(page < 2);
                    p.get("items").forEach(n -> paged.add(n.get("agreementId").asLong()));
                }
                assertThat(paged).describedAs(sort).isEqualTo(expected);
            }
            // owed: 300 (a1, a2 by id), 200, 100 (a0, a4 by id)
            var owed = ok(get(seller.token(), base(seller) + "/receivables/restaurants?sort=owed")).get("items");
            List<Long> ids = new ArrayList<>();
            lines.forEach(l -> ids.add(l.agreementId()));
            assertThat(owed.get(0).get("agreementId").asLong()).isEqualTo(ids.get(1));
            assertThat(owed.get(1).get("agreementId").asLong()).isEqualTo(ids.get(2));
            assertThat(owed.get(2).get("agreementId").asLong()).isEqualTo(ids.get(3));
            assertThat(owed.get(3).get("agreementId").asLong()).isEqualTo(ids.get(0));
            assertThat(owed.get(4).get("agreementId").asLong()).isEqualTo(ids.get(4));
            // nextDue: earliest first
            var next = ok(get(seller.token(), base(seller) + "/receivables/restaurants?sort=nextDue")).get("items");
            assertThat(next.get(0).get("agreementId").asLong()).isEqualTo(ids.get(0));
            assertThat(next.get(4).get("agreementId").asLong()).isEqualTo(ids.get(4));
        }
    }

    // ── ageing ───────────────────────────────────────────────────────────

    @Nested
    class Ageing {

        @Test
        @DisplayName("buckets by days past the due date in IST, and they add up exactly to the receivable")
        void buckets() throws Exception {
            var f = twoRestaurants();
            var seller = f.first().seller();
            var d = ok(get(seller.token(), base(seller) + "/ageing"));
            var buckets = d.get("buckets");
            assertThat(buckets).hasSize(4);
            String[] names = {"CURRENT", "D1_7", "D8_30", "D30_PLUS"};
            String[] amounts = {"1800.00", "2900.00", "6600.00", "1200.00"};
            int[] invoicesIn = {4, 3, 2, 1};
            int[] restaurantsIn = {1, 2, 1, 1};
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < 4; i++) {
                assertThat(buckets.get(i).get("bucket").asText()).isEqualTo(names[i]);
                money(buckets.get(i).get("amount"), amounts[i]);
                assertThat(buckets.get(i).get("invoiceCount").asInt()).describedAs(names[i]).isEqualTo(invoicesIn[i]);
                assertThat(buckets.get(i).get("restaurantCount").asInt()).describedAs(names[i])
                        .isEqualTo(restaurantsIn[i]);
                sum = sum.add(buckets.get(i).get("amount").decimalValue());
            }
            assertThat(sum).isEqualByComparingTo(receivables(seller).get("totalReceivable").decimalValue());
            money(d.get("total"), "12500.00");
            // top restaurants of D1_7: second line 2700 (1300 + 1400), first line 200
            var top = buckets.get(1).get("topRestaurants");
            assertThat(top).hasSize(2);
            assertThat(top.get(0).get("agreementId").asLong()).isEqualTo(f.second().agreementId());
            money(top.get(0).get("amount"), "2700.00");
            assertThat(top.get(0).get("invoiceCount").asInt()).isEqualTo(2);
            assertThat(top.get(0).get("outletName").asText()).isEqualTo("Banjara Hills");
        }

        @Test
        @DisplayName("the day edges: 1 and 7 days late are D1_7, 8 and 30 are D8_30, 31 is D30_PLUS, due today is CURRENT")
        void edges() throws Exception {
            Line line = s.creditLine("500000");
            int[] late = {0, 1, 7, 8, 30, 31};
            for (int days : late) {
                invoiceDue(line, "1", -days, 0, "ISSUED");
            }
            var buckets = ok(get(line.seller().token(), base(line.seller()) + "/ageing")).get("buckets");
            int[] expected = {1, 2, 2, 1};
            for (int i = 0; i < 4; i++) {
                assertThat(buckets.get(i).get("invoiceCount").asInt()).describedAs("bucket %d", i).isEqualTo(expected[i]);
            }
        }

        @Test
        @DisplayName("IST midnight moves an invoice out of CURRENT exactly at 00:00 IST")
        void istMidnight() throws Exception {
            Line line = s.creditLine("200000");
            invoiceDue(line, "3", 0, 5, "ISSUED");
            freezeAt("2026-03-10T23:59:00");
            var before = ok(get(line.seller().token(), base(line.seller()) + "/ageing")).get("buckets");
            money(before.get(0).get("amount"), "300.00");
            money(before.get(1).get("amount"), "0");
            freezeAt("2026-03-11T00:01:00");
            var after = ok(get(line.seller().token(), base(line.seller()) + "/ageing")).get("buckets");
            money(after.get(0).get("amount"), "0");
            money(after.get(1).get("amount"), "300.00");
        }

        @Test
        @DisplayName("top restaurants per bucket are at most five, biggest first")
        void topFive() throws Exception {
            Line first = s.creditLine("200000");
            List<Line> lines = new ArrayList<>(List.of(first));
            for (int i = 0; i < 5; i++) {
                lines.add(lineOn(first.seller(), "200000"));
            }
            for (int i = 0; i < lines.size(); i++) {
                invoiceDue(lines.get(i), String.valueOf(i + 1), -3, 5, "ISSUED");
            }
            var bucket = ok(get(first.seller().token(), base(first.seller()) + "/ageing")).get("buckets").get(1);
            assertThat(bucket.get("restaurantCount").asInt()).isEqualTo(6);
            var top = bucket.get("topRestaurants");
            assertThat(top).hasSize(5);
            money(top.get(0).get("amount"), "600.00");
            money(top.get(4).get("amount"), "200.00");
        }
    }

    // ── payments ─────────────────────────────────────────────────────────

    @Nested
    class Payments {

        private long pay(Line line, long invoice, String amount) throws Exception {
            return payAt(line, invoice, amount, "2026-03-10T09:00:00");
        }

        @Test
        @DisplayName("the store feed lists payments newest first with restaurant, invoice number, source and reference")
        void storeFeed() throws Exception {
            freezeAt("2026-03-08T09:00:00");
            Line one = s.creditLine("200000");
            Line two = lineOn(one.seller(), "200000");
            long i1 = s.invoice(one, "10", 100);
            long i2 = s.invoice(two, "10", 100);
            Line other = s.creditLine("200000");
            long io = s.invoice(other, "10", 100);
            freezeAt("2026-03-10T12:00:00");
            long p1 = payAt(one, i1, "100.00", "2026-03-09T10:00:00");
            long p2 = payAt(two, i2, "250.25", "2026-03-10T10:00:00");
            jdbc.update("update credit_payment set source = 'WALLET' where id = ?", p2);
            payAt(other, io, "5.00", "2026-03-10T11:00:00");

            var seller = one.seller();
            var d = ok(get(seller.token(), base(seller) + "/payments"));
            var items = d.get("items");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).get("id").asLong()).isEqualTo(p2);
            money(items.get(0).get("amount"), "250.25");
            assertThat(items.get(0).get("source").asText()).isEqualTo("WALLET");
            assertThat(items.get(0).get("method").asText()).isEqualTo("BANK_TRANSFER");
            assertThat(items.get(0).get("reference").asText()).isEqualTo("UTR12345678");
            assertThat(items.get(0).get("restaurantName").asText()).isEqualTo("Paradise");
            assertThat(items.get(0).get("outletName").asText()).isEqualTo("Banjara Hills");
            assertThat(items.get(0).get("invoiceNumber").asText())
                    .isEqualTo(jdbc.queryForObject("select invoice_number from credit_invoice where id = ?",
                            String.class, i2));
            assertThat(items.get(0).get("paidOn").asText()).isEqualTo("2026-03-10");
            assertThat(items.get(1).get("id").asLong()).isEqualTo(p1);

            var wallet = ok(get(seller.token(), base(seller) + "/payments?source=WALLET")).get("items");
            assertThat(wallet).hasSize(1);
            var march10 = ok(get(seller.token(), base(seller) + "/payments?from=2026-03-10&to=2026-03-10")).get("items");
            assertThat(march10).hasSize(1);
            assertThat(march10.get(0).get("id").asLong()).isEqualTo(p2);
            var march9 = ok(get(seller.token(), base(seller) + "/payments?to=2026-03-09")).get("items");
            assertThat(march9).hasSize(1);
            assertThat(march9.get(0).get("id").asLong()).isEqualTo(p1);
            assertThat(get(seller.token(), base(seller) + "/payments?from=2026-03-11&to=2026-03-10").status())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an IST day starts at 00:00 IST: a payment at 00:10 IST on the 10th is the 10th, not the 9th")
        void feedDayIsIst() throws Exception {
            Line line = s.creditLine("200000");
            long inv = s.invoice(line, "10", 100);
            long p = payAt(line, inv, "10.00", "2026-03-10T00:10:00");
            var seller = line.seller();
            assertThat(ok(get(seller.token(), base(seller) + "/payments?from=2026-03-10&to=2026-03-10")).get("items"))
                    .hasSize(1);
            assertThat(ok(get(seller.token(), base(seller) + "/payments?to=2026-03-09")).get("items")).isEmpty();
            assertThat(p).isPositive();
        }

        @Test
        @DisplayName("paging the feed: pages cover every payment once")
        void feedPaging() throws Exception {
            Line line = s.creditLine("200000");
            long inv = s.invoice(line, "10", 100);
            List<Long> made = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                made.add(payAt(line, inv, "10.00", "2026-03-10T09:0" + i + ":00"));
            }
            List<Long> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                var p = ok(get(line.seller().token(), base(line.seller()) + "/payments?size=2&page=" + page));
                assertThat(p.get("total").asInt()).isEqualTo(5);
                p.get("items").forEach(n -> seen.add(n.get("id").asLong()));
            }
            assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(made);
            assertThat(seen.get(0)).isEqualTo(made.get(4));
        }

        @Test
        @DisplayName("one agreement's payments: the supplier and the restaurant read them, nobody else")
        void agreementPayments() throws Exception {
            Line one = s.creditLine("200000");
            Line two = lineOn(one.seller(), "200000");
            long p1 = pay(one, s.invoice(one, "10", 100), "100.00");
            pay(two, s.invoice(two, "10", 100), "7.00");
            String path = "/api/v1/credit/agreements/" + one.agreementId() + "/payments";

            for (String token : List.of(one.seller().token(), one.buyer().token())) {
                var items = ok(get(token, path)).get("items");
                assertThat(items).hasSize(1);
                assertThat(items.get(0).get("id").asLong()).isEqualTo(p1);
                money(items.get(0).get("amount"), "100.00");
            }
            assertThat(get(two.buyer().token(), path).status()).isEqualTo(404);
            assertThat(get(s.newSeller().token(), path).status()).isEqualTo(404);
        }
    }
}
