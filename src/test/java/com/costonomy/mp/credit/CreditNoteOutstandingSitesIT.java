package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every place that works out what is owed (B7, D-151): outstanding is amount - paid - credited, and each site is
 * proven separately to leave a credited amount out. The fixture is one ₹10,000 invoice, ₹4,000 of it credited, so
 * ₹6,000 is owed; a site that forgot the credit shows 10,000.
 *
 * <p>The grep audit behind it (every read of paid_amount / outstanding() / "amount - paid_amount"): the entity's
 * {@code outstanding()} (receivables, ageing, restaurant rows, dues, next-due, claims cap, claim reads, record
 * payment, receipt allocation and preview, wallet allocation, overdue event, reversal), the status rules in the
 * reversal and due-extension services, and the two admin SQL sums (exposure list and ops dashboard).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditNoteOutstandingSitesIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditNoteSupport n;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        n = new CreditNoteSupport(e, jdbc);
    }

    private record Fx(Line line, long invoice) {
    }

    /** ₹10,000 invoice, ₹4,000 credited: ₹6,000 owed. Not late. */
    private Fx fixture() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        n.noteOk(line, invoice, "4000.00");
        return new Fx(line, invoice);
    }

    /** As fixture(), but 10 days past its grace period; credited before the sweep marks it OVERDUE, so the sweep sees 6,000. */
    private Fx overdueFixture(Map<String, Object> approval) throws Exception {
        var line = s.creditLine("200000", approval);
        long invoice = s.invoice(line, "100", 100);
        s.age(invoice, 10);
        n.noteOk(line, invoice, "4000.00");
        creditJobs.sweepOverdue();
        assertThat(n.status(invoice)).isEqualTo("OVERDUE");
        return new Fx(line, invoice);
    }

    private String sellerStore(Line line) {
        return "/api/v1/supplier-stores/" + line.seller().storeId() + "/credit";
    }

    // ── what the restaurant reads ────────────────────────────────────────

    @Test
    @DisplayName("site: the invoice, the agreement's invoice list and the agreement's dues leave the credited amount out")
    void invoiceAndAgreementReads() throws Exception {
        var fx = fixture();
        var invoice = n.invoiceRead(fx.line().buyer().token(), fx.invoice()).data();
        assertThat(invoice.get("outstanding").decimalValue()).isEqualByComparingTo("6000");
        assertThat(invoice.get("creditedAmount").decimalValue()).isEqualByComparingTo("4000");
        assertThat(invoice.get("paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(invoice.get("reportableAmount").decimalValue()).isEqualByComparingTo("6000");

        var list = s.api.get(fx.line().buyer().token(), "/api/v1/credit/agreements/" + fx.line().agreementId() + "/invoices")
                .at("/data/0");
        assertThat(list.get("outstanding").decimalValue()).isEqualByComparingTo("6000");
        assertThat(list.get("creditedAmount").decimalValue()).isEqualByComparingTo("4000");

        var agreement = s.api.get(fx.line().buyer().token(), "/api/v1/credit/agreements/" + fx.line().agreementId()).at("/data");
        assertThat(agreement.get("due").decimalValue()).isEqualByComparingTo("6000");
        assertThat(agreement.get("nextDueAmount").decimalValue()).isEqualByComparingTo("6000");
        var summary = s.api.get(fx.line().buyer().token(), "/api/v1/outlets/" + fx.line().buyer().outletId() + "/credit/summary").at("/data");
        assertThat(summary.get("due").decimalValue()).isEqualByComparingTo("6000");
        assertThat(summary.get("reportableAmount").decimalValue()).isEqualByComparingTo("6000");
    }

    @Test
    @DisplayName("site: overdue on the agreement and the summary is what is owed late, not the invoice amount")
    void overdueDues() throws Exception {
        var fx = overdueFixture(Map.of());
        var agreement = s.api.get(fx.line().buyer().token(), "/api/v1/credit/agreements/" + fx.line().agreementId()).at("/data");
        assertThat(agreement.get("overdue").decimalValue()).isEqualByComparingTo("6000");
        assertThat(agreement.get("due").decimalValue()).isEqualByComparingTo("6000");
        var summary = s.api.get(fx.line().buyer().token(), "/api/v1/outlets/" + fx.line().buyer().outletId() + "/credit/summary").at("/data");
        assertThat(summary.get("overdue").decimalValue()).isEqualByComparingTo("6000");
    }

    // ── what the supplier reads ──────────────────────────────────────────

    @Test
    @DisplayName("site: the supplier's receivables, restaurant rows and ageing add up to 6,000; due this week and overdue likewise")
    void supplierReads() throws Exception {
        var fx = overdueFixture(Map.of());
        long second = s.invoice(fx.line(), "10", 100);   // 1,000, not due for 30 days
        n.noteOk(fx.line(), second, "250.00");           // 750 owed
        var receivables = s.api.get(fx.line().seller().token(), sellerStore(fx.line()) + "/receivables").at("/data");
        assertThat(receivables.get("totalReceivable").decimalValue()).isEqualByComparingTo("6750");
        assertThat(receivables.get("overdue").decimalValue()).isEqualByComparingTo("6000");

        var rows = s.api.get(fx.line().seller().token(), sellerStore(fx.line()) + "/receivables/restaurants").at("/data/items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("owed").decimalValue()).isEqualByComparingTo("6750");
        assertThat(rows.get(0).get("overdue").decimalValue()).isEqualByComparingTo("6000");

        var ageing = s.api.get(fx.line().seller().token(), sellerStore(fx.line()) + "/ageing").at("/data");
        assertThat(ageing.get("total").decimalValue()).isEqualByComparingTo("6750");
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode bucket : ageing.get("buckets")) {
            sum = sum.add(bucket.get("amount").decimalValue());
        }
        assertThat(sum).describedAs("buckets add up to the receivable").isEqualByComparingTo("6750");
    }

    @Test
    @DisplayName("site: a line due this week counts only what is still owed (dueThisWeek, nextDueAmount)")
    void dueThisWeek() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?",
                LocalDate.now().plusDays(2), LocalDate.now().plusDays(7), invoice);
        n.noteOk(line, invoice, "4000.00");
        var receivables = s.api.get(line.seller().token(), sellerStore(line) + "/receivables").at("/data");
        assertThat(receivables.get("dueThisWeek").decimalValue()).isEqualByComparingTo("6000");
        var rows = s.api.get(line.seller().token(), sellerStore(line) + "/receivables/restaurants").at("/data/items/0");
        assertThat(rows.get("nextDueAmount").decimalValue()).isEqualByComparingTo("6000");
    }

    // ── what can be paid ─────────────────────────────────────────────────

    @Test
    @DisplayName("site: recording a payment on one invoice is capped at 6,000, not 10,000")
    void recordPaymentOverpayCheck() throws Exception {
        var fx = fixture();
        var over = s.recordPayment(fx.line().seller(), fx.invoice(), "6000.01");
        assertThat(over.status()).describedAs(over.body().toString()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select paid_amount from credit_invoice where id = ?", BigDecimal.class, fx.invoice()))
                .isEqualByComparingTo("0");
        assertThat(s.recordPayment(fx.line().seller(), fx.invoice(), "6000.00").status()).isEqualTo(200);
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("0");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("site: a receipt over the line: preview and record allocate and cap at 6,000; the preview's overdue and available agree")
    void receiptAllocationAndPreview() throws Exception {
        var fx = overdueFixture(Map.of());
        String path = "/api/v1/credit/agreements/" + fx.line().agreementId() + "/payments";
        var over = e.call("POST", fx.line().seller().token(), path + "/preview", null, Map.of("amount", "6000.01"));
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6000.00");

        var preview = e.call("POST", fx.line().seller().token(), path + "/preview", null, Map.of("amount", "6000.00"));
        assertThat(preview.status()).describedAs(preview.body().toString()).isEqualTo(200);
        assertThat(preview.data().at("/allocations/0/amount").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(preview.data().at("/agreement/due").decimalValue()).isEqualByComparingTo("0");
        assertThat(preview.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");

        var body = new LinkedHashMap<String, Object>();
        body.put("amount", "6000.01");
        body.put("method", "UPI");
        body.put("reference", "UTR-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("paidOn", CreditEdgeSupport.today().toString());
        assertThat(e.call("POST", fx.line().seller().token(), path, UUID.randomUUID().toString(), body).code())
                .isEqualTo("CREDIT_OVERPAYMENT");
        body.put("amount", "6000.00");
        var ok = e.call("POST", fx.line().seller().token(), path, UUID.randomUUID().toString(), body);
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(201);
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("site: the wallet repays at most 6,000; allocation takes what is owed, the sub-rupee tail rule counts what is owed")
    void walletRepayAllocation() throws Exception {
        var fx = fixture();
        s.topUp(fx.line().buyer(), "20000.00");
        var over = s.repay(fx.line().buyer().token(), fx.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "6000.01"));
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6000");

        var ok = s.repay(fx.line().buyer().token(), fx.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "6000.00"));
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(201);
        assertThat(ok.data().at("/allocations/0/amount").decimalValue()).isEqualByComparingTo("6000");
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        assertThat(s.balance(fx.line().buyer())).isEqualByComparingTo("14000.00");
        n.assertConsistent(fx.line());

        // A remainder under ₹1 that is exactly what is left is allowed (D-130): the left-over is outstanding, not amount - paid.
        var line = s.creditLine("200000");
        long tail = s.invoice(line, "10", 1);   // 10.00
        n.noteOk(line, tail, "9.50");           // 0.50 owed
        s.topUp(line.buyer(), "100.00");
        var half = s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(), Map.of("amount", "0.50"));
        assertThat(half.status()).describedAs(half.body().toString()).isEqualTo(201);
        assertThat(n.status(tail)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("site: claims cap at what is owed after the note, on submit, on the read, and on confirm")
    void claimCaps() throws Exception {
        var fx = fixture();
        var over = e.claim(fx.line().buyer().token(), fx.invoice(), "6000.01");
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6000");
        var claim = e.claim(fx.line().buyer().token(), fx.invoice(), "6000.00");
        assertThat(claim.status()).isEqualTo(201);
        assertThat(claim.data().get("invoiceOutstanding").decimalValue()).isEqualByComparingTo("6000");
        assertThat(e.confirm(fx.line().seller().token(), claim.data().get("id").asLong(), "6000.01").status()).isEqualTo(422);
        assertThat(e.confirm(fx.line().seller().token(), claim.data().get("id").asLong(), "6000.00").status()).isEqualTo(200);
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
    }

    // ── what is re-raised, swept and extended ────────────────────────────

    @Test
    @DisplayName("site: reversing a payment puts back what the payment paid and the invoice stays PARTIALLY_PAID, not ISSUED, because 4,000 is credited")
    void reversalReRaise() throws Exception {
        var fx = fixture();
        assertThat(s.recordPayment(fx.line().seller(), fx.invoice(), "2000.00").status()).isEqualTo(200);
        assertThat(n.outstanding(fx.invoice())).isEqualByComparingTo("4000");
        long payment = e.count("select id from credit_payment where credit_invoice_id = ?", fx.invoice());

        var reversed = e.call("POST", fx.line().seller().token(), "/api/v1/credit/payments/" + payment + "/reverse",
                UUID.randomUUID().toString(), Map.of("reason", "Typed the wrong amount"));

        assertThat(reversed.status()).describedAs(reversed.body().toString()).isEqualTo(200);
        assertThat(n.outstanding(fx.invoice())).describedAs("6,000 owed again, not 10,000").isEqualByComparingTo("6000");
        assertThat(n.status(fx.invoice())).isEqualTo("PARTIALLY_PAID");
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("6000");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("site: reversing the payment of a fully credited-and-paid invoice reopens exactly the payment")
    void reversalOfAPaymentOnACreditSettledInvoice() throws Exception {
        var fx = fixture();
        assertThat(s.recordPayment(fx.line().seller(), fx.invoice(), "6000.00").status()).isEqualTo(200);
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        long payment = e.count("select id from credit_payment where credit_invoice_id = ?", fx.invoice());
        var reversed = e.call("POST", fx.line().seller().token(), "/api/v1/credit/payments/" + payment + "/reverse",
                UUID.randomUUID().toString(), Map.of("reason", "Cheque bounced"));
        assertThat(reversed.status()).describedAs(reversed.body().toString()).isEqualTo(200);
        assertThat(n.outstanding(fx.invoice())).isEqualByComparingTo("6000");
        assertThat(n.credited(fx.invoice())).isEqualByComparingTo("4000");
        assertThat(n.status(fx.invoice())).isEqualTo("PARTIALLY_PAID");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("site: the overdue sweep suspends on 6,000 overdue, not 10,000: a 7,000 tolerance holds")
    void sweepUsesWhatIsOwed() throws Exception {
        var fx = overdueFixture(Map.of("maxOverdueAmount", "7000"));
        creditJobs.sweepOverdue();
        assertThat(jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, fx.line().agreementId()))
                .isEqualTo("ACTIVE");
        // And the other way round: a 5,000 tolerance is below the 6,000 owed late, so it does suspend.
        var strict = overdueFixture(Map.of("maxOverdueAmount", "5000"));
        creditJobs.sweepOverdue();
        assertThat(jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, strict.line().agreementId()))
                .isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("site: the overdue event names what is owed late; a fully credited invoice is never marked overdue")
    void overdueEventAndSettled() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        n.noteOk(line, invoice, "4000.00");
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        var payload = json.readTree(jdbc.queryForObject("select payload from outbox_event where event_type = 'CreditOverdue' "
                + "and aggregate_id = ?", String.class, invoice));
        assertThat(payload.get("outstanding").asText()).isEqualTo("6000.0000");

        long settled = s.invoice(line, "10", 100);
        n.noteOk(line, settled, "1000.00");
        s.age(settled, 10);
        creditJobs.sweepOverdue();
        assertThat(n.status(settled)).describedAs("nothing left to be late on").isEqualTo("PAID");
        assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditOverdue' and aggregate_id = ?", settled)).isZero();
    }

    @Test
    @DisplayName("site: moving the due date of an overdue, part-credited invoice reopens it as PARTIALLY_PAID (money was taken off), not ISSUED")
    void extendDueStatus() throws Exception {
        var fx = overdueFixture(Map.of());
        var extended = e.call("POST", fx.line().seller().token(), "/api/v1/credit/invoices/" + fx.invoice() + "/extend-due",
                UUID.randomUUID().toString(), Map.of("newDueDate", CreditEdgeSupport.today().plusDays(10).toString(),
                        "reason", "Restaurant asked"));
        assertThat(extended.status()).describedAs(extended.body().toString()).isEqualTo(200);
        assertThat(n.status(fx.invoice())).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("site: the restaurant's Home attention is clear once an overdue invoice is credited away entirely")
    void attention() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        var url = "/api/v1/outlets/" + line.buyer().outletId() + "/credit/attention";
        assertThat(s.api.get(line.buyer().token(), url).at("/data/overdue").asBoolean()).isTrue();
        n.noteOk(line, invoice, "10000.00");
        assertThat(s.api.get(line.buyer().token(), url).at("/data/overdue").asBoolean()).isFalse();
    }

    // ── the database ─────────────────────────────────────────────────────

    @Test
    @DisplayName("site: the database refuses paid + credited above the amount, and a negative credited amount")
    void checkConstraint() throws Exception {
        var fx = fixture();
        assertThatThrownBy(() -> jdbc.update("update credit_invoice set credited_amount = 10000.0001 where id = ?", fx.invoice()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update credit_invoice set paid_amount = 6000.0001 where id = ?", fx.invoice()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update credit_invoice set credited_amount = -1 where id = ?", fx.invoice()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update credit_note set amount = 0 where credit_invoice_id = ?", fx.invoice()))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("update credit_invoice set paid_amount = 6000 where id = ?", fx.invoice());
    }

    // ── the admin side ───────────────────────────────────────────────────

    private String operator(String roleCode) throws Exception {
        String phone = ApiClient.freshPhone();
        String token = s.api.login(phone);
        Long userId = jdbc.queryForObject("select id from users where phone = ?", Long.class, "+91" + phone);
        jdbc.update("""
                insert into user_role (user_id, role_id, scope_type, scope_id, status, granted_at, created_at, updated_at, version)
                select ?, r.id, 'PLATFORM', null, 'ACTIVE', now(6), now(6), now(6), 0 from role r where r.code = ?
                """, userId, roleCode);
        return token;
    }

    @Test
    @DisplayName("site: the admin exposure list and the ops dashboard sum what is owed, not amount - paid")
    void adminSums() throws Exception {
        String ops = operator("OPS_SUPPORT");
        String finance = operator("OPS_FINANCE");
        BigDecimal overdueBefore = s.api.get(ops, "/api/v1/admin/dashboard?windowDays=1").at("/data/credit/totalOverdue").decimalValue();
        // Big enough to head the list (worst overdue first), whatever other tests left behind: 5,000,000 less 4,000,000 credited.
        var line = s.creditLine("20000000");
        long invoice = s.invoice(line, "50000", 100);
        s.age(invoice, 10);
        n.noteOk(line, invoice, "4000000.00");
        creditJobs.sweepOverdue();

        var rows = s.api.get(finance, "/api/v1/admin/credit/exposure?limit=100").at("/data");
        JsonNode mine = null;
        for (JsonNode row : rows) {
            if (row.get("agreementId").asLong() == line.agreementId()) {
                mine = row;
            }
        }
        assertThat(mine).describedAs("the line is listed").isNotNull();
        assertThat(mine.get("due").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(mine.get("overdue").decimalValue()).isEqualByComparingTo("1000000");

        BigDecimal overdueAfter = s.api.get(ops, "/api/v1/admin/dashboard?windowDays=1").at("/data/credit/totalOverdue").decimalValue();
        assertThat(overdueAfter.subtract(overdueBefore)).describedAs("this line adds 1,000,000 to the platform's overdue, not 5,000,000")
                .isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("site: a settled invoice that was credited in full shows nothing owed anywhere")
    void fullyCreditedShowsNothingOwed() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 100);
        n.noteOk(line, invoice, "10000.00");
        assertThat(n.status(invoice)).isEqualTo("PAID");
        var agreement = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId()).at("/data");
        assertThat(agreement.get("due").decimalValue()).isEqualByComparingTo("0");
        assertThat(agreement.get("openInvoices").asInt()).isZero();
        assertThat(agreement.get("available").decimalValue()).isEqualByComparingTo("200000");
        var receivables = s.api.get(line.seller().token(), sellerStore(line) + "/receivables").at("/data");
        assertThat(receivables.get("totalReceivable").decimalValue()).isEqualByComparingTo("0");
        var read = n.invoiceRead(line.buyer().token(), invoice).data();
        assertThat(read.get("reportableAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(read.get("settledAt").isNull()).isFalse();
    }
}
