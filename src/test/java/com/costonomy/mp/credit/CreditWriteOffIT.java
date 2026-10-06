package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
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

import static com.costonomy.mp.credit.CreditNoteSupport.writeOff;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write-off (B8, D-155, D-156): the supplier gives up on what is owed. Not a payment, not collected, never silent:
 * a reason, the owner or an admin, the line suspended unless the supplier says otherwise, and nothing after it can
 * pay, credit, undo or claim the written-off money.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditWriteOffIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditNoteSupport n;
    private CreditLifecycleSupport l;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        n = new CreditNoteSupport(e, jdbc);
        l = new CreditLifecycleSupport(e, jdbc, relay);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private Reply writeOffInvoice(Line line, long invoice, String amount, String reason, Boolean keepOpen) throws Exception {
        return n.writeOffInvoice(line.seller().token(), invoice, writeOff(amount, reason, keepOpen), key());
    }

    private Reply writeOffLine(Line line, String amount, String reason, Boolean keepOpen) throws Exception {
        return n.writeOffLine(line.seller().token(), line.agreementId(), writeOff(amount, reason, keepOpen), key());
    }

    private String column(Line line, String column) {
        return String.valueOf(l.column(line.agreementId(), column));
    }

    /** A ₹12,000 invoice, 10 days past its grace period, marked OVERDUE. */
    private record Twelve(Line line, long invoice) {
    }

    private Twelve overdueTwelve(Map<String, Object> approval) throws Exception {
        var line = s.creditLine("200000", approval);
        long invoice = s.invoice(line, "120", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(n.status(invoice)).isEqualTo("OVERDUE");
        return new Twelve(line, invoice);
    }

    // ── permissions ──────────────────────────────────────────────────────

    @Test
    @DisplayName("WO04, PM06: only the owner and an admin may write off; finance, manager, salesperson, operations, the restaurant and another supplier get a uniform 404, on both endpoints")
    void permissions() throws Exception {
        var fx = overdueTwelve(Map.of());
        var line = fx.line();
        var before = s.snapshot(line);
        var refused = new LinkedHashMap<String, String>();
        refused.put("finance", l.staff(line, "SUP_FINANCE_STAFF"));
        refused.put("store manager", l.staff(line, "SUP_STORE_MANAGER"));
        refused.put("salesperson", l.staff(line, "SUP_SALESPERSON"));
        refused.put("operations", l.staff(line, "SUP_OPERATIONS_STAFF"));
        refused.put("restaurant", line.buyer().token());
        refused.put("another supplier", s.newSeller().token());
        for (var entry : refused.entrySet()) {
            var byInvoice = n.writeOffInvoice(entry.getValue(), fx.invoice(), writeOff(null, "Restaurant closed", null), key());
            assertThat(byInvoice.status()).describedAs(entry.getKey() + " " + byInvoice.body()).isEqualTo(404);
            var byLine = n.writeOffLine(entry.getValue(), line.agreementId(), writeOff(null, "Restaurant closed", null), key());
            assertThat(byLine.status()).describedAs(entry.getKey() + " " + byLine.body()).isEqualTo(404);
        }
        assertThat(n.writeOffInvoice(line.seller().token(), 987654321L, writeOff(null, "Restaurant closed", null), key()).status())
                .isEqualTo(404);
        assertThat(n.writeOffLine(line.seller().token(), 987654321L, writeOff(null, "Restaurant closed", null), key()).status())
                .isEqualTo(404);
        assertThat(n.notesOf(fx.invoice())).isZero();
        assertThat(n.status(fx.invoice())).isEqualTo("OVERDUE");
        assertThat(s.snapshot(line)).describedAs("nothing moved").isEqualTo(before);
        assertThat(n.audits("CREDIT_WRITTEN_OFF", fx.invoice())).isZero();

        var admin = n.writeOffInvoice(l.staff(line, "SUP_ADMIN"), fx.invoice(), writeOff("1000.00", "Goodwill after a dispute", true), key());
        assertThat(admin.status()).describedAs(admin.body().toString()).isEqualTo(200);
        var owner = writeOffInvoice(line, fx.invoice(), "1000.00", "Restaurant closed", true);
        assertThat(owner.status()).describedAs(owner.body().toString()).isEqualTo(200);
        assertThat(n.credited(fx.invoice())).isEqualByComparingTo("2000");
    }

    // ── one invoice ──────────────────────────────────────────────────────

    @Test
    @DisplayName("WO01: writing off an overdue ₹12,000 invoice: WRITTEN_OFF, owed 0, utilized 0, a WRITE_OFF ledger row, the line suspended by the supplier with reason 'Written off', audited, the restaurant told")
    void wholeInvoice() throws Exception {
        var fx = overdueTwelve(Map.of());
        var before = s.snapshot(fx.line());
        long payoutsBefore = n.payouts(fx.line());

        var reply = writeOffInvoice(fx.line(), fx.invoice(), null, "Restaurant closed down", null);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        var d = reply.data();
        assertThat(d.at("/writtenOff").decimalValue()).isEqualByComparingTo("12000.00");
        assertThat(d.at("/items")).hasSize(1);
        assertThat(d.at("/items/0/invoiceId").asLong()).isEqualTo(fx.invoice());
        assertThat(d.at("/items/0/amount").decimalValue()).isEqualByComparingTo("12000.00");
        assertThat(d.at("/items/0/invoiceStatus").asText()).isEqualTo("WRITTEN_OFF");
        assertThat(d.at("/items/0/outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/items/0/creditNoteNumber").asText()).matches("CN-\\d{6}-\\d{6}");
        assertThat(d.at("/lineSuspended").asBoolean()).isTrue();
        assertThat(d.at("/lineStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(d.at("/agreement/due").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/agreement/available").decimalValue()).isEqualByComparingTo("200000");

        var invoice = n.inv(fx.invoice());
        assertThat(invoice.get("status")).isEqualTo("WRITTEN_OFF");
        assertThat((BigDecimal) invoice.get("credited_amount")).isEqualByComparingTo("12000");
        assertThat((BigDecimal) invoice.get("paid_amount")).describedAs("not a payment").isEqualByComparingTo("0");
        assertThat(invoice.get("settled_at")).isNotNull();
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("0");
        var note = jdbc.queryForMap("select kind, reason_code, note, created_by from credit_note where credit_invoice_id = ?", fx.invoice());
        assertThat(note.get("kind")).isEqualTo("WRITE_OFF");
        assertThat(note.get("note")).isEqualTo("Restaurant closed down");
        assertThat(note.get("created_by")).isNotNull();
        assertThat(n.ledgerRows(fx.line(), "WRITE_OFF")).isEqualTo(1);
        assertThat(n.ledgerRows(fx.line(), "CREDIT_NOTE")).isZero();
        assertThat(column(fx.line(), "status")).isEqualTo("SUSPENDED");
        assertThat(column(fx.line(), "suspension_source")).isEqualTo("SUPPLIER");
        assertThat(column(fx.line(), "suspension_reason")).isEqualTo("Written off");
        assertThat(n.audits("CREDIT_WRITTEN_OFF", fx.invoice())).isEqualTo(1);
        assertThat(n.events("CreditWrittenOff", fx.invoice())).isEqualTo(1);

        var after = s.snapshot(fx.line());
        assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(after.creditPayments()).describedAs("a write-off is not a payment").isEqualTo(before.creditPayments());
        assertThat(after.walletRows()).isEqualTo(before.walletRows());
        assertThat(n.payouts(fx.line())).describedAs("no payout, no commission").isEqualTo(payoutsBefore);
        n.assertConsistent(fx.line());

        var receivables = s.api.get(fx.line().seller().token(), "/api/v1/supplier-stores/" + fx.line().seller().storeId()
                + "/credit/receivables").at("/data");
        assertThat(receivables.get("totalReceivable").decimalValue()).isEqualByComparingTo("0");
        assertThat(receivables.get("collectedThisMonth").decimalValue()).describedAs("WO07: not collected").isEqualByComparingTo("0");
        assertThat(n.invoiceRead(fx.line().buyer().token(), fx.invoice()).data().at("/status").asText()).isEqualTo("WRITTEN_OFF");
    }

    @Test
    @DisplayName("WO02: a partial write-off of ₹2,000 leaves ₹10,000 owed and the invoice's status; the line is still suspended by default")
    void partial() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);

        var reply = writeOffInvoice(line, invoice, "2000.00", "Goodwill", null);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/items/0/invoiceStatus").asText()).isEqualTo("ISSUED");
        assertThat(n.status(invoice)).isEqualTo("ISSUED");
        assertThat(n.outstanding(invoice)).isEqualByComparingTo("10000");
        assertThat(n.credited(invoice)).isEqualByComparingTo("2000");
        assertThat(n.utilized(line)).isEqualByComparingTo("10000");
        assertThat(n.inv(invoice).get("settled_at")).isNull();
        assertThat(column(line, "status")).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("select reason_code from credit_note where credit_invoice_id = ?", String.class, invoice))
                .describedAs("quickReason GOODWILL is not given, so OTHER").isEqualTo("OTHER");
        n.assertConsistent(line);

        var more = writeOffInvoice(line, invoice, "10000.01", "Too much", null);
        assertThat(more.status()).isEqualTo(422);
        assertThat(more.code()).isEqualTo("CREDIT_NOTE_EXCEEDS_OUTSTANDING");
        assertThat(more.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(n.credited(invoice)).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("WO03: keepLineOpen leaves the line ACTIVE; and when the sweep had suspended it, clearing the overdue lifts that")
    void keepLineOpen() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);
        var reply = writeOffInvoice(line, invoice, null, "Settled outside", true);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/lineSuspended").asBoolean()).isFalse();
        assertThat(column(line, "status")).isEqualTo("ACTIVE");
        assertThat(n.audits("CREDIT_SUSPENDED", line.agreementId())).isZero();

        var fx = overdueTwelve(Map.of("maxOverdueAmount", "1000"));
        creditJobs.sweepOverdue();
        assertThat(column(fx.line(), "status")).isEqualTo("SUSPENDED");
        assertThat(column(fx.line(), "suspension_source")).isEqualTo("SYSTEM");
        assertThat(writeOffInvoice(fx.line(), fx.invoice(), null, "Restaurant closed", true).status()).isEqualTo(200);
        assertThat(column(fx.line(), "status")).describedAs("nothing is overdue now, so the sweep's suspension lifts").isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a line the sweep suspended becomes the supplier's suspension 'Written off': clearing the overdue does not reinstate it")
    void sweepSuspensionBecomesTheSuppliers() throws Exception {
        var fx = overdueTwelve(Map.of("maxOverdueAmount", "1000"));
        assertThat(column(fx.line(), "suspension_source")).isEqualTo("SYSTEM");

        assertThat(writeOffInvoice(fx.line(), fx.invoice(), null, "Unrecoverable", null).status()).isEqualTo(200);

        assertThat(column(fx.line(), "status")).isEqualTo("SUSPENDED");
        assertThat(column(fx.line(), "suspension_source")).isEqualTo("SUPPLIER");
        assertThat(column(fx.line(), "suspension_reason")).isEqualTo("Written off");
        creditJobs.sweepOverdue();
        assertThat(column(fx.line(), "status")).isEqualTo("SUSPENDED");
    }

    // ── claims ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a full write-off supersedes the invoice's waiting claims, and says why; a partial one leaves them to be confirmed, capped")
    void claims() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);
        long claim = e.claim(line.buyer().token(), invoice, "3000.00").data().get("id").asLong();

        assertThat(writeOffInvoice(line, invoice, "2000.00", "Goodwill", true).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claim))
                .describedAs("money may really have been paid: a partial write-off does not hide it").isEqualTo("SUBMITTED");

        assertThat(writeOffInvoice(line, invoice, null, "Restaurant closed", true).status()).isEqualTo(200);
        var row = jdbc.queryForMap("select status, decision_note from credit_payment_claim where id = ?", claim);
        assertThat(row.get("status")).isEqualTo("SUPERSEDED");
        assertThat((String) row.get("decision_note")).containsIgnoringCase("written off");
        var late = e.confirm(line.seller().token(), claim, null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("CREDIT_CLAIM_STATE");
    }

    // ── the whole line ───────────────────────────────────────────────────

    /** Three invoices of 1,000, 2,000 and 3,000, due in the order b, a, c (so due date, not id, decides). */
    private record Three(Line line, long a, long b, long c) {
    }

    private Three three() throws Exception {
        var line = s.creditLine("200000");
        long a = s.invoice(line, "100", 10);
        long b = s.invoice(line, "100", 20);
        long c = s.invoice(line, "100", 30);
        due(a, 5);
        due(b, 2);
        due(c, 9);
        return new Three(line, a, b, c);
    }

    private void due(long invoice, int daysFromToday) {
        LocalDate d = CreditEdgeSupport.today().plusDays(daysFromToday);
        jdbc.update("update credit_invoice set due_date = ?, overdue_after = ? where id = ?", d, d.plusDays(5), invoice);
    }

    @Test
    @DisplayName("the whole line with no amount writes off every open invoice: one note each, one event, one suspension, one answer")
    void wholeLine() throws Exception {
        var fx = three();
        long paid = s.invoice(fx.line(), "10", 10);
        assertThat(s.recordPayment(fx.line().seller(), paid, "100.00").status()).isEqualTo(200);

        var reply = writeOffLine(fx.line(), null, "Restaurant closed down", null);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/writtenOff").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(reply.data().at("/items")).hasSize(3);
        assertThat(n.status(fx.a())).isEqualTo("WRITTEN_OFF");
        assertThat(n.status(fx.b())).isEqualTo("WRITTEN_OFF");
        assertThat(n.status(fx.c())).isEqualTo("WRITTEN_OFF");
        assertThat(n.status(paid)).describedAs("a settled invoice is not touched").isEqualTo("PAID");
        assertThat(n.notesOfLine(fx.line())).isEqualTo(3);
        assertThat(n.ledgerRows(fx.line(), "WRITE_OFF")).isEqualTo(3);
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("0");
        assertThat(n.audits("CREDIT_WRITTEN_OFF", fx.a()) + n.audits("CREDIT_WRITTEN_OFF", fx.b())
                + n.audits("CREDIT_WRITTEN_OFF", fx.c())).isEqualTo(3);
        assertThat(n.events("CreditWrittenOff", fx.b()) + n.events("CreditWrittenOff", fx.a()) + n.events("CreditWrittenOff", fx.c()))
                .describedAs("one event for the whole write-off").isEqualTo(1);
        assertThat(n.audits("CREDIT_SUSPENDED", fx.line().agreementId())).isEqualTo(1);
        assertThat(column(fx.line(), "suspension_reason")).isEqualTo("Written off");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("a stated amount over the whole line goes to the oldest due date first (ties by id), and more than is owed is refused")
    void wholeLineWithAnAmount() throws Exception {
        var fx = three();   // due order: b (2,000, day 2), a (1,000, day 5), c (3,000, day 9)

        var reply = writeOffLine(fx.line(), "2500.00", "Goodwill", true);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(n.credited(fx.b())).describedAs("the oldest takes all it owes").isEqualByComparingTo("2000");
        assertThat(n.status(fx.b())).isEqualTo("WRITTEN_OFF");
        assertThat(n.credited(fx.a())).isEqualByComparingTo("500");
        assertThat(n.status(fx.a())).describedAs("a partial keeps its status").isEqualTo("ISSUED");
        assertThat(n.credited(fx.c())).isEqualByComparingTo("0");
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("3500");
        assertThat(reply.data().at("/items")).hasSize(2);
        n.assertConsistent(fx.line());

        var over = writeOffLine(fx.line(), "3500.01", "Too much", true);
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_NOTE_EXCEEDS_OUTSTANDING");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("3500.00");
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("3500");
    }

    @Test
    @DisplayName("a line with nothing owed has nothing to write off: 409 and no trace; so has a settled invoice")
    void nothingOwed() throws Exception {
        var line = s.creditLine("200000");
        var empty = writeOffLine(line, null, "Restaurant closed", null);
        assertThat(empty.status()).isEqualTo(409);
        assertThat(empty.code()).isEqualTo("CREDIT_WRITE_OFF_NOTHING_OWED");
        assertThat(column(line, "status")).isEqualTo("ACTIVE");

        long invoice = s.invoice(line, "10", 10);
        assertThat(s.recordPayment(line.seller(), invoice, "100.00").status()).isEqualTo(200);
        var settled = writeOffInvoice(line, invoice, null, "Restaurant closed", null);
        assertThat(settled.status()).isEqualTo(409);
        assertThat(settled.code()).isEqualTo("CREDIT_WRITE_OFF_NOTHING_OWED");
        assertThat(n.notesOfLine(line)).isZero();
        assertThat(column(line, "status")).isEqualTo("ACTIVE");
    }

    // ── validation and idempotency ───────────────────────────────────────

    @Test
    @DisplayName("a reason of 3 to 500 characters is required; the amount has at most 2 decimals and is above zero; a quick reason is one of four; the key is required")
    void validation() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);
        var before = s.snapshot(line);
        String t = line.seller().token();
        for (String reason : new String[]{"", "  ", "ab", "x".repeat(501)}) {
            assertThat(n.writeOffInvoice(t, invoice, writeOff(null, reason, null), key()).status())
                    .describedAs("reason '" + reason.length() + "'").isEqualTo(400);
        }
        assertThat(n.writeOffInvoice(t, invoice, Map.of(), key()).status()).describedAs("no reason").isEqualTo(400);
        for (String amount : new String[]{"0", "0.00", "-1.00", "10.001", "abc"}) {
            assertThat(n.writeOffInvoice(t, invoice, writeOff(amount, "Restaurant closed", null), key()).status())
                    .describedAs(amount).isEqualTo(400);
        }
        var badQuick = writeOff(null, "Restaurant closed", null);
        badQuick.put("quickReason", "BECAUSE");
        assertThat(n.writeOffInvoice(t, invoice, badQuick, key()).status()).isEqualTo(400);
        assertThat(n.writeOffInvoice(t, invoice, writeOff(null, "Restaurant closed", null), null).status())
                .describedAs("the Idempotency-Key is required").isIn(400, 422);
        assertThat(n.writeOffLine(t, line.agreementId(), writeOff("5.001", "Restaurant closed", null), key()).status()).isEqualTo(400);
        assertThat(n.notesOf(invoice)).isZero();
        assertThat(s.snapshot(line)).isEqualTo(before);

        var good = writeOff(null, "  Restaurant closed  ", true);
        good.put("quickReason", "RESTAURANT_CLOSED");
        assertThat(n.writeOffInvoice(t, invoice, good, key()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select note from credit_note where credit_invoice_id = ?", String.class, invoice))
                .describedAs("the reason is trimmed").isEqualTo("Restaurant closed");
    }

    @Test
    @DisplayName("the same key twice is one write-off (one note, one event, one suspension); another body under that key is a key reuse; 1e3 and 1000.00 are one request")
    void idempotent() throws Exception {
        var fx = three();
        String key = key();
        var body = writeOff("2500.00", "Goodwill", null);
        var first = n.writeOffLine(fx.line().seller().token(), fx.line().agreementId(), body, key);
        assertThat(first.status()).describedAs(first.body().toString()).isEqualTo(200);
        long notes = n.notesOfLine(fx.line());
        long ledger = n.ledgerRows(fx.line(), "WRITE_OFF");

        var again = n.writeOffLine(fx.line().seller().token(), fx.line().agreementId(), body, key);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.data().at("/writtenOff").decimalValue()).isEqualByComparingTo("2500.00");
        assertThat(again.data().at("/items/0/creditNoteId").asLong()).isEqualTo(first.data().at("/items/0/creditNoteId").asLong());
        assertThat(n.notesOfLine(fx.line())).isEqualTo(notes);
        assertThat(n.ledgerRows(fx.line(), "WRITE_OFF")).isEqualTo(ledger);
        assertThat(n.audits("CREDIT_SUSPENDED", fx.line().agreementId())).isEqualTo(1);
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("3500");

        var other = n.writeOffLine(fx.line().seller().token(), fx.line().agreementId(), writeOff("2000.00", "Goodwill", null), key);
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");

        String k2 = key();
        var one = n.writeOffInvoice(fx.line().seller().token(), fx.c(), writeOff("1e3", "Goodwill", true), k2);
        var two = n.writeOffInvoice(fx.line().seller().token(), fx.c(), writeOff("1000.00", "Goodwill", true), k2);
        assertThat(one.status()).describedAs(one.body().toString()).isEqualTo(200);
        assertThat(two.data().at("/items/0/creditNoteId").asLong()).isEqualTo(one.data().at("/items/0/creditNoteId").asLong());
        assertThat(n.credited(fx.c())).isEqualByComparingTo("1000");
    }

    // ── after a write-off ────────────────────────────────────────────────

    @Test
    @DisplayName("WO06: a written-off invoice cannot be paid, receipted, repaid from the wallet, claimed, credited, extended, written off again or have an earlier payment undone")
    void nothingCanTouchAWrittenOffInvoice() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);
        assertThat(s.recordPayment(line.seller(), invoice, "1000.00").status()).isEqualTo(200);
        long payment = e.count("select id from credit_payment where credit_invoice_id = ?", invoice);
        assertThat(writeOffInvoice(line, invoice, null, "Restaurant closed", true).status()).isEqualTo(200);
        assertThat(n.status(invoice)).isEqualTo("WRITTEN_OFF");
        s.topUp(line.buyer(), "5000.00");
        var before = s.snapshot(line);
        long notes = n.notesOf(invoice);

        assertThat(s.recordPayment(line.seller(), invoice, "10.00").status()).describedAs("record payment").isEqualTo(400);

        var receiptBody = new LinkedHashMap<String, Object>();
        receiptBody.put("amount", "10.00");
        receiptBody.put("method", "UPI");
        receiptBody.put("reference", "UTR-" + key().substring(0, 8));
        receiptBody.put("paidOn", CreditEdgeSupport.today().toString());
        receiptBody.put("invoiceIds", List.of(invoice));
        var receipt = e.call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/payments",
                key(), receiptBody);
        assertThat(receipt.status()).describedAs("receipt " + receipt.body()).isEqualTo(404);
        receiptBody.remove("invoiceIds");
        var receiptAll = e.call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/payments",
                key(), receiptBody);
        assertThat(receiptAll.status()).describedAs("receipt over the line: nothing is owed " + receiptAll.body()).isEqualTo(422);

        var wallet = s.repay(line.buyer().token(), line.agreementId(), key(), Map.of("amount", "10.00", "invoiceIds", List.of(invoice)));
        assertThat(wallet.status()).describedAs("wallet " + wallet.body()).isEqualTo(404);
        var walletAll = s.repay(line.buyer().token(), line.agreementId(), key(), Map.of("amount", "10.00"));
        assertThat(walletAll.status()).describedAs("wallet over the line " + walletAll.body()).isEqualTo(422);

        var claim = e.claim(line.buyer().token(), invoice, "10.00");
        assertThat(claim.status()).isEqualTo(422);
        assertThat(claim.code()).isEqualTo("CREDIT_OVERPAYMENT");

        var credit = n.note(line, invoice, "10.00");
        assertThat(credit.status()).isEqualTo(409);
        assertThat(credit.code()).isEqualTo("CREDIT_NOTE_INVOICE_SETTLED");

        var extend = e.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/extend-due", key(),
                Map.of("newDueDate", CreditEdgeSupport.today().plusDays(20).toString(), "reason", "More time"));
        assertThat(extend.status()).isEqualTo(409);

        var again = writeOffInvoice(line, invoice, null, "Again", true);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("CREDIT_WRITE_OFF_NOTHING_OWED");

        var reversal = e.call("POST", line.seller().token(), "/api/v1/credit/payments/" + payment + "/reverse", key(),
                Map.of("reason", "Typed the wrong amount"));
        assertThat(reversal.status()).isEqualTo(409);
        assertThat(reversal.code()).isEqualTo("CREDIT_REVERSAL_NOT_ALLOWED");

        assertThat(s.snapshot(line)).describedAs("nothing moved").isEqualTo(before);
        assertThat(n.notesOf(invoice)).isEqualTo(notes);
        n.assertConsistent(line);
    }

    @Test
    @DisplayName("CC05: the overdue sweep ignores written-off money: a written-off invoice is never marked overdue; a partly written-off one is overdue for what remains")
    void sweepIgnoresWrittenOff() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "1000"));
        long gone = s.invoice(line, "120", 100);
        long partly = s.invoice(line, "100", 100);
        s.age(gone, 10);
        s.age(partly, 10);
        assertThat(writeOffInvoice(line, gone, null, "Restaurant closed", true).status()).isEqualTo(200);
        assertThat(writeOffInvoice(line, partly, "9500.00", "Goodwill", true).status()).isEqualTo(200);

        creditJobs.sweepOverdue();

        assertThat(n.status(gone)).isEqualTo("WRITTEN_OFF");
        assertThat(e.count("select count(*) from outbox_event where event_type = 'CreditOverdue' and aggregate_id = ?", gone)).isZero();
        assertThat(n.status(partly)).isEqualTo("OVERDUE");
        var payload = json.readTree(jdbc.queryForObject("select payload from outbox_event where event_type = 'CreditOverdue' "
                + "and aggregate_id = ?", String.class, partly));
        assertThat(payload.get("outstanding").asText()).isEqualTo("500.0000");
        assertThat(column(line, "status")).describedAs("500 overdue is within the 1,000 tolerance").isEqualTo("ACTIVE");
        var restaurantRead = s.api.get(line.buyer().token(), "/api/v1/credit/agreements/" + line.agreementId()).at("/data");
        assertThat(restaurantRead.get("overdue").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("WO14: the statement shows 'Written off' lines with their credit note number and opening + lines = closing across notes, write-offs and payments")
    void statementIdentity() throws Exception {
        var fx = three();
        n.noteOk(fx.line(), fx.a(), "100.00");
        assertThat(s.recordPayment(fx.line().seller(), fx.b(), "500.00").status()).isEqualTo(200);
        assertThat(writeOffLine(fx.line(), "1000.00", "Goodwill", true).status()).isEqualTo(200);
        assertThat(writeOffInvoice(fx.line(), fx.c(), null, "Restaurant closed", true).status()).isEqualTo(200);

        for (String token : List.of(fx.line().buyer().token(), fx.line().seller().token())) {
            var statement = n.statement(token, fx.line()).data();
            var lines = new ArrayList<JsonNode>();
            statement.get("lines").forEach(lines::add);
            java.util.Collections.reverse(lines);
            BigDecimal running = statement.get("openingOwed").decimalValue();
            for (JsonNode line : lines) {
                running = running.add(line.get("amount").decimalValue());
                assertThat(line.get("owedAfter").decimalValue()).describedAs(line.toString()).isEqualByComparingTo(running);
            }
            assertThat(running).isEqualByComparingTo(statement.get("closingOwed").decimalValue());
            assertThat(statement.get("closingOwed").decimalValue()).isEqualByComparingTo(n.utilized(fx.line()));
            var off = lines.stream().filter(x -> "WRITE_OFF".equals(x.get("type").asText())).toList();
            assertThat(off).hasSize(2);
            assertThat(off.get(0).get("label").asText()).isEqualTo("Written off");
            assertThat(off.get(0).get("creditNoteNumber").asText()).startsWith("CN-");
        }
    }

    @Test
    @DisplayName("the restaurant is told once in-app only, in neutral words; the invoice detail lists the write-off as a credit note of kind WRITE_OFF")
    void restaurantIsToldInAppOnly() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "120", 100);
        l.registerDevice(line.buyer().token());

        assertThat(writeOffInvoice(line, invoice, null, "Restaurant closed", null).status()).isEqualTo(200);

        var payload = json.readTree(l.payload("CreditWrittenOff", invoice));
        assertThat(payload.at("/outletId").asLong()).isEqualTo(line.buyer().outletId());
        assertThat(payload.at("/supplierStoreId").asLong()).isEqualTo(line.seller().storeId());
        assertThat(payload.at("/amount").asText()).isEqualTo("12000.00");
        assertThat(payload.toString()).describedAs("the supplier's private reason is not put in the event")
                .doesNotContain("Restaurant closed");
        l.relayEvents("CreditWrittenOff", invoice);
        var sent = l.notificationsFor("CreditWrittenOff", invoice);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat((String) sent.get(0).get("body")).contains("ABC Foods").contains("INV-");
        assertThat(l.deliveries("CreditWrittenOff", invoice, "PUSH")).isZero();
        assertThat(l.deliveries("CreditWrittenOff", invoice, "SMS")).isZero();

        var read = n.invoiceRead(line.buyer().token(), invoice).data();
        assertThat(read.at("/creditNotes/0/kind").asText()).isEqualTo("WRITE_OFF");
        assertThat(read.at("/creditedAmount").decimalValue()).isEqualByComparingTo("12000.00");
    }

    @Test
    @DisplayName("cancelling the order of a part-paid, written-off invoice makes no new note; what was paid is owed back")
    void cancelAfterAWriteOff() throws Exception {
        var line = s.creditLine("200000", Map.of());
        long intent = e.answeredRequest(line, "400", 100);
        long orderId = e.orderOnCredit(line.buyer(), intent).data().get("supplierOrderId").asLong();
        long invoice = jdbc.queryForObject("select id from credit_invoice where supplier_order_id = ?", Long.class, orderId);
        assertThat(s.recordPayment(line.seller(), invoice, "10000.00").status()).isEqualTo(200);
        assertThat(writeOffInvoice(line, invoice, null, "Restaurant closed", true).status()).isEqualTo(200);

        var cancel = e.call("POST", line.seller().token(), "/api/v1/supplier-orders/" + orderId + "/supplier-cancel", key(),
                Map.of("reason", "OUT_OF_STOCK"));

        assertThat(cancel.status()).describedAs(cancel.body().toString()).isEqualTo(200);
        assertThat(n.notesOf(invoice)).describedAs("only the write-off").isEqualTo(1);
        assertThat(n.status(invoice)).isEqualTo("WRITTEN_OFF");
        assertThat(jdbc.queryForObject("select amount from credit_refund_due where credit_invoice_id = ?", BigDecimal.class, invoice))
                .isEqualByComparingTo("10000");
        n.assertConsistent(line);
    }

    // ── races ────────────────────────────────────────────────────────────

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
    @DisplayName("WO05: a write-off racing a wallet repayment of the same invoice: the wallet then the invoice lock order holds and one sees the other's result")
    void writeOffRacingAWalletRepayment() throws Exception {
        for (int round = 0; round < 3; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);   // 1,000
            s.topUp(line.buyer(), "5000.00");
            var replies = concurrently(List.of(() -> writeOffInvoice(line, invoice, null, "Restaurant closed", true),
                    () -> s.repay(line.buyer().token(), line.agreementId(), key(),
                            Map.of("amount", "600.00", "invoiceIds", List.of(invoice)))));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isEqualTo(200);
            assertThat(replies.get(1).status()).describedAs(replies.toString()).isIn(201, 404, 422);
            assertThat(n.outstanding(invoice)).isEqualByComparingTo("0");
            assertThat(n.status(invoice)).isEqualTo("WRITTEN_OFF");
            BigDecimal paid = (BigDecimal) n.inv(invoice).get("paid_amount");
            assertThat(n.credited(invoice).add(paid)).isEqualByComparingTo("1000");
            assertThat(replies.get(1).status() == 201 ? paid : BigDecimal.ZERO).isEqualByComparingTo(paid);
            n.assertConsistent(line);
        }
    }

    @Test
    @DisplayName("a write-off racing a supplier receipt over the line: consistent whichever runs first, never more than is owed")
    void writeOffRacingAReceipt() throws Exception {
        for (int round = 0; round < 3; round++) {
            var fx = three();
            var body = new LinkedHashMap<String, Object>();
            body.put("amount", "3000.00");
            body.put("method", "UPI");
            body.put("reference", "UTR-" + key().substring(0, 8));
            body.put("paidOn", CreditEdgeSupport.today().toString());
            var replies = concurrently(List.of(() -> writeOffLine(fx.line(), "4000.00", "Goodwill", true),
                    () -> e.call("POST", fx.line().seller().token(), "/api/v1/credit/agreements/" + fx.line().agreementId()
                            + "/payments", key(), body)));

            assertThat(replies.get(0).status()).describedAs(replies.toString()).isIn(200, 422);
            assertThat(replies.get(1).status()).describedAs(replies.toString()).isIn(201, 422);
            n.assertConsistent(fx.line());
            assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ? "
                    + "and amount - paid_amount - credited_amount < 0", fx.line().agreementId())).isZero();
        }
    }

    @Test
    @DisplayName("two simultaneous full write-offs of one invoice (different keys): one wins, the other finds nothing owed")
    void twoWriteOffsRace() throws Exception {
        for (int round = 0; round < 3; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);
            var replies = concurrently(List.of(() -> writeOffInvoice(line, invoice, null, "Restaurant closed", true),
                    () -> writeOffInvoice(line, invoice, null, "Restaurant closed", true)));
            assertThat(replies.stream().map(Reply::status).sorted().toList()).describedAs(replies.toString())
                    .containsExactly(200, 409);
            assertThat(n.notesOf(invoice)).isEqualTo(1);
            n.assertConsistent(line);
        }
    }
}
