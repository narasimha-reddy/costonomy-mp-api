package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The supplier records money received for a whole credit line (B5): one receipt, split over the open invoices
 * oldest due date first, with a preview that is a pure read. Every test asserts what moved and, for a refusal, that
 * nothing did.
 */
@AutoConfigureMockMvc
class CreditSupplierPaymentIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

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

    /** A line with three invoices: A 1000 due in 10 days, B 2000 due 5 days ago, C 3000 due in 2 days. Ids ascending A, B, C. */
    private record Three(Line line, long a, long b, long c) {
    }

    private Three three() throws Exception {
        var line = s.creditLine("200000");
        long a = s.invoice(line, "100", 10);
        long b = s.invoice(line, "100", 20);
        long c = s.invoice(line, "100", 30);
        due(a, 10);
        due(b, -5);
        due(c, 2);
        return new Three(line, a, b, c);
    }

    private void due(long invoice, int daysFromToday) {
        LocalDate d = CreditEdgeSupport.today().plusDays(daysFromToday);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?", d, d.plusDays(5), invoice);
    }

    private String path(Line line, String tail) {
        return "/api/v1/credit/agreements/" + line.agreementId() + "/payments" + tail;
    }

    private Map<String, Object> body(String amount, String method, String reference) {
        var b = new LinkedHashMap<String, Object>();
        b.put("amount", amount);
        b.put("method", method);
        if (reference != null) {
            b.put("reference", reference);
        }
        b.put("paidOn", CreditEdgeSupport.today().toString());
        return b;
    }

    private Map<String, Object> cash(String amount) {
        return body(amount, "CASH", null);
    }

    private Map<String, Object> upi(String amount) {
        return body(amount, "UPI", "UTR" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    private Reply record(Line line, Map<String, Object> body) throws Exception {
        return e.call("POST", line.seller().token(), path(line, ""), UUID.randomUUID().toString(), body);
    }

    private Reply record(String token, Line line, String key, Map<String, Object> body) throws Exception {
        return e.call("POST", token, path(line, ""), key, body);
    }

    private Reply preview(Line line, String amount, List<Long> ids) throws Exception {
        var b = new LinkedHashMap<String, Object>();
        b.put("amount", amount);
        if (ids != null) {
            b.put("invoiceIds", ids);
        }
        return e.call("POST", line.seller().token(), path(line, "/preview"), null, b);
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

    private long receipts(Line line) {
        return e.count("select count(*) from credit_repayment where credit_agreement_id = ?", line.agreementId());
    }

    private long payments(Line line) {
        return e.count("select count(*) from credit_payment where credit_agreement_id = ?", line.agreementId());
    }

    private List<Long> allocatedIds(Reply reply) {
        var ids = new ArrayList<Long>();
        reply.data().get("allocations").forEach(a -> ids.add(a.get("invoiceId").asLong()));
        return ids;
    }

    private List<BigDecimal> allocatedAmounts(Reply reply) {
        var out = new ArrayList<BigDecimal>();
        reply.data().get("allocations").forEach(a -> out.add(a.get("amount").decimalValue()));
        return out;
    }

    private String staff(Line line, String roleCode) throws Exception {
        long orgId = e.count("select supplier_organization_id from supplier_store where id = ?", line.seller().storeId());
        String phone = ApiClient.freshPhone();
        s.api.post(line.seller().token(), "/api/v1/suppliers/" + orgId + "/users",
                Map.of("phone", phone, "roleCode", roleCode, "storeId", line.seller().storeId()));
        return s.api.login(phone);
    }

    // ── allocation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PY08: one receipt is split oldest due date first, one credit_payment per invoice, one receipt row")
    void allocatesOldestDueFirst() throws Exception {
        var fx = three();
        var reply = record(fx.line(), upi("2500.00"));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(allocatedIds(reply)).containsExactly(fx.b(), fx.c());
        assertThat(allocatedAmounts(reply)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("2000.00"), new BigDecimal("500.00"));
        assertThat(reply.data().at("/allocations/0/statusAfter").asText()).isEqualTo("PAID");
        assertThat(reply.data().at("/allocations/1/statusAfter").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(status(fx.b())).isEqualTo("PAID");
        assertThat(outstanding(fx.c())).isEqualByComparingTo("2500.00");
        assertThat(outstanding(fx.a())).isEqualByComparingTo("1000.00");
        assertThat(receipts(fx.line())).isEqualTo(1);
        assertThat(payments(fx.line())).isEqualTo(2);
        var receipt = jdbc.queryForMap("select id, source, status, amount, method, paid_on from credit_repayment "
                + "where credit_agreement_id = ?", fx.line().agreementId());
        assertThat(receipt.get("source")).isEqualTo("SUPPLIER_RECORDED");
        assertThat(((Number) receipt.get("id")).longValue()).isEqualTo(reply.data().get("receiptId").asLong());
        assertThat((BigDecimal) receipt.get("amount")).isEqualByComparingTo("2500.00");
        assertThat(receipt.get("method")).isEqualTo("UPI");
        assertThat(jdbc.queryForList("select source from credit_payment where credit_repayment_id = ?", String.class,
                receipt.get("id"))).containsOnly("SUPPLIER_RECORDED");
        assertThat(utilized(fx.line())).isEqualByComparingTo("3500.00");
        // The agreement position after, from the server.
        assertThat(reply.data().at("/agreement/due").decimalValue()).isEqualByComparingTo("3500.00");
        assertThat(reply.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");
        assertThat(reply.data().at("/agreement/available").decimalValue()).isEqualByComparingTo("196500.00");
        assertThat(reply.data().at("/agreement/status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("PY08: invoices with the same due date are paid lowest id first")
    void tieOnDueDateGoesToLowerId() throws Exception {
        var line = s.creditLine("200000");
        long first = s.invoice(line, "100", 10);
        long second = s.invoice(line, "100", 10);
        due(first, 7);
        due(second, 7);

        var reply = record(line, upi("1500.00"));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(allocatedIds(reply)).containsExactly(first, second);
        assertThat(status(first)).isEqualTo("PAID");
        assertThat(outstanding(second)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("PY09: with chosen invoices only those are paid, in due-date order among them, however the ids are listed")
    void specificInvoicesOnly() throws Exception {
        var fx = three();
        var reply = record(fx.line(), withIds(upi("3500.00"), List.of(fx.c(), fx.a())));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        // C is due before A, so C first, even though B (not chosen, the oldest) is overdue.
        assertThat(allocatedIds(reply)).containsExactly(fx.c(), fx.a());
        assertThat(allocatedAmounts(reply)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("3000.00"), new BigDecimal("500.00"));
        assertThat(outstanding(fx.b())).describedAs("B was not chosen").isEqualByComparingTo("2000.00");
    }

    private Map<String, Object> withIds(Map<String, Object> body, List<Long> ids) {
        var b = new LinkedHashMap<>(body);
        b.put("invoiceIds", ids);
        return b;
    }

    @Test
    @DisplayName("PY09: chosen invoices - the untouched one stays untouched and more than the chosen owe is an overpayment")
    void chosenInvoicesLimitTheTotal() throws Exception {
        var fx = three();
        var reply = record(fx.line(), withIds(upi("3500.00"), List.of(fx.c(), fx.a())));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(allocatedIds(reply)).containsExactly(fx.c(), fx.a());
        assertThat(status(fx.c())).isEqualTo("PAID");
        assertThat(outstanding(fx.a())).isEqualByComparingTo("500.00");
        assertThat(outstanding(fx.b())).describedAs("B was not chosen").isEqualByComparingTo("2000.00");

        // 600 more is more than the 500 still owed on the chosen A, though B owes plenty.
        var before = snapshot(fx.line());
        var over = record(fx.line(), withIds(upi("600.00"), List.of(fx.a())));
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(snapshot(fx.line())).isEqualTo(before);
    }

    @Test
    @DisplayName("PY09: a chosen invoice that is not this agreement's or is already settled is a 404 and nothing moves")
    void foreignOrSettledInvoiceIs404() throws Exception {
        var fx = three();
        var other = s.creditLine("200000");
        long foreign = s.invoice(other, "100", 1);
        var before = snapshot(fx.line());
        assertThat(record(fx.line(), withIds(upi("10.00"), List.of(fx.a(), foreign))).status()).isEqualTo(404);
        assertThat(record(fx.line(), withIds(upi("10.00"), List.of(999999999L))).status()).isEqualTo(404);
        assertThat(snapshot(fx.line())).isEqualTo(before);
        assertThat(outstanding(foreign)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("MN09/PY10: sub-rupee amounts: 0.01 pays 0.01, and 0.03 over three invoices owing 0.01, 0.01, 0.05 is 0.01 each")
    void subRupeeAmounts() throws Exception {
        var line = s.creditLine("200000");
        long i1 = s.invoice(line, "1", 1);
        long i2 = s.invoice(line, "1", 1);
        long i3 = s.invoice(line, "1", 1);
        due(i1, 1);
        due(i2, 2);
        due(i3, 3);
        assertThat(record(line, withIds(cash("0.99"), List.of(i1))).status()).isEqualTo(201);
        assertThat(record(line, withIds(cash("0.99"), List.of(i2))).status()).isEqualTo(201);
        assertThat(record(line, withIds(cash("0.95"), List.of(i3))).status()).isEqualTo(201);
        assertThat(outstanding(i1)).isEqualByComparingTo("0.01");
        assertThat(outstanding(i3)).isEqualByComparingTo("0.05");

        var single = record(line, withIds(cash("0.01"), List.of(i1)));
        assertThat(single.status()).describedAs(single.body().toString()).isEqualTo(201);
        assertThat(status(i1)).isEqualTo("PAID");

        var spread = record(line, cash("0.03"));
        assertThat(spread.status()).describedAs(spread.body().toString()).isEqualTo(201);
        assertThat(allocatedIds(spread)).containsExactly(i2, i3);
        assertThat(allocatedAmounts(spread)).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("0.01"), new BigDecimal("0.02"));
        assertThat(status(i2)).isEqualTo("PAID");
        assertThat(outstanding(i3)).isEqualByComparingTo("0.03");
        assertThat(utilized(line)).isEqualByComparingTo("0.03");
    }

    @Test
    @DisplayName("PY01/PY14: paying the exact total closes every invoice PAID and lifts a SYSTEM suspension; a supplier's own stays")
    void fullPaymentClosesAndReinstatesSystemSuspension() throws Exception {
        var fx = three();
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", fx.b());
        jdbc.update("update credit_agreement set status = 'SUSPENDED', suspension_source = 'SYSTEM', "
                + "suspended_at = now(6), suspension_reason = 'Overdue' where id = ?", fx.line().agreementId());

        var reply = record(fx.line(), upi("6000.00"));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        for (long invoice : List.of(fx.a(), fx.b(), fx.c())) {
            assertThat(status(invoice)).isEqualTo("PAID");
        }
        assertThat(utilized(fx.line())).isEqualByComparingTo("0");
        assertThat(reply.data().at("/agreement/status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from credit_agreement where id = ?", String.class,
                fx.line().agreementId())).isEqualTo("ACTIVE");

        // PY13/PY15: a supplier-suspended line takes the payment and stays suspended.
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        jdbc.update("update credit_agreement set status = 'SUSPENDED', suspension_source = 'SUPPLIER', "
                + "suspended_at = now(6), suspension_reason = 'Review' where id = ?", line.agreementId());
        var paid = record(line, upi("1000.00"));
        assertThat(paid.status()).describedAs(paid.body().toString()).isEqualTo(201);
        assertThat(status(invoice)).isEqualTo("PAID");
        assertThat(paid.data().at("/agreement/status").asText()).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("PY02: more than is owed is CREDIT_OVERPAYMENT with the outstanding total, and nothing moves")
    void overpaymentIsRefused() throws Exception {
        var fx = three();
        var before = snapshot(fx.line());

        var reply = record(fx.line(), upi("6000.01"));

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(reply.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(snapshot(fx.line())).isEqualTo(before);
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("MN01: zero, negative, three-decimal and missing amounts are 400 and nothing moves, on record and preview")
    void badAmounts() throws Exception {
        var fx = three();
        var before = snapshot(fx.line());
        for (String bad : new String[]{"0", "0.00", "-5.00", "1.005", "0.001"}) {
            var reply = record(fx.line(), upi(bad));
            assertThat(reply.status()).describedAs(bad + " " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).describedAs(bad).isEqualTo("VALIDATION_ERROR");
            assertThat(preview(fx.line(), bad, null).status()).describedAs("preview " + bad).isEqualTo(400);
        }
        var noAmount = new HashMap<>(upi("1.00"));
        noAmount.remove("amount");
        assertThat(record(fx.line(), noAmount).status()).isEqualTo(400);
        assertThat(snapshot(fx.line())).isEqualTo(before);
    }

    @Test
    @DisplayName("PY07: ADJUSTMENT, WALLET, an unknown or a missing method is 400; nothing moves")
    void methodMustBeOneOfFive() throws Exception {
        var fx = three();
        var before = snapshot(fx.line());
        for (String bad : new String[]{"ADJUSTMENT", "WALLET", "BITCOIN", "cash"}) {
            var reply = record(fx.line(), body("100.00", bad, "REF123456"));
            assertThat(reply.status()).describedAs(bad + " " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        }
        var none = new HashMap<>(cash("100.00"));
        none.remove("method");
        assertThat(record(fx.line(), none).status()).isEqualTo(400);
        assertThat(snapshot(fx.line())).isEqualTo(before);
        for (String ok : new String[]{"CASH", "UPI", "BANK_TRANSFER", "CHEQUE", "CARD"}) {
            assertThat(record(fx.line(), body("1.00", ok, "REF" + UUID.randomUUID().toString().substring(0, 10)))
                    .status()).describedAs(ok).isEqualTo(201);
        }
    }

    @Test
    @DisplayName("reference: required for UPI, bank transfer and cheque (4 to 64 characters, trimmed); optional for cash and card")
    void referenceRules() throws Exception {
        var fx = three();
        for (String method : new String[]{"UPI", "BANK_TRANSFER", "CHEQUE"}) {
            for (String bad : new String[]{null, "", "   ", "abc", "  ab  ", "x".repeat(65)}) {
                var reply = record(fx.line(), body("10.00", method, bad));
                assertThat(reply.status()).describedAs(method + " [" + bad + "] " + reply.body()).isEqualTo(400);
            }
        }
        assertThat(payments(fx.line())).isZero();
        assertThat(record(fx.line(), body("10.00", "CASH", null)).status()).isEqualTo(201);
        assertThat(record(fx.line(), body("10.00", "CARD", "")).status()).isEqualTo(201);
        // Optional is not unlimited: a supplied reference is still held to the length rules.
        assertThat(record(fx.line(), body("10.00", "CASH", "x".repeat(65))).status()).isEqualTo(400);
        // 64 is allowed, and it is stored trimmed.
        assertThat(record(fx.line(), body("10.00", "UPI", "y".repeat(64))).status()).isEqualTo(201);
        var reply = record(fx.line(), body("10.00", "UPI", "   UTR998877   "));
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select reference from credit_repayment where id = ?", String.class,
                reply.data().get("receiptId").asLong())).isEqualTo("UTR998877");
    }

    @Test
    @DisplayName("PY06: a payment date in the future is 400, today and past days are fine")
    void futureDateRefused() throws Exception {
        var fx = three();
        var future = upi("10.00");
        future.put("paidOn", CreditEdgeSupport.today().plusDays(1).toString());
        var before = snapshot(fx.line());
        var reply = record(fx.line(), future);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(snapshot(fx.line())).isEqualTo(before);

        var missing = new HashMap<>(upi("10.00"));
        missing.remove("paidOn");
        assertThat(record(fx.line(), missing).status()).describedAs("paidOn is required").isEqualTo(400);

        // Invoices issued 10 days ago: a date 3 days ago is allowed.
        jdbc.update("update credit_invoice set issued_at = date_sub(now(6), interval 10 day) "
                + "where credit_agreement_id = ?", fx.line().agreementId());
        var past = upi("10.00");
        past.put("paidOn", CreditEdgeSupport.today().minusDays(3).toString());
        assertThat(record(fx.line(), past).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("PY06: a payment date before the oldest targeted invoice was issued is 400")
    void dateBeforeIssueRefused() throws Exception {
        var fx = three();
        // A was issued 10 days ago; B and C today.
        jdbc.update("update credit_invoice set issued_at = date_sub(now(6), interval 10 day) where id = ?", fx.a());
        var tooEarly = upi("10.00");
        tooEarly.put("paidOn", CreditEdgeSupport.today().minusDays(11).toString());
        var before = snapshot(fx.line());
        var reply = record(fx.line(), tooEarly);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(400);
        assertThat(snapshot(fx.line())).isEqualTo(before);

        // The oldest targeted: choose only B (issued today), and yesterday is then before its issue.
        var yesterday = upi("10.00");
        yesterday.put("paidOn", CreditEdgeSupport.today().minusDays(1).toString());
        assertThat(record(fx.line(), withIds(yesterday, List.of(fx.b()))).status()).isEqualTo(400);
        // Choosing A as well makes A the oldest targeted, and yesterday is after its issue.
        assertThat(record(fx.line(), withIds(yesterday, List.of(fx.a(), fx.b()))).status()).isEqualTo(201);
    }

    // ── idempotency ──────────────────────────────────────────────────────

    @Test
    @DisplayName("ID01/PY03: the same key and body twice is one receipt; the replay returns the original response")
    void sameKeyReplays() throws Exception {
        var fx = three();
        String key = UUID.randomUUID().toString();
        var request = upi("2500.00");
        var first = record(fx.line().seller().token(), fx.line(), key, request);
        var utilizedAfterFirst = utilized(fx.line());
        var replay = record(fx.line().seller().token(), fx.line(), key, new LinkedHashMap<>(request));

        assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(201);
        assertThat(replay.status()).describedAs(replay.body().toString()).isEqualTo(201);
        assertThat(replay.data().get("receiptId").asLong()).isEqualTo(first.data().get("receiptId").asLong());
        assertThat(allocatedIds(replay)).isEqualTo(allocatedIds(first));
        assertThat(receipts(fx.line())).isEqualTo(1);
        assertThat(payments(fx.line())).isEqualTo(2);
        assertThat(utilized(fx.line())).isEqualByComparingTo(utilizedAfterFirst);
        assertThat(outstanding(fx.c())).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("MN02: 2500, 2500.0 and \"2500.00\" with one key are one request; the key is not a squat risk for another supplier")
    void canonicalAmountAndKeyNamespace() throws Exception {
        var fx = three();
        String key = UUID.randomUUID().toString();
        var request = new LinkedHashMap<>(upi("2500.00"));
        var first = record(fx.line().seller().token(), fx.line(), key, request);
        request.put("amount", "2500");
        var second = record(fx.line().seller().token(), fx.line(), key, request);
        request.put("amount", 2500.0);
        var third = record(fx.line().seller().token(), fx.line(), key, request);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.data().get("receiptId").asLong()).isEqualTo(first.data().get("receiptId").asLong());
        assertThat(third.data().get("receiptId").asLong()).isEqualTo(first.data().get("receiptId").asLong());
        assertThat(receipts(fx.line())).isEqualTo(1);

        // Another supplier using the same key text records its own receipt: the key is the actor's own namespace.
        var other = s.creditLine("200000");
        s.invoice(other, "100", 5);
        var theirs = record(other.seller().token(), other, key, upi("100.00"));
        assertThat(theirs.status()).describedAs(theirs.body().toString()).isEqualTo(201);
        assertThat(theirs.data().get("receiptId").asLong()).isNotEqualTo(first.data().get("receiptId").asLong());
        // A system-style key cannot squat or collide either.
        var squat = record(other.seller().token(), other, "claim:1", upi("100.00"));
        assertThat(squat.status()).describedAs(squat.body().toString()).isEqualTo(201);
    }

    @Test
    @DisplayName("PY04: the same key with a different body is 409 IDEMPOTENCY_KEY_REUSE and posts nothing more")
    void sameKeyDifferentBodyIsRefused() throws Exception {
        var fx = three();
        String key = UUID.randomUUID().toString();
        String token = fx.line().seller().token();
        var request = upi("1000.00");
        assertThat(record(token, fx.line(), key, request).status()).isEqualTo(201);
        var before = snapshot(fx.line());

        var different = new LinkedHashMap<>(request);
        different.put("amount", "1000.01");
        var amount = record(token, fx.line(), key, different);
        var method = new LinkedHashMap<>(request);
        method.put("method", "BANK_TRANSFER");
        var changed = record(token, fx.line(), key, method);
        var chosen = record(token, fx.line(), key, withIds(request, List.of(fx.a())));

        for (var reply : List.of(amount, changed, chosen)) {
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        }
        assertThat(snapshot(fx.line())).isEqualTo(before);
    }

    // ── duplicate reference ──────────────────────────────────────────────

    @Test
    @DisplayName("PY11: the same reference in this store within 90 days is 409 with the earlier receipt; allowDuplicateReference passes it")
    void duplicateReference() throws Exception {
        var fx = three();
        var first = record(fx.line(), body("100.00", "UPI", "UTR4455667788"));
        assertThat(first.status()).isEqualTo(201);
        var before = snapshot(fx.line());

        // Case-insensitive, and any method.
        var again = record(fx.line(), body("200.00", "BANK_TRANSFER", "utr4455667788"));
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("CREDIT_DUPLICATE_REFERENCE");
        assertThat(again.body().at("/error/details/receiptId").asLong()).isEqualTo(first.data().get("receiptId").asLong());
        assertThat(again.body().at("/error/details/paidOn").asText()).isEqualTo(CreditEdgeSupport.today().toString());
        assertThat(again.body().at("/error/details/amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(snapshot(fx.line())).isEqualTo(before);

        var allowed = body("200.00", "BANK_TRANSFER", "utr4455667788");
        allowed.put("allowDuplicateReference", true);
        var forced = record(fx.line(), allowed);
        assertThat(forced.status()).describedAs(forced.body().toString()).isEqualTo(201);
        assertThat(receipts(fx.line())).isEqualTo(2);
    }

    @Test
    @DisplayName("PY11: a duplicate refusal leaves the key free, so Record anyway can reuse it with the flag")
    void duplicateRefusalDoesNotBurnTheKey() throws Exception {
        var fx = three();
        assertThat(record(fx.line(), body("100.00", "UPI", "UTR1212121212")).status()).isEqualTo(201);
        String key = UUID.randomUUID().toString();
        String token = fx.line().seller().token();
        var request = body("100.00", "UPI", "UTR1212121212");
        assertThat(record(token, fx.line(), key, request).status()).isEqualTo(409);
        request.put("allowDuplicateReference", true);
        var retry = record(token, fx.line(), key, request);
        assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(201);
        // And once recorded, replaying it is still a replay and not a duplicate refusal.
        var replay = record(token, fx.line(), key, request);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.data().get("receiptId").asLong()).isEqualTo(retry.data().get("receiptId").asLong());
    }

    @Test
    @DisplayName("PY11: the check is per store and 90 days; a cash payment without a reference never matches; a legacy payment's reference counts")
    void duplicateReferenceScope() throws Exception {
        var fx = three();
        var other = s.creditLine("200000");
        s.invoice(other, "100", 5);
        assertThat(record(fx.line(), body("100.00", "UPI", "UTR7000000001")).status()).isEqualTo(201);
        // Another store may use the same text.
        assertThat(record(other, body("100.00", "UPI", "UTR7000000001")).status()).isEqualTo(201);
        // Two cash receipts with no reference are fine.
        assertThat(record(fx.line(), cash("10.00")).status()).isEqualTo(201);
        assertThat(record(fx.line(), cash("10.00")).status()).isEqualTo(201);
        // Older than 90 days no longer counts.
        jdbc.update("update credit_payment set paid_at = date_sub(now(6), interval 91 day) where credit_agreement_id = ? "
                + "and reference = 'UTR7000000001'", fx.line().agreementId());
        jdbc.update("update credit_invoice set issued_at = date_sub(now(6), interval 120 day) where credit_agreement_id = ?",
                fx.line().agreementId());
        assertThat(record(fx.line(), body("100.00", "UPI", "UTR7000000001")).status()).isEqualTo(201);

        // A payment recorded through the single-invoice endpoint counts too.
        var old = e.call("POST", fx.line().seller().token(), "/api/v1/credit/invoices/" + fx.c() + "/payments",
                UUID.randomUUID().toString(), Map.of("amount", "10.00", "method", "UPI", "reference", "UTR7000000002"));
        assertThat(old.status()).describedAs(old.body().toString()).isEqualTo(200);
        var dup = record(fx.line(), body("100.00", "UPI", "UTR7000000002"));
        assertThat(dup.status()).describedAs(dup.body().toString()).isEqualTo(409);
        assertThat(dup.code()).isEqualTo("CREDIT_DUPLICATE_REFERENCE");
        assertThat(dup.body().at("/error/details/amount").decimalValue()).isEqualByComparingTo("10.00");
    }

    // ── preview ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("preview: a pure read that equals what recording then does, and writes nothing")
    void previewEqualsActual() throws Exception {
        var fx = three();
        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", fx.b());
        var before = snapshot(fx.line());
        long idempotencyBefore = e.count("select count(*) from idempotency_record");

        var preview = preview(fx.line(), "2500.00", null);

        assertThat(preview.status()).describedAs(preview.body().toString()).isEqualTo(200);
        assertThat(snapshot(fx.line())).describedAs("a preview writes nothing").isEqualTo(before);
        assertThat(e.count("select count(*) from idempotency_record")).isEqualTo(idempotencyBefore);
        assertThat(preview.data().at("/amount").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(preview.data().at("/pendingClaims").size()).isZero();

        var actual = record(fx.line(), upi("2500.00"));
        assertThat(actual.status()).describedAs(actual.body().toString()).isEqualTo(201);
        assertThat(preview.data().get("allocations")).isEqualTo(actual.data().get("allocations"));
        for (String field : List.of("due", "overdue", "available")) {
            assertThat(preview.data().at("/agreement/" + field).decimalValue())
                    .describedAs(field).isEqualByComparingTo(actual.data().at("/agreement/" + field).decimalValue());
        }
        assertThat(preview.data().at("/agreement/status").asText()).isEqualTo(actual.data().at("/agreement/status").asText());
        assertThat(preview.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");
        assertThat(preview.data().at("/allocations/0/statusAfter").asText()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("preview: chosen invoices in due order, an overpayment is CREDIT_OVERPAYMENT with outstanding, a SYSTEM suspension shows ACTIVE after")
    void previewVariants() throws Exception {
        var fx = three();
        var chosen = preview(fx.line(), "3500.00", List.of(fx.a(), fx.c()));
        assertThat(allocatedIds(chosen)).containsExactly(fx.c(), fx.a());
        assertThat(chosen.data().at("/allocations/0/statusAfter").asText()).isEqualTo("PAID");
        assertThat(chosen.data().at("/allocations/1/statusAfter").asText()).isEqualTo("PARTIALLY_PAID");

        var over = preview(fx.line(), "4001.00", List.of(fx.a(), fx.c()));
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("4000.00");

        jdbc.update("update credit_invoice set status = 'OVERDUE' where id = ?", fx.b());
        jdbc.update("update credit_agreement set status = 'SUSPENDED', suspension_source = 'SYSTEM', "
                + "max_overdue_amount = 500, suspended_at = now(6), suspension_reason = 'Overdue' where id = ?",
                fx.line().agreementId());
        var lifts = preview(fx.line(), "2000.00", null);
        assertThat(lifts.data().at("/agreement/status").asText()).isEqualTo("ACTIVE");
        assertThat(lifts.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("0");
        var stays = preview(fx.line(), "1000.00", null);
        assertThat(stays.data().at("/agreement/status").asText()).isEqualTo("SUSPENDED");
        assertThat(stays.data().at("/agreement/overdue").decimalValue()).isEqualByComparingTo("1000.00");
    }

    // ── concurrency ──────────────────────────────────────────────────────

    private List<Reply> concurrently(List<java.util.concurrent.Callable<Reply>> calls) throws Exception {
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
            out.add(f.get(60, TimeUnit.SECONDS));
        }
        return out;
    }

    @Test
    @DisplayName("CC01: two receipts at once for 60% each of one invoice: one is recorded, the other is an overpayment; never below zero")
    void concurrentReceiptsCannotOverpay() throws Exception {
        for (int round = 0; round < 2; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "100", 10);
            var replies = concurrently(List.of(
                    () -> record(line, withIds(upi("600.00"), List.of(invoice))),
                    () -> record(line, withIds(upi("600.00"), List.of(invoice)))));
            var statuses = replies.stream().map(Reply::status).sorted().toList();
            assertThat(statuses).describedAs(replies.toString()).containsExactly(201, 422);
            var refused = replies.stream().filter(r -> r.status() == 422).findFirst().orElseThrow();
            assertThat(refused.code()).isEqualTo("CREDIT_OVERPAYMENT");
            assertThat(outstanding(invoice)).isEqualByComparingTo("400.00");
            assertThat(utilized(line)).isEqualByComparingTo("400.00");
            assertThat(receipts(line)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CC01: two receipts at once that together fit are both recorded and close the invoice exactly")
    void concurrentReceiptsThatFitSplitCorrectly() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        var replies = concurrently(List.of(
                () -> record(line, withIds(upi("500.00"), List.of(invoice))),
                () -> record(line, withIds(upi("500.00"), List.of(invoice)))));
        assertThat(replies.stream().map(Reply::status)).describedAs(replies.toString()).containsOnly(201);
        assertThat(status(invoice)).isEqualTo("PAID");
        assertThat(outstanding(invoice)).isEqualByComparingTo("0");
        assertThat(utilized(line)).isEqualByComparingTo("0");
        assertThat(receipts(line)).isEqualTo(2);
    }

    @Test
    @DisplayName("CC02: the receipt waits for an invoice row another transaction holds, then allocates against what was committed")
    void receiptLocksTheInvoice() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        try (var conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("select id from credit_invoice where id = " + invoice + " for update");
                var call = pool.submit(() -> record(line, upi("1000.00")));
                Thread.sleep(1500);
                assertThat(call.isDone()).describedAs("the receipt must wait for the invoice row").isFalse();
                st.executeUpdate("update credit_invoice set paid_amount = 300, status = 'PARTIALLY_PAID', "
                        + "version = version + 1 where id = " + invoice);
                st.executeUpdate("update credit_agreement set utilized_amount = utilized_amount - 300 where id = "
                        + line.agreementId());
                conn.commit();
                var reply = call.get(30, TimeUnit.SECONDS);
                // 300 was already paid, so 1000 is now more than the 700 owed.
                assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(422);
                assertThat(reply.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("700.00");
            }
        }
        assertThat(outstanding(invoice)).isEqualByComparingTo("700.00");
    }

    // ── permissions and isolation ────────────────────────────────────────

    @Test
    @DisplayName("PY25/PY26: owner, admin, finance and store manager record (201); salesperson, operations, another store and the restaurant get 404, on record and preview")
    void permissionMatrix() throws Exception {
        var fx = three();
        var line = fx.line();
        var other = s.creditLine("200000");

        var refused = new LinkedHashMap<String, String>();
        refused.put("salesperson", staff(line, "SUP_SALESPERSON"));
        refused.put("operations", staff(line, "SUP_OPERATIONS_STAFF"));
        refused.put("another store's owner", other.seller().token());
        refused.put("the restaurant", line.buyer().token());
        var before = snapshot(line);
        for (var who : refused.entrySet()) {
            var reply = e.call("POST", who.getValue(), path(line, ""), UUID.randomUUID().toString(), upi("100.00"));
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(404);
            var peek = e.call("POST", who.getValue(), path(line, "/preview"), null, Map.of("amount", "100.00"));
            assertThat(peek.status()).describedAs("preview " + who.getKey() + " " + peek.body()).isEqualTo(404);
        }
        assertThat(snapshot(line)).isEqualTo(before);
        assertThat(e.count("select count(*) from idempotency_record where actor_id = ?",
                e.userId(refused.get("salesperson")))).describedAs("a refusal leaves no idempotency row").isZero();

        var allowed = new LinkedHashMap<String, String>();
        allowed.put("owner", line.seller().token());
        allowed.put("admin", staff(line, "SUP_ADMIN"));
        allowed.put("finance", staff(line, "SUP_FINANCE_STAFF"));
        allowed.put("store manager", staff(line, "SUP_STORE_MANAGER"));
        long expected = 0;
        for (var who : allowed.entrySet()) {
            var reply = e.call("POST", who.getValue(), path(line, ""), UUID.randomUUID().toString(), upi("100.00"));
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(201);
            assertThat(receipts(line)).isEqualTo(++expected);
            assertThat(e.call("POST", who.getValue(), path(line, "/preview"), null, Map.of("amount", "100.00"))
                    .status()).describedAs("preview " + who.getKey()).isEqualTo(200);
        }
        // An agreement that does not exist is a 404 too.
        assertThat(e.call("POST", line.seller().token(), "/api/v1/credit/agreements/999999999/payments",
                UUID.randomUUID().toString(), upi("1.00")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the Idempotency-Key header is required and checked: missing is 400, over 100 characters is 400")
    void keyHeaderIsRequired() throws Exception {
        var fx = three();
        var before = snapshot(fx.line());
        assertThat(e.call("POST", fx.line().seller().token(), path(fx.line(), ""), null, upi("10.00")).status())
                .isEqualTo(400);
        assertThat(e.call("POST", fx.line().seller().token(), path(fx.line(), ""), "k".repeat(101), upi("10.00"))
                .status()).isEqualTo(400);
        assertThat(snapshot(fx.line())).isEqualTo(before);
    }

    // ── claims, notification, reads ──────────────────────────────────────

    @Test
    @DisplayName("PY12: an open claim on a paid invoice is a warning in the preview only; the payment is allowed and a later confirm is capped")
    void openClaimIsAWarningNotABlock() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 5);
        long other = s.invoice(line, "100", 5);
        var claim = e.claim(line.buyer().token(), invoice, "300.00");
        assertThat(claim.status()).describedAs(claim.body().toString()).isEqualTo(201);
        long claimId = claim.data().get("id").asLong();
        String number = jdbc.queryForObject("select invoice_number from credit_invoice where id = ?", String.class, invoice);

        var preview = preview(line, "400.00", null);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.data().get("pendingClaims").size()).isEqualTo(1);
        assertThat(preview.data().at("/pendingClaims/0/invoiceId").asLong()).isEqualTo(invoice);
        assertThat(preview.data().at("/pendingClaims/0/invoiceNumber").asText()).isEqualTo(number);
        assertThat(preview.data().at("/pendingClaims/0/amount").decimalValue()).isEqualByComparingTo("300.00");
        // Paying only the other invoice does not touch the claimed one: no warning.
        assertThat(preview(line, "100.00", List.of(other)).data().get("pendingClaims").size()).isZero();

        var receipt = record(line, withIds(upi("400.00"), List.of(invoice)));
        assertThat(receipt.status()).describedAs(receipt.body().toString()).isEqualTo(201);
        assertThat(receipt.data().has("pendingClaims")).describedAs("the warning is the preview's only").isFalse();
        assertThat(outstanding(invoice)).isEqualByComparingTo("100.00");
        // The claim (300) is now bigger than what is owed (100): confirming it without an amount is capped at 100.
        var confirmed = e.confirm(line.seller().token(), claimId, null);
        assertThat(confirmed.status()).describedAs(confirmed.body().toString()).isEqualTo(200);
        assertThat(confirmed.data().get("confirmedAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(status(invoice)).isEqualTo("PAID");
        assertThat(outstanding(invoice)).isEqualByComparingTo("0");
        assertThat(utilized(line)).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("PY12: a receipt that settles the invoice supersedes its open claim and never leaves a negative balance")
    void receiptThatSettlesSupersedesTheClaim() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 5);
        long claimId = e.claim(line.buyer().token(), invoice, "200.00").data().get("id").asLong();
        assertThat(record(line, upi("500.00")).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claimId))
                .isEqualTo("SUPERSEDED");
        assertThat(e.confirm(line.seller().token(), claimId, null).status()).isEqualTo(409);
        assertThat(outstanding(invoice)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the restaurant is told once per receipt, not once per invoice")
    void restaurantIsNotifiedOncePerReceipt() throws Exception {
        var fx = three();
        long before = e.count("select count(*) from outbox_event where event_type = 'CreditRepaymentRecorded' "
                + "and json_extract(payload, '$.outletId') = ?", fx.line().buyer().outletId());
        var reply = record(fx.line(), upi("2500.00"));
        assertThat(reply.status()).isEqualTo(201);
        assertThat(allocatedIds(reply)).hasSize(2);
        assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditRepaymentRecorded' "
                + "and json_extract(payload, '$.outletId') = ?", fx.line().buyer().outletId())).isEqualTo(before + 1);
        var event = jdbc.queryForMap("select payload from outbox_event where event_type = 'CreditRepaymentRecorded' "
                + "and json_extract(payload, '$.outletId') = ? order by id desc limit 1", fx.line().buyer().outletId());
        var payload = json.readTree(String.valueOf(event.get("payload")));
        assertThat(payload.get("amount").asText()).isEqualTo("2500.00");
        assertThat(payload.get("receiptId").asLong()).isEqualTo(reply.data().get("receiptId").asLong());
        assertThat(e.count("select count(*) from audit_log where action = 'CREDIT_RECEIPT_RECORDED' and entity_id = ?",
                reply.data().get("receiptId").asLong())).isEqualTo(1);
    }

    @Test
    @DisplayName("statement identity: opening + payments and draws = closing after receipts, and each payment line carries the receipt's method and reference")
    void statementShowsReceiptsAndStaysBalanced() throws Exception {
        var fx = three();
        var first = record(fx.line(), body("2500.00", "UPI", "UTR5550001111"));
        var second = record(fx.line(), body("100.50", "CHEQUE", "CHQ000123"));
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);

        var statement = e.call("GET", fx.line().seller().token(),
                "/api/v1/credit/agreements/" + fx.line().agreementId() + "/statement", null, null);
        assertThat(statement.status()).isEqualTo(200);
        BigDecimal sum = BigDecimal.ZERO;
        for (var line : statement.data().get("lines")) {
            sum = sum.add(line.get("amount").decimalValue());
        }
        assertThat(statement.data().get("openingOwed").decimalValue().add(sum))
                .isEqualByComparingTo(statement.data().get("closingOwed").decimalValue());
        assertThat(statement.data().get("closingOwed").decimalValue()).isEqualByComparingTo("3399.50");

        var refs = new ArrayList<String>();
        for (var line : statement.data().get("lines")) {
            if ("REPAYMENT".equals(line.get("type").asText())) {
                refs.add(line.get("method").asText() + ":" + line.get("reference").asText());
                assertThat(line.get("source").asText()).isEqualTo("SUPPLIER_RECORDED");
            }
        }
        assertThat(refs).containsExactlyInAnyOrder("UPI:UTR5550001111", "UPI:UTR5550001111", "CHEQUE:CHQ000123");
        // The restaurant sees the same lines.
        var theirs = e.call("GET", fx.line().buyer().token(),
                "/api/v1/credit/agreements/" + fx.line().agreementId() + "/statement", null, null);
        assertThat(theirs.data().get("closingOwed").decimalValue()).isEqualByComparingTo("3399.50");
    }

    @Test
    @DisplayName("the single-invoice endpoint still works and still takes ADJUSTMENT (kept for back-compat)")
    void oldEndpointStillWorks() throws Exception {
        var fx = three();
        var reply = s.recordPayment(fx.line().seller(), fx.a(), "100.00");
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(outstanding(fx.a())).isEqualByComparingTo("900.00");
        assertThat(receipts(fx.line())).isZero();
    }

    // ── what a refusal must leave alone ──────────────────────────────────

    private record Snap(List<Map<String, Object>> invoices, BigDecimal utilized, long receipts, long payments,
                        long ledger, long outbox) {
    }

    private Snap snapshot(Line line) {
        return new Snap(
                jdbc.queryForList("select id, status, paid_amount, settled_at from credit_invoice "
                        + "where credit_agreement_id = ? order by id", line.agreementId()),
                utilized(line), receipts(line), payments(line),
                e.count("select count(*) from credit_transaction where credit_agreement_id = ?", line.agreementId()),
                e.count("select count(*) from outbox_event where json_extract(payload, '$.outletId') = ?",
                        line.buyer().outletId()));
    }
}
