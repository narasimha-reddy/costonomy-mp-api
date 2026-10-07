package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Fx;
import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditRepaymentPayoutWriter;
import com.costonomy.mp.settlement.service.SettlementService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a supplier reads about wallet repayments Mandi collected for it and pays out through settlement (S8a, B4):
 * pending versus applied, the commission as snapshotted, the invoices each one settled, a summary the server adds
 * up, paging, and who may look. The figures asserted are the stored ones; nothing here is recomputed by the app.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditPayoutReadsIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettlementService settlementService;
    @Autowired private CreditRepaymentPayoutWriter payoutWriter;

    private CreditWalletSupport s;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setUp() {
        s = new CreditWalletSupport(mvc, json, jdbc);
        // Same JVM-zone shift as CreditRepaymentPayoutIT: the settlement queries bind java.sql.Timestamp.
        start = dbWallClock(Instant.now().minus(Duration.ofSeconds(10)));
        end = dbWallClock(Instant.now().plus(Duration.ofDays(1)));
    }

    @AfterEach
    void restoreSwitch() {
        ReflectionTestUtils.setField(payoutWriter, "commissionEnabled", true);
    }

    private static Instant dbWallClock(Instant instant) {
        return instant.minusSeconds(ZoneId.systemDefault().getRules().getOffset(instant).getTotalSeconds());
    }

    private Fx fx() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "65", 100);
        long second = s.invoice(line, "40", 100);
        s.topUp(line.buyer(), "20000.00");
        long org = jdbc.queryForObject("select supplier_organization_id from supplier_store where id = ?", Long.class,
                line.seller().storeId());
        jdbc.update("""
                insert into commission_configuration (scope_type, scope_id, rate_percent, config_version, description,
                    effective_from, status, created_at, updated_at, version)
                values ('SUPPLIER', ?, 2.5, 1, 'B4 test', now(6) - interval 1 minute, 'ACTIVE', now(6), now(6), 0)
                """, org);
        return new Fx(line, first, second);
    }

    private long pay(Line line, String amount, Long... invoiceIds) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("amount", amount);
        if (invoiceIds.length > 0) {
            body.put("invoiceIds", List.of(invoiceIds));
        }
        var reply = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(), body);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return jdbc.queryForObject("select id from credit_repayment_payout where credit_repayment_id = ?", Long.class,
                reply.data().get("repaymentId").asLong());
    }

    private Reply get(String token, String path) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Reply(response.getStatus(), text.isBlank() ? json.createObjectNode() : json.readTree(text));
    }

    private JsonNode list(Line line, String query) throws Exception {
        var reply = get(line.seller().token(),
                "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit/payouts" + query);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        return reply.data();
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    private static void assertMoney(JsonNode n, String expected) {
        assertThat(dec(n)).isEqualByComparingTo(expected);
    }

    private JsonNode row(JsonNode data, long payoutId) {
        for (JsonNode item : data.get("items")) {
            if (item.get("payoutId").asLong() == payoutId) {
                return item;
            }
        }
        throw new AssertionError("payout " + payoutId + " not in " + data);
    }

    private void generate() {
        settlementService.generate(start, end);
    }

    @Test
    @DisplayName("a pending payout shows gross, the snapshotted commission, net, the outlet, and each invoice it settled")
    void pendingRowWithInvoices() throws Exception {
        var fx = fx();
        long payoutId = pay(fx.line(), "10500.00");

        var data = list(fx.line(), "");
        assertThat(data.get("items")).hasSize(1);
        var r = row(data, payoutId);
        assertThat(r.get("status").asText()).isEqualTo("PENDING");
        assertMoney(r.get("grossAmount"), "10500.00");
        assertMoney(r.get("commissionAmount"), "262.50");
        assertMoney(r.get("netAmount"), "10237.50");
        assertMoney(r.get("commissionRatePercent"), "2.5");
        assertThat(r.get("agreementId").asLong()).isEqualTo(fx.line().agreementId());
        assertThat(r.get("outletName").asText()).isEqualTo("Banjara Hills");
        assertThat(r.get("restaurantName").asText()).isEqualTo("Paradise");
        assertThat(r.get("settlementId").isNull()).isTrue();
        assertThat(r.get("settlementDate").isNull()).isTrue();
        assertThat(r.get("createdAt").asText()).isNotBlank();

        var invoices = r.get("invoices");
        assertThat(invoices).hasSize(2);
        String firstNumber = jdbc.queryForObject("select invoice_number from credit_invoice where id = ?",
                String.class, fx.first());
        String secondNumber = jdbc.queryForObject("select invoice_number from credit_invoice where id = ?",
                String.class, fx.second());
        for (JsonNode inv : invoices) {
            if (inv.get("invoiceNumber").asText().equals(firstNumber)) {
                assertMoney(inv.get("amount"), "6500.00");
                assertThat(inv.get("invoiceId").asLong()).isEqualTo(fx.first());
            } else {
                assertThat(inv.get("invoiceNumber").asText()).isEqualTo(secondNumber);
                assertMoney(inv.get("amount"), "4000.00");
            }
        }

        assertMoney(data.get("summary").get("pendingNet"), "10237.50");
        assertMoney(data.get("summary").get("appliedNetThisMonth"), "0");
    }

    @Test
    @DisplayName("once a settlement carries it the payout is APPLIED with that settlement and its date")
    void appliedRow() throws Exception {
        var fx = fx();
        long payoutId = pay(fx.line(), "6500.00", fx.first());
        generate();

        var data = list(fx.line(), "");
        var r = row(data, payoutId);
        assertThat(r.get("status").asText()).isEqualTo("APPLIED");
        var settlement = jdbc.queryForMap("select id, settlement_number, settlement_date from settlement "
                + "where supplier_store_id = ?", fx.line().seller().storeId());
        assertThat(r.get("settlementId").asLong()).isEqualTo(((Number) settlement.get("id")).longValue());
        assertThat(r.get("settlementNumber").asText()).isEqualTo(settlement.get("settlement_number"));
        assertThat(LocalDate.parse(r.get("settlementDate").asText()))
                .isEqualTo(((java.sql.Date) settlement.get("settlement_date")).toLocalDate());
        assertThat(r.get("appliedAt").asText()).isNotBlank();
        assertMoney(r.get("netAmount"), "6337.50");
        assertMoney(data.get("summary").get("pendingNet"), "0");
        assertMoney(data.get("summary").get("appliedNetThisMonth"), "6337.50");
    }

    @Test
    @DisplayName("status filter splits pending from applied; summary is the sum of the rows")
    void statusFilterAndSummary() throws Exception {
        var fx = fx();
        long applied = pay(fx.line(), "1000.00");
        generate();
        long pendingA = pay(fx.line(), "2000.00");
        long pendingB = pay(fx.line(), "3000.00");

        var all = list(fx.line(), "?status=ALL");
        assertThat(all.get("items")).hasSize(3);
        var pending = list(fx.line(), "?status=PENDING");
        assertThat(pending.get("items")).hasSize(2);
        row(pending, pendingA);
        row(pending, pendingB);
        var done = list(fx.line(), "?status=APPLIED");
        assertThat(done.get("items")).hasSize(1);
        assertThat(done.get("items").get(0).get("payoutId").asLong()).isEqualTo(applied);

        BigDecimal pendingSum = BigDecimal.ZERO;
        BigDecimal appliedSum = BigDecimal.ZERO;
        for (JsonNode item : all.get("items")) {
            assertThat(dec(item.get("netAmount"))).isEqualByComparingTo(
                    dec(item.get("grossAmount")).subtract(dec(item.get("commissionAmount"))));
            if (item.get("status").asText().equals("PENDING")) {
                pendingSum = pendingSum.add(dec(item.get("netAmount")));
            } else {
                appliedSum = appliedSum.add(dec(item.get("netAmount")));
            }
        }
        assertThat(dec(all.get("summary").get("pendingNet"))).isEqualByComparingTo(pendingSum).isEqualByComparingTo("4875.00");
        assertThat(dec(all.get("summary").get("appliedNetThisMonth"))).isEqualByComparingTo(appliedSum)
                .isEqualByComparingTo("975.00");
        // The summary is the store's position, not the filter's.
        assertThat(dec(pending.get("summary").get("appliedNetThisMonth"))).isEqualByComparingTo("975.00");
    }

    @Test
    @DisplayName("the commission is the one stored at repayment time, whatever the rate or the switch says later")
    void commissionIsSnapshot() throws Exception {
        var fx = fx();
        long payoutId = pay(fx.line(), "10000.00");

        jdbc.update("update commission_configuration set rate_percent = 10 where description = 'B4 test'");
        ReflectionTestUtils.setField(payoutWriter, "commissionEnabled", false);

        var r = row(list(fx.line(), ""), payoutId);
        assertMoney(r.get("commissionAmount"), "250.00");
        assertMoney(r.get("netAmount"), "9750.00");
        assertMoney(r.get("commissionRatePercent"), "2.5");

        // A repayment made with the switch off carries none, and says so.
        long free = pay(fx.line(), "400.00");
        var f = row(list(fx.line(), ""), free);
        assertMoney(f.get("commissionAmount"), "0");
        assertMoney(f.get("netAmount"), "400.00");
        assertThat(f.get("commissionRatePercent").isNull()).isTrue();
    }

    @Test
    @DisplayName("newest first, in pages, with the total")
    void paging() throws Exception {
        var fx = fx();
        long a = pay(fx.line(), "1000.00");
        long b = pay(fx.line(), "1100.00");
        long c = pay(fx.line(), "1200.00");

        var p0 = list(fx.line(), "?page=0&size=2");
        assertThat(p0.get("items")).hasSize(2);
        assertThat(p0.get("items").get(0).get("payoutId").asLong()).isEqualTo(c);
        assertThat(p0.get("items").get(1).get("payoutId").asLong()).isEqualTo(b);
        assertThat(p0.get("totalElements").asLong()).isEqualTo(3);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(p0.get("hasNext").asBoolean()).isTrue();
        var p1 = list(fx.line(), "?page=1&size=2");
        assertThat(p1.get("items")).hasSize(1);
        assertThat(p1.get("items").get(0).get("payoutId").asLong()).isEqualTo(a);
        assertThat(p1.get("hasNext").asBoolean()).isFalse();
        // The summary does not depend on the page.
        assertThat(dec(p1.get("summary").get("pendingNet"))).isEqualByComparingTo("3217.50");
    }

    @Test
    @DisplayName("from and to are India calendar days on the creation date; from after to is a 400")
    void dateFilter() throws Exception {
        var fx = fx();
        pay(fx.line(), "1000.00");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));

        assertThat(list(fx.line(), "?from=" + today + "&to=" + today).get("items")).hasSize(1);
        assertThat(list(fx.line(), "?from=" + today.plusDays(1)).get("items")).isEmpty();
        assertThat(list(fx.line(), "?to=" + today.minusDays(1)).get("items")).isEmpty();
        var bad = get(fx.line().seller().token(), "/api/v1/supplier-stores/" + fx.line().seller().storeId()
                + "/credit/payouts?from=" + today + "&to=" + today.minusDays(1));
        assertThat(bad.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("another store's payouts never appear, and its payout id is a 404 under this store")
    void isolation() throws Exception {
        var mine = fx();
        long myPayout = pay(mine.line(), "1000.00");
        var theirs = fx();
        long theirPayout = pay(theirs.line(), "2000.00");

        var data = list(mine.line(), "");
        assertThat(data.get("items")).hasSize(1);
        assertThat(data.get("items").get(0).get("payoutId").asLong()).isEqualTo(myPayout);
        assertMoney(data.get("summary").get("pendingNet"), "975.00");
        assertThat(data.toString()).doesNotContain("\"payoutId\":" + theirPayout);

        String base = "/api/v1/supplier-stores/" + mine.line().seller().storeId() + "/credit/payouts/";
        assertThat(get(mine.line().seller().token(), base + theirPayout).status()).isEqualTo(404);
        assertThat(get(mine.line().seller().token(), base + myPayout).status()).isEqualTo(200);

        // The other store's user cannot read this store at all.
        assertThat(get(theirs.line().seller().token(), "/api/v1/supplier-stores/"
                + mine.line().seller().storeId() + "/credit/payouts").status()).isEqualTo(404);
        assertThat(get(theirs.line().seller().token(), base + myPayout).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the restaurant side is denied with the same 404")
    void restaurantDenied() throws Exception {
        var fx = fx();
        long payoutId = pay(fx.line(), "1000.00");
        String base = "/api/v1/supplier-stores/" + fx.line().seller().storeId() + "/credit/payouts";
        assertThat(get(fx.line().buyer().token(), base).status()).isEqualTo(404);
        assertThat(get(fx.line().buyer().token(), base + "/" + payoutId).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a store with no payouts gets an empty list and zero totals, not an error")
    void emptyStore() throws Exception {
        var line = s.creditLine("200000");
        var data = list(line, "");
        assertThat(data.get("items")).isEmpty();
        assertThat(data.get("totalElements").asLong()).isZero();
        assertMoney(data.get("summary").get("pendingNet"), "0");
        assertMoney(data.get("summary").get("appliedNetThisMonth"), "0");
    }

    @Test
    @DisplayName("the detail is the same row as the list gives")
    void detail() throws Exception {
        var fx = fx();
        long payoutId = pay(fx.line(), "10500.00");
        var reply = get(fx.line().seller().token(), "/api/v1/supplier-stores/" + fx.line().seller().storeId()
                + "/credit/payouts/" + payoutId);
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.data()).isEqualTo(row(list(fx.line(), ""), payoutId));
        assertThat(get(fx.line().seller().token(), "/api/v1/supplier-stores/" + fx.line().seller().storeId()
                + "/credit/payouts/999999999").status()).isEqualTo(404);
    }
}
