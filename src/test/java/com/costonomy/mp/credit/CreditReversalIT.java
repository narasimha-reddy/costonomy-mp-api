package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supplier undoes a payment it recorded (B6, D-140): a reversal, never a delete. Every test asserts what moved
 * and, for a refusal, that nothing did.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditReversalIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ── fixtures and helpers ─────────────────────────────────────────────

    /** A line with three invoices of 1000, 2000 and 3000 (ids ascending a, b, c), all due soon and not late. */
    private record Three(Line line, long a, long b, long c) {
    }

    private Three three() throws Exception {
        var line = s.creditLine("200000");
        long a = s.invoice(line, "100", 10);
        long b = s.invoice(line, "100", 20);
        long c = s.invoice(line, "100", 30);
        due(a, 10);
        due(b, 5);
        due(c, 2);
        return new Three(line, a, b, c);
    }

    private void due(long invoice, int daysFromToday) {
        LocalDate d = CreditEdgeSupport.today().plusDays(daysFromToday);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?", d, d.plusDays(5), invoice);
    }

    private String receiptsPath(Line line) {
        return "/api/v1/credit/agreements/" + line.agreementId() + "/payments";
    }

    private Map<String, Object> body(String amount, String method, String reference, List<Long> ids) {
        var b = new LinkedHashMap<String, Object>();
        b.put("amount", amount);
        b.put("method", method);
        if (reference != null) {
            b.put("reference", reference);
        }
        b.put("paidOn", CreditEdgeSupport.today().toString());
        if (ids != null) {
            b.put("invoiceIds", ids);
        }
        return b;
    }

    private String utr() {
        return "UTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private Reply receipt(Line line, String amount, String method, List<Long> ids) throws Exception {
        String ref = "CASH".equals(method) ? null : utr();
        var reply = e.call("POST", line.seller().token(), receiptsPath(line), UUID.randomUUID().toString(),
                body(amount, method, ref, ids));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply;
    }

    private long receiptId(Reply reply) {
        return reply.data().get("receiptId").asLong();
    }

    private Reply reverseReceipt(String token, long receiptId, String key, String reason) throws Exception {
        return e.call("POST", token, "/api/v1/credit/receipts/" + receiptId + "/reverse", key,
                Map.of("reason", reason));
    }

    private Reply reverseReceipt(Line line, long receiptId) throws Exception {
        return reverseReceipt(line.seller().token(), receiptId, UUID.randomUUID().toString(), "Typed the wrong amount");
    }

    private Reply reversePayment(String token, long paymentId, String key, String reason) throws Exception {
        return e.call("POST", token, "/api/v1/credit/payments/" + paymentId + "/reverse", key,
                Map.of("reason", reason));
    }

    private long onlyPaymentOf(long invoiceId) {
        return e.count("select id from credit_payment where credit_invoice_id = ? order by id desc limit 1", invoiceId);
    }

    /** What a refusal must leave alone: invoices, the line's figures, payments, reversals, ledger, receipts, claims. */
    private Map<String, Object> state(Line line) {
        var m = new LinkedHashMap<String, Object>();
        m.put("invoices", jdbc.queryForList("select id, status, paid_amount, settled_at, marked_overdue_at "
                + "from credit_invoice where credit_agreement_id = ? order by id", line.agreementId()));
        m.put("agreement", jdbc.queryForMap("select status, approved_limit, reserved_amount, utilized_amount, "
                + "overdue_floor from credit_agreement where id = ?", line.agreementId()));
        m.put("payments", e.count("select count(*) from credit_payment where credit_agreement_id = ?", line.agreementId()));
        m.put("reversals", e.count("select count(*) from credit_payment_reversal where credit_agreement_id = ?",
                line.agreementId()));
        m.put("ledger", e.count("select count(*) from credit_transaction where credit_agreement_id = ?",
                line.agreementId()));
        m.put("receipts", jdbc.queryForList("select id, status from credit_repayment where credit_agreement_id = ? "
                + "order by id", line.agreementId()));
        m.put("claims", jdbc.queryForList("select id, status, decision_note from credit_payment_claim "
                + "where credit_agreement_id = ? order by id", line.agreementId()));
        return m;
    }

    private BigDecimal outstanding(long invoice) {
        return jdbc.queryForObject("select amount - paid_amount from credit_invoice where id = ?", BigDecimal.class, invoice);
    }

    private String status(long invoice) {
        return jdbc.queryForObject("select status from credit_invoice where id = ?", String.class, invoice);
    }

    private BigDecimal utilized(Line line) {
        return jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?", BigDecimal.class,
                line.agreementId());
    }

    private String agreementStatus(Line line) {
        return jdbc.queryForObject("select status from credit_agreement where id = ?", String.class, line.agreementId());
    }

    /** The recorded day moves back by whole days, so the time of day (and the IST day arithmetic) is unchanged. */
    private void recordedDaysAgo(long receiptId, int days) {
        jdbc.update("update credit_payment set created_at = date_sub(created_at, interval ? day) "
                + "where credit_repayment_id = ?", days, receiptId);
    }

    private String staff(Line line, String roleCode) throws Exception {
        long orgId = e.count("select supplier_organization_id from supplier_store where id = ?", line.seller().storeId());
        String phone = ApiClient.freshPhone();
        s.api.post(line.seller().token(), "/api/v1/suppliers/" + orgId + "/users",
                Map.of("phone", phone, "roleCode", roleCode, "storeId", line.seller().storeId()));
        return s.api.login(phone);
    }

    /** utilized == what the open invoices say is outstanding, and no invoice is over- or under-paid. */
    private void assertConsistent(Line line) {
        BigDecimal owed = jdbc.queryForObject("select coalesce(sum(amount - paid_amount), 0) from credit_invoice "
                + "where credit_agreement_id = ?", BigDecimal.class, line.agreementId());
        assertThat(utilized(line)).isEqualByComparingTo(owed);
        assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ? "
                + "and (paid_amount < 0 or paid_amount > amount)", line.agreementId())).isZero();
        // Each invoice's paid amount is the sum of its payments that are not reversed.
        assertThat(e.count("select count(*) from credit_invoice i where i.credit_agreement_id = ? and i.paid_amount <> "
                + "(select coalesce(sum(p.amount), 0) from credit_payment p where p.credit_invoice_id = i.id "
                + "and not exists (select 1 from credit_payment_reversal r where r.credit_payment_id = p.id))",
                line.agreementId())).isZero();
        assertThat(jdbc.queryForObject("select approved_limit - reserved_amount - utilized_amount "
                + "from credit_agreement where id = ?", BigDecimal.class, line.agreementId()).signum()).isGreaterThanOrEqualTo(0);
    }

    // ── what a reversal restores ─────────────────────────────────────────

    @Test
    @DisplayName("PY18: a typo reversal puts the invoices, status and line figures back exactly as before the payment")
    void typoReversalRestoresEverything() throws Exception {
        var fx = three();
        var before = state(fx.line());

        // Typed 3,500 for 350: the oldest due go first, C paid in full and B in part.
        var paid = receipt(fx.line(), "3500.00", "UPI", null);
        assertThat(status(fx.c())).isEqualTo("PAID");
        long receiptId = receiptId(paid);

        var reply = reverseReceipt(fx.line(), receiptId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("receiptId").asLong()).isEqualTo(receiptId);
        assertThat(reply.data().get("amount").decimalValue()).isEqualByComparingTo("3500.00");
        assertThat(reply.data().get("reason").asText()).isEqualTo("Typed the wrong amount");
        assertThat(reply.data().get("reversedAt").asText()).isNotBlank();
        assertThat(reply.data().at("/agreement/due").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(reply.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");
        assertThat(reply.data().at("/agreement/available").decimalValue()).isEqualByComparingTo("194000.00");

        var after = state(fx.line());
        // Same invoices, same figures, same statuses; the only differences are the new rows that explain it.
        assertThat(after.get("invoices")).isEqualTo(before.get("invoices"));
        assertThat(after.get("agreement")).isEqualTo(before.get("agreement"));
        assertThat(utilized(fx.line())).isEqualByComparingTo("6000.00");
        assertThat(status(fx.c())).isEqualTo("ISSUED");
        // Never a delete: the payments stay, and the reversal rows sit beside them.
        assertThat(after.get("payments")).isEqualTo(((Long) before.get("payments")) + 2);
        assertThat(after.get("reversals")).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select status from credit_repayment where id = ?", String.class, receiptId))
                .isEqualTo("REVERSED");
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("PY18: a receipt over three invoices is reversed as one: three reversal rows, three ledger rows, one audit row")
    void receiptWithThreeAllocationsIsReversedAsOne() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "6000.00", "BANK_TRANSFER", null);
        assertThat(paid.data().get("allocations")).hasSize(3);
        assertThat(utilized(fx.line())).isEqualByComparingTo("0");
        long receiptId = receiptId(paid);
        long ledgerBefore = e.count("select count(*) from credit_transaction where credit_agreement_id = ?",
                fx.line().agreementId());

        var reply = reverseReceipt(fx.line(), receiptId);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("allocations")).hasSize(3);
        assertThat(reply.data().at("/allocations/0/statusAfter").asText()).isEqualTo("ISSUED");
        assertThat(e.count("select count(*) from credit_payment_reversal where receipt_id = ?", receiptId)).isEqualTo(3);
        assertThat(e.count("select count(*) from credit_transaction where credit_agreement_id = ? "
                + "and transaction_type = 'PAYMENT_REVERSED'", fx.line().agreementId())).isEqualTo(3);
        assertThat(e.count("select count(*) from credit_transaction where credit_agreement_id = ?",
                fx.line().agreementId())).isEqualTo(ledgerBefore + 3);
        assertThat(e.count("select count(*) from audit_log where action = 'CREDIT_PAYMENT_REVERSED' "
                + "and entity_type = 'CREDIT_REPAYMENT' and entity_id = ?", receiptId)).isEqualTo(1);
        assertThat(utilized(fx.line())).isEqualByComparingTo("6000.00");
        assertThat(List.of(status(fx.a()), status(fx.b()), status(fx.c()))).containsOnly("ISSUED");
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("status is recomputed: a PAID invoice already past its grace period comes back OVERDUE, a part payment comes back ISSUED")
    void statusIsRecomputedByTheDateRule() throws Exception {
        var fx = three();
        due(fx.b(), -30); // overdue_after = 25 days ago
        s.age(fx.b(), 20);
        creditJobs.sweepOverdue();
        assertThat(status(fx.b())).isEqualTo("OVERDUE");

        var paidB = receipt(fx.line(), "2000.00", "UPI", List.of(fx.b()));
        assertThat(status(fx.b())).isEqualTo("PAID");
        var partA = receipt(fx.line(), "400.00", "UPI", List.of(fx.a()));
        assertThat(status(fx.a())).isEqualTo("PARTIALLY_PAID");

        var reply = reverseReceipt(fx.line(), receiptId(paidB));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(status(fx.b())).isEqualTo("OVERDUE");
        assertThat(reply.data().at("/allocations/0/statusAfter").asText()).isEqualTo("OVERDUE");
        assertThat(reply.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(jdbc.queryForObject("select settled_at from credit_invoice where id = ?", Object.class, fx.b())).isNull();

        assertThat(reverseReceipt(fx.line(), receiptId(partA)).status()).isEqualTo(200);
        assertThat(status(fx.a())).isEqualTo("ISSUED");
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("a payment recorded one invoice at a time (no receipt) is reversed through /credit/payments/{id}/reverse")
    void legacySinglePaymentReversal() throws Exception {
        var fx = three();
        assertThat(s.recordPayment(fx.line().seller(), fx.a(), "1000.00").status()).isEqualTo(200);
        long paymentId = onlyPaymentOf(fx.a());
        assertThat(status(fx.a())).isEqualTo("PAID");

        var reply = reversePayment(fx.line().seller().token(), paymentId, UUID.randomUUID().toString(), "Cheque bounced");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().get("paymentId").asLong()).isEqualTo(paymentId);
        assertThat(reply.data().get("receiptId").isNull()).isTrue();
        assertThat(status(fx.a())).isEqualTo("ISSUED");
        assertThat(outstanding(fx.a())).isEqualByComparingTo("1000.00");
        assertThat(utilized(fx.line())).isEqualByComparingTo("6000.00");
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("one payment of a receipt cannot be undone alone: 409 CREDIT_REVERSAL_NOT_ALLOWED with the receipt id")
    void aReceiptsPaymentIsReversedWithItsReceipt() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "2500.00", "UPI", null);
        var before = state(fx.line());

        var reply = reversePayment(fx.line().seller().token(), onlyPaymentOf(fx.c()), UUID.randomUUID().toString(),
                "Typed the wrong amount");

        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.code()).isEqualTo("CREDIT_REVERSAL_NOT_ALLOWED");
        assertThat(reply.body().at("/error/details/receiptId").asLong()).isEqualTo(receiptId(paid));
        assertThat(state(fx.line())).isEqualTo(before);
    }

    // ── who and what may be reversed ─────────────────────────────────────

    @Test
    @DisplayName("PY19: a wallet repayment is never reversed by the supplier, as a receipt or as a payment; nothing moves")
    void walletPaymentIsRefused() throws Exception {
        var fx = three();
        s.topUp(fx.line().buyer(), "5000");
        var repaid = s.repay(fx.line().buyer().token(), fx.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "1000.00", "invoiceIds", List.of(fx.a())));
        assertThat(repaid.status()).describedAs(repaid.body().toString()).isEqualTo(201);
        long repaymentId = repaid.data().get("repaymentId").asLong();
        long paymentId = onlyPaymentOf(fx.a());
        var before = state(fx.line());
        BigDecimal wallet = s.balance(fx.line().buyer());

        var asReceipt = reverseReceipt(fx.line().seller().token(), repaymentId, UUID.randomUUID().toString(), "Not ours");
        var asPayment = reversePayment(fx.line().seller().token(), paymentId, UUID.randomUUID().toString(), "Not ours");

        assertThat(asReceipt.status()).isEqualTo(409);
        assertThat(asReceipt.code()).isEqualTo("CREDIT_REVERSAL_NOT_ALLOWED");
        assertThat(asPayment.status()).isEqualTo(409);
        assertThat(asPayment.code()).isEqualTo("CREDIT_REVERSAL_NOT_ALLOWED");
        assertThat(state(fx.line())).isEqualTo(before);
        assertThat(s.balance(fx.line().buyer())).isEqualByComparingTo(wallet);
    }

    @Test
    @DisplayName("reversing a supplier receipt leaves the restaurant's wallet repayments exactly as they were")
    void walletRepaymentUntouched() throws Exception {
        var fx = three();
        s.topUp(fx.line().buyer(), "5000");
        var repaid = s.repay(fx.line().buyer().token(), fx.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "400.00", "invoiceIds", List.of(fx.a())));
        assertThat(repaid.status()).isEqualTo(201);
        var paid = receipt(fx.line(), "600.00", "UPI", List.of(fx.a()));

        assertThat(reverseReceipt(fx.line(), receiptId(paid)).status()).isEqualTo(200);

        assertThat(outstanding(fx.a())).isEqualByComparingTo("600.00");
        assertThat(status(fx.a())).isEqualTo("PARTIALLY_PAID");
        assertThat(jdbc.queryForObject("select status from credit_repayment where id = ?", String.class,
                repaid.data().get("repaymentId").asLong())).isEqualTo("COMPLETED");
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("PY23: reversing a confirmed claim's payment sends the claim back to REJECTED with a reason")
    void claimConfirmedReversalRejectsTheClaim() throws Exception {
        var fx = three();
        long claimId = e.claim(fx.line().buyer().token(), fx.b(), "2000.00").data().get("id").asLong();
        var confirmed = e.confirm(fx.line().seller().token(), claimId, null);
        assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
        assertThat(status(fx.b())).isEqualTo("PAID");
        long paymentId = onlyPaymentOf(fx.b());

        var reply = reversePayment(fx.line().seller().token(), paymentId, UUID.randomUUID().toString(), "Claim was false");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(status(fx.b())).isEqualTo("ISSUED");
        var claim = jdbc.queryForMap("select status, decision_note, confirmed_amount from credit_payment_claim where id = ?",
                claimId);
        assertThat(claim.get("status")).isEqualTo("REJECTED");
        assertThat(claim.get("decision_note")).isEqualTo("Payment reversed by supplier");
        assertThat(claim.get("confirmed_amount")).isNull();
        // A rejected claim counts for nothing: the restaurant can report the payment again, and the cap is the debt.
        assertThat(e.claim(fx.line().buyer().token(), fx.b(), "2000.00").status()).isEqualTo(201);
        assertThat(e.claim(fx.line().buyer().token(), fx.b(), "100.00").code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertConsistent(fx.line());
    }

    // ── the window ───────────────────────────────────────────────────────

    @Test
    @DisplayName("TZ06: day 7 after the recorded India day is the last day to undo; day 8 is refused with the day it closed")
    void sevenDayWindowEdges() throws Exception {
        var fx = three();
        var day7 = receipt(fx.line(), "1000.00", "UPI", List.of(fx.a()));
        var day8 = receipt(fx.line(), "2000.00", "UPI", List.of(fx.b()));
        recordedDaysAgo(receiptId(day7), 7);
        recordedDaysAgo(receiptId(day8), 8);
        var before = state(fx.line());

        var late = reverseReceipt(fx.line(), receiptId(day8));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("CREDIT_REVERSAL_WINDOW_CLOSED");
        assertThat(late.body().at("/error/details/closedOn").asText())
                .isEqualTo(CreditEdgeSupport.today().toString()); // recorded 8 days ago: the last day was yesterday
        assertThat(state(fx.line())).isEqualTo(before);

        assertThat(reverseReceipt(fx.line(), receiptId(day7)).status()).isEqualTo(200);
        assertThat(status(fx.a())).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("PY17: a cheque has 30 days: day 30 is allowed, day 31 is refused")
    void chequeThirtyDayWindowEdges() throws Exception {
        var fx = three();
        var day30 = receipt(fx.line(), "1000.00", "CHEQUE", List.of(fx.a()));
        var day31 = receipt(fx.line(), "2000.00", "CHEQUE", List.of(fx.b()));
        recordedDaysAgo(receiptId(day30), 30);
        recordedDaysAgo(receiptId(day31), 31);

        var late = reverseReceipt(fx.line(), receiptId(day31));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("CREDIT_REVERSAL_WINDOW_CLOSED");
        assertThat(reverseReceipt(fx.line(), receiptId(day30)).status()).isEqualTo(200);
        assertConsistent(fx.line());
    }

    // ── no headroom ──────────────────────────────────────────────────────

    @Test
    @DisplayName("PY20: when the freed credit was drawn again, the reversal is refused 422 with what is needed; nothing moves")
    void noHeadroomIsRefused() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "6000.00", "UPI", null);
        // The restaurant's available is the whole limit, then the supplier cut the limit below what the reversal needs.
        jdbc.update("update credit_agreement set approved_limit = 4500 where id = ?", fx.line().agreementId());
        var before = state(fx.line());

        var reply = reverseReceipt(fx.line(), receiptId(paid));

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("CREDIT_REVERSAL_NO_HEADROOM");
        assertThat(reply.body().at("/error/details/needed").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(reply.body().at("/error/details/available").decimalValue()).isEqualByComparingTo("4500.00");
        assertThat(reply.body().at("/error/details/shortBy").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(state(fx.line())).isEqualTo(before);

        // Raising the limit by the shortfall makes it work, with a new key.
        jdbc.update("update credit_agreement set approved_limit = 6000 where id = ?", fx.line().agreementId());
        assertThat(reverseReceipt(fx.line(), receiptId(paid)).status()).isEqualTo(200);
        assertConsistent(fx.line());
    }

    @Test
    @DisplayName("a suspended line still allows the reversal when the headroom is there")
    void suspendedLineAllowsReversal() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "1000.00", "UPI", List.of(fx.a()));
        assertThat(e.suspend(fx.line(), "Account under review").status()).isEqualTo(200);

        var reply = reverseReceipt(fx.line(), receiptId(paid));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(agreementStatus(fx.line())).isEqualTo("SUSPENDED");
        assertThat(reply.data().at("/agreement/status").asText()).isEqualTo("SUSPENDED");
        assertThat(status(fx.a())).isEqualTo("ISSUED");
    }

    // ── repeating and replaying ──────────────────────────────────────────

    @Test
    @DisplayName("PY21: a second reversal of the same receipt (another key) is 409 CREDIT_ALREADY_REVERSED and changes nothing")
    void doubleReverseIsRefused() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "2500.00", "UPI", null);
        assertThat(reverseReceipt(fx.line(), receiptId(paid)).status()).isEqualTo(200);
        var before = state(fx.line());

        var again = reverseReceipt(fx.line(), receiptId(paid));

        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("CREDIT_ALREADY_REVERSED");
        assertThat(state(fx.line())).isEqualTo(before);
        // The same goes for one payment reversed on its own.
        assertThat(s.recordPayment(fx.line().seller(), fx.c(), "3000.00").status()).isEqualTo(200);
        long paymentId = onlyPaymentOf(fx.c());
        assertThat(reversePayment(fx.line().seller().token(), paymentId, UUID.randomUUID().toString(), "Typo").status())
                .isEqualTo(200);
        assertThat(reversePayment(fx.line().seller().token(), paymentId, UUID.randomUUID().toString(), "Typo again").code())
                .isEqualTo("CREDIT_ALREADY_REVERSED");
    }

    @Test
    @DisplayName("ID08: the same key replays the first answer, one reversal; the same key with another reason is refused")
    void idempotentReplayAndKeyReuse() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "2500.00", "UPI", null);
        String key = UUID.randomUUID().toString();
        String token = fx.line().seller().token();

        var first = reverseReceipt(token, receiptId(paid), key, "Typed the wrong amount");
        var replay = reverseReceipt(token, receiptId(paid), key, "Typed the wrong amount");
        var reuse = reverseReceipt(token, receiptId(paid), key, "A different reason");

        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.data()).isEqualTo(first.data());
        assertThat(reuse.status()).isEqualTo(409);
        assertThat(reuse.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(e.count("select count(*) from credit_payment_reversal where receipt_id = ?", receiptId(paid))).isEqualTo(1);
        assertThat(utilized(fx.line())).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("the key is required, and the reason must be 3 to 500 characters")
    void validation() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "2500.00", "UPI", null);
        String token = fx.line().seller().token();
        var before = state(fx.line());

        assertThat(e.call("POST", token, "/api/v1/credit/receipts/" + receiptId(paid) + "/reverse", null,
                Map.of("reason", "Typed the wrong amount")).status()).isEqualTo(400);
        assertThat(reverseReceipt(token, receiptId(paid), UUID.randomUUID().toString(), "ab").status()).isEqualTo(400);
        assertThat(reverseReceipt(token, receiptId(paid), UUID.randomUUID().toString(), "x".repeat(501)).status())
                .isEqualTo(400);
        assertThat(e.call("POST", token, "/api/v1/credit/receipts/" + receiptId(paid) + "/reverse",
                UUID.randomUUID().toString(), Map.of()).status()).isEqualTo(400);
        assertThat(reverseReceipt(token, 987654321L, UUID.randomUUID().toString(), "Typed the wrong amount").status())
                .isEqualTo(404);
        assertThat(state(fx.line())).isEqualTo(before);
        var longest = reverseReceipt(token, receiptId(paid), UUID.randomUUID().toString(), "x".repeat(500));
        assertThat(longest.status()).describedAs(longest.body().toString()).isEqualTo(200);
    }

    // ── permissions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("permissions: owner, finance and store manager may reverse; a salesperson, another supplier and the restaurant get 404")
    void permissionsMatrix() throws Exception {
        var fx = three();
        var other = s.creditLine("1000");
        var paid = receipt(fx.line(), "3000.00", "UPI", null);
        long receiptId = receiptId(paid);
        var before = state(fx.line());

        for (var refused : Map.of("salesperson", staff(fx.line(), "SUP_SALESPERSON"),
                "other supplier", other.seller().token(),
                "restaurant", fx.line().buyer().token()).entrySet()) {
            var reply = reverseReceipt(refused.getValue(), receiptId, UUID.randomUUID().toString(), "Typed the wrong amount");
            assertThat(reply.status()).describedAs(refused.getKey()).isEqualTo(404);
        }
        assertThat(reversePayment(other.seller().token(), onlyPaymentOf(fx.c()), UUID.randomUUID().toString(), "Typed it wrong")
                .status()).isEqualTo(404);
        assertThat(state(fx.line())).isEqualTo(before);

        assertThat(reverseReceipt(staff(fx.line(), "SUP_FINANCE_STAFF"), receiptId, UUID.randomUUID().toString(),
                "Typed the wrong amount").status()).isEqualTo(200);
        var again = receipt(fx.line(), "3000.00", "UPI", null);
        assertThat(reverseReceipt(staff(fx.line(), "SUP_STORE_MANAGER"), receiptId(again), UUID.randomUUID().toString(),
                "Typed the wrong amount").status()).isEqualTo(200);
        var third = receipt(fx.line(), "3000.00", "UPI", null);
        assertThat(reverseReceipt(fx.line(), receiptId(third)).status()).isEqualTo(200);
    }

    // ── reads ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PY27: collectedThisMonth and the feeds leave reversed payments out of the sum but keep the rows, with reversedAt")
    void readsExcludeReversedFromCollected() throws Exception {
        var fx = three();
        String token = fx.line().seller().token();
        String home = "/api/v1/supplier-stores/" + fx.line().seller().storeId() + "/credit/receivables";
        var kept = receipt(fx.line(), "1000.00", "UPI", List.of(fx.a()));
        var undone = receipt(fx.line(), "2500.00", "UPI", List.of(fx.c()));
        assertThat(e.call("GET", token, home, null, null).data().get("collectedThisMonth").decimalValue())
                .isEqualByComparingTo("3500.00");

        assertThat(reverseReceipt(fx.line(), receiptId(undone)).status()).isEqualTo(200);

        assertThat(e.call("GET", token, home, null, null).data().get("collectedThisMonth").decimalValue())
                .isEqualByComparingTo("1000.00");
        for (String feed : List.of("/api/v1/credit/agreements/" + fx.line().agreementId() + "/payments",
                "/api/v1/supplier-stores/" + fx.line().seller().storeId() + "/credit/payments")) {
            var items = e.call("GET", token, feed, null, null).data().get("items");
            assertThat(items).describedAs(feed).hasSize(2);
            for (var item : items) {
                boolean isUndone = item.get("receiptId").asLong() == receiptId(undone);
                assertThat(item.get("reversible").asBoolean()).isEqualTo(!isUndone);
                assertThat(item.get("reversedAt").isNull()).isEqualTo(!isUndone);
                if (isUndone) {
                    assertThat(item.get("reversibleUntil").isNull()).isTrue();
                } else {
                    assertThat(item.get("reversibleUntil").asText())
                            .isEqualTo(CreditEdgeSupport.today().plusDays(7).toString());
                    assertThat(item.get("receiptId").asLong()).isEqualTo(receiptId(kept));
                }
            }
        }
        // The restaurant's own totals come from the invoices the reversal recomputed.
        var invoice = e.call("GET", fx.line().buyer().token(), "/api/v1/credit/agreements/" + fx.line().agreementId()
                + "/invoices", null, null);
        for (var row : invoice.data()) {
            if (row.get("id").asLong() == fx.c()) {
                assertThat(row.get("paidAmount").decimalValue()).isEqualByComparingTo("0");
                assertThat(row.get("outstanding").decimalValue()).isEqualByComparingTo("3000");
            }
        }
    }

    @Test
    @DisplayName("the feed says what can still be undone: a wallet payment never, a cheque for 30 days, an old payment not")
    void reversibleFlagsInTheFeed() throws Exception {
        var fx = three();
        String token = fx.line().seller().token();
        s.topUp(fx.line().buyer(), "5000");
        assertThat(s.repay(fx.line().buyer().token(), fx.line().agreementId(), UUID.randomUUID().toString(),
                Map.of("amount", "500.00", "invoiceIds", List.of(fx.a()))).status()).isEqualTo(201);
        var cheque = receipt(fx.line(), "2000.00", "CHEQUE", List.of(fx.b()));
        var old = receipt(fx.line(), "3000.00", "UPI", List.of(fx.c()));
        recordedDaysAgo(receiptId(old), 9);

        var items = e.call("GET", token, "/api/v1/credit/agreements/" + fx.line().agreementId() + "/payments", null, null)
                .data().get("items");

        assertThat(items).hasSize(3);
        for (var item : items) {
            switch (item.get("source").asText()) {
                case "WALLET" -> {
                    assertThat(item.get("reversible").asBoolean()).isFalse();
                    assertThat(item.get("reversibleUntil").isNull()).isTrue();
                }
                case "SUPPLIER_RECORDED" -> {
                    boolean isCheque = item.get("receiptId").asLong() == receiptId(cheque);
                    assertThat(item.get("reversible").asBoolean()).isEqualTo(isCheque);
                    assertThat(item.get("reversibleUntil").asText()).isEqualTo(isCheque
                            ? CreditEdgeSupport.today().plusDays(30).toString()
                            : CreditEdgeSupport.today().minusDays(9).plusDays(7).toString());
                }
                default -> throw new AssertionError(item.toString());
            }
        }
    }

    @Test
    @DisplayName("statement: the reversal is a line of its own and opening + movements = closing still holds")
    void statementIdentityHolds() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "3500.00", "UPI", null);
        assertThat(reverseReceipt(fx.line(), receiptId(paid)).status()).isEqualTo(200);

        var reply = e.call("GET", fx.line().seller().token(),
                "/api/v1/credit/agreements/" + fx.line().agreementId() + "/statement", null, null);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        BigDecimal sum = BigDecimal.ZERO;
        int reversals = 0;
        int repayments = 0;
        for (var line : reply.data().get("lines")) {
            sum = sum.add(line.get("amount").decimalValue());
            if ("PAYMENT_REVERSED".equals(line.get("type").asText())) {
                reversals++;
                assertThat(line.get("label").asText()).isEqualTo("Payment reversed");
                assertThat(line.get("amount").decimalValue().signum()).isPositive();
                assertThat(line.get("source").asText()).isEqualTo("SUPPLIER_RECORDED");
                assertThat(line.get("method").asText()).isEqualTo("UPI");
            }
            if ("REPAYMENT".equals(line.get("type").asText())) {
                repayments++;
                assertThat(line.get("amount").decimalValue().signum()).isNegative();
            }
        }
        assertThat(reversals).isEqualTo(2);
        assertThat(repayments).isEqualTo(2);
        assertThat(reply.data().get("openingOwed").decimalValue().add(sum))
                .isEqualByComparingTo(reply.data().get("closingOwed").decimalValue());
        assertThat(reply.data().get("closingOwed").decimalValue()).isEqualByComparingTo("6000.00");
        // The restaurant reads the same statement.
        assertThat(e.call("GET", fx.line().buyer().token(),
                "/api/v1/credit/agreements/" + fx.line().agreementId() + "/statement", null, null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("PY11: the reference of a reversed payment can be recorded again, for a receipt and for a single payment")
    void duplicateReferenceIsFreedByReversal() throws Exception {
        var fx = three();
        String token = fx.line().seller().token();
        String ref = utr();
        var first = e.call("POST", token, receiptsPath(fx.line()), UUID.randomUUID().toString(),
                body("1000.00", "UPI", ref, List.of(fx.a())));
        assertThat(first.status()).isEqualTo(201);
        var blocked = e.call("POST", token, receiptsPath(fx.line()), UUID.randomUUID().toString(),
                body("500.00", "UPI", ref, List.of(fx.b())));
        assertThat(blocked.code()).isEqualTo("CREDIT_DUPLICATE_REFERENCE");

        assertThat(reverseReceipt(fx.line(), receiptId(first)).status()).isEqualTo(200);

        var again = e.call("POST", token, receiptsPath(fx.line()), UUID.randomUUID().toString(),
                body("1000.00", "UPI", ref, List.of(fx.a())));
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(201);

        // A payment recorded alone carries its reference on the payment row: the reversal row frees it too.
        var single = e.call("POST", token, "/api/v1/credit/invoices/" + fx.b() + "/payments", UUID.randomUUID().toString(),
                Map.of("amount", "500.00", "method", "BANK_TRANSFER", "reference", "SINGLEREF9999"));
        assertThat(single.status()).isEqualTo(200);
        assertThat(e.call("POST", token, receiptsPath(fx.line()), UUID.randomUUID().toString(),
                body("500.00", "UPI", "SINGLEREF9999", List.of(fx.c()))).code()).isEqualTo("CREDIT_DUPLICATE_REFERENCE");
        assertThat(reversePayment(token, onlyPaymentOf(fx.b()), UUID.randomUUID().toString(), "Typed the wrong amount").status())
                .isEqualTo(200);
        assertThat(e.call("POST", token, receiptsPath(fx.line()), UUID.randomUUID().toString(),
                body("500.00", "UPI", "SINGLEREF9999", List.of(fx.c()))).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("NT03: the restaurant is told once through CreditPaymentReversed, and the audit row names the reason")
    void restaurantIsNotified() throws Exception {
        var fx = three();
        var paid = receipt(fx.line(), "3000.00", "UPI", null);
        long receiptId = receiptId(paid);

        assertThat(reverseReceipt(fx.line(), receiptId).status()).isEqualTo(200);

        var events = jdbc.queryForList("select payload from outbox_event where event_type = 'CreditPaymentReversed' "
                + "and json_extract(payload, '$.outletId') = ?", String.class, fx.line().buyer().outletId());
        assertThat(events).hasSize(1);
        var payload = json.readTree(events.get(0));
        assertThat(payload.get("amount").asText()).isEqualTo("3000.00");
        assertThat(payload.get("reason").asText()).isEqualTo("Typed the wrong amount");
        assertThat(payload.get("recordedOn").asText()).isEqualTo(CreditEdgeSupport.today().toString());
        assertThat(payload.get("receiptId").asLong()).isEqualTo(receiptId);
        assertThat(payload.get("supplierName").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("select reason from audit_log where action = 'CREDIT_PAYMENT_REVERSED' "
                + "and entity_type = 'CREDIT_REPAYMENT' and entity_id = ?", String.class, receiptId)).contains("Typed the wrong amount");
        // A refusal tells nobody.
        long count = e.count("select count(*) from outbox_event where event_type = 'CreditPaymentReversed'");
        assertThat(reverseReceipt(fx.line(), receiptId).status()).isEqualTo(409);
        assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditPaymentReversed'")).isEqualTo(count);
    }

    // ── the overdue sweep ────────────────────────────────────────────────

    private Line overdueLine() throws Exception {
        return s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
    }

    private void reinstate(Line line) throws Exception {
        var reply = e.call("POST", line.seller().token(),
                "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate", null, Map.of());
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
    }

    private Object column(Line line, String column) {
        return jdbc.queryForMap("select " + column + " from credit_agreement where id = ?", line.agreementId()).get(column);
    }

    @Test
    @DisplayName("a reversal that brings the overdue back suspends the line again on the next sweep, by SYSTEM")
    void reversalOverdueIsSuspendedBySweep() throws Exception {
        var line = overdueLine();
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(column(line, "status")).isEqualTo("SUSPENDED");

        var paid = receipt(line, "40000.00", "UPI", null);
        assertThat(column(line, "status")).describedAs("paying the overdue lifts the sweep's suspension").isEqualTo("ACTIVE");

        var reply = reverseReceipt(line, receiptId(paid));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(status(invoice)).isEqualTo("OVERDUE");
        assertThat(reply.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("40000.00");

        creditJobs.sweepOverdue();
        assertThat(column(line, "status")).isEqualTo("SUSPENDED");
        assertThat(column(line, "suspension_source")).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("SU05/SU06: the floor of a manual reinstate is left alone by a reversal; overdue back within it is not suspended, beyond it is")
    void reversalAndTheOverdueFloor() throws Exception {
        var line = overdueLine();
        long old = s.invoice(line, "400", 100);
        s.age(old, 10);
        creditJobs.sweepOverdue();
        reinstate(line);
        assertThat((BigDecimal) column(line, "overdue_floor")).isEqualByComparingTo("40000");

        // 10,000 of the old debt is paid, then undone: overdue is back at the floor, not above it.
        var part = receipt(line, "10000.00", "UPI", List.of(old));
        assertThat(reverseReceipt(line, receiptId(part)).status()).isEqualTo(200);
        assertThat((BigDecimal) column(line, "overdue_floor")).describedAs("a reversal never moves the floor")
                .isEqualByComparingTo("40000");
        creditJobs.sweepOverdue();
        assertThat(column(line, "status")).isEqualTo("ACTIVE");

        // 10,000 paid, 5,000 of new overdue (35,000 + ... = 35,000 within the 40,000 floor), then the payment is undone
        // and the overdue is 45,000: beyond the floor.
        var part2 = receipt(line, "10000.00", "UPI", List.of(old));
        long fresh = s.invoice(line, "50", 100);
        s.age(fresh, 10);
        creditJobs.sweepOverdue();
        assertThat(column(line, "status")).describedAs("30,000 + 5,000 is within the floor").isEqualTo("ACTIVE");
        assertThat(reverseReceipt(line, receiptId(part2)).status()).isEqualTo(200);
        assertThat((BigDecimal) column(line, "overdue_floor")).isEqualByComparingTo("40000");
        creditJobs.sweepOverdue();
        assertThat(column(line, "status")).isEqualTo("SUSPENDED");
        assertThat(column(line, "suspension_source")).isEqualTo("SYSTEM");
    }

    // ── concurrency ──────────────────────────────────────────────────────

    private List<Reply> concurrently(List<Callable<Reply>> calls) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Reply>>();
        for (var call : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return call.call();
            }));
        }
        start.countDown();
        var out = new ArrayList<Reply>();
        for (var f : futures) {
            out.add(f.get(90, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("CC03: two simultaneous reversals of one receipt: exactly one wins, the debt is put back once")
    void twoSimultaneousReversalsOneWins() throws Exception {
        for (int round = 0; round < 3; round++) {
            var fx = three();
            var paid = receipt(fx.line(), "6000.00", "UPI", null);
            long receiptId = receiptId(paid);

            var replies = concurrently(List.of(
                    () -> reverseReceipt(fx.line(), receiptId),
                    () -> reverseReceipt(fx.line(), receiptId)));

            var statuses = replies.stream().map(Reply::status).sorted().toList();
            assertThat(statuses).describedAs(replies.toString()).containsExactly(200, 409);
            assertThat(replies.stream().filter(r -> r.status() == 409).findFirst().orElseThrow().code())
                    .isEqualTo("CREDIT_ALREADY_REVERSED");
            assertThat(utilized(fx.line())).isEqualByComparingTo("6000.00");
            assertThat(e.count("select count(*) from credit_payment_reversal where receipt_id = ?", receiptId)).isEqualTo(3);
            assertConsistent(fx.line());
        }
    }

    @Test
    @DisplayName("CC03: a reversal racing a new receipt on the same invoice ends consistent, in whichever order they run")
    void reversalRacingANewReceipt() throws Exception {
        for (int round = 0; round < 3; round++) {
            var fx = three();
            var first = receipt(fx.line(), "400.00", "UPI", List.of(fx.a()));
            // 600 is left on A. The reversal puts 400 back; the new 700 fits only if the reversal went first.
            var replies = concurrently(List.of(
                    () -> reverseReceipt(fx.line(), receiptId(first)),
                    () -> e.call("POST", fx.line().seller().token(), receiptsPath(fx.line()),
                            UUID.randomUUID().toString(), body("700.00", "UPI", utr(), List.of(fx.a())))));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isEqualTo(200);
            int second = replies.get(1).status();
            assertThat(second).describedAs(replies.toString()).isIn(201, 422);
            assertThat(outstanding(fx.a())).isEqualByComparingTo(second == 201 ? "300.00" : "1000.00");
            assertConsistent(fx.line());
        }
    }

    @Test
    @DisplayName("CC03: reversing a PAID invoice's payment while a receipt is recorded over the open ones keeps every figure consistent")
    void reversalRacingAReceiptOverOtherInvoices() throws Exception {
        for (int round = 0; round < 2; round++) {
            var fx = three();
            var paid = receipt(fx.line(), "1000.00", "UPI", List.of(fx.a()));
            var replies = concurrently(List.of(
                    () -> reverseReceipt(fx.line(), receiptId(paid)),
                    () -> e.call("POST", fx.line().seller().token(), receiptsPath(fx.line()),
                            UUID.randomUUID().toString(), body("6000.00", "UPI", utr(), null))));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isEqualTo(200);
            // With no ids the receipt takes the open invoices: all three (5000 is what B and C owe) or, if it ran
            // first, B and C only (a 6000 receipt then overpays and is refused).
            assertThat(replies.get(1).status()).describedAs(replies.toString()).isIn(201, 422);
            assertConsistent(fx.line());
        }
    }
}
