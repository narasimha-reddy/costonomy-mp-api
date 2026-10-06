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
import java.util.ArrayList;
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
 * The supplier's credit note (B7, D-151, D-153): an amount taken off an invoice that is not a payment. Capped at what
 * is owed, never on a settled invoice, idempotent, it frees the credit the way a payment does, never touches a payout,
 * and shows on the statement without breaking its identity.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "costonomy.mp.credit.wallet-repay.enabled=true")
class CreditNoteIT extends AbstractIntegrationTest {

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

    /** An invoice of ₹7,000 on a ₹2,00,000 line. */
    private record Seven(Line line, long invoice) {
    }

    private Seven seven() throws Exception {
        var line = s.creditLine("200000");
        return new Seven(line, s.invoice(line, "70", 100));
    }

    // ── the note ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("WO08: a note of ₹2,100 on ₹7,000 credits 2,100, leaves 4,900 owed, frees the credit, is numbered and audited")
    void aNoteReducesWhatIsOwedAndFreesTheCredit() throws Exception {
        var fx = seven();
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("7000");

        var reply = n.note(fx.line().seller().token(), fx.invoice(), "2100.00", "SHORT_SUPPLY",
                UUID.randomUUID().toString(), "Two crates short");

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        var d = reply.data();
        assertThat(d.at("/creditNoteNumber").asText()).matches("CN-\\d{6}-\\d{6}");
        assertThat(d.at("/invoiceId").asLong()).isEqualTo(fx.invoice());
        assertThat(d.at("/amount").decimalValue()).isEqualByComparingTo("2100.00");
        assertThat(d.at("/reasonCode").asText()).isEqualTo("SHORT_SUPPLY");
        assertThat(d.at("/kind").asText()).isEqualTo("MANUAL");
        assertThat(d.at("/note").asText()).isEqualTo("Two crates short");
        assertThat(d.at("/invoice/creditedAmount").decimalValue()).isEqualByComparingTo("2100.00");
        assertThat(d.at("/invoice/paidAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(d.at("/invoice/outstanding").decimalValue()).isEqualByComparingTo("4900.00");
        assertThat(d.at("/agreement/due").decimalValue()).isEqualByComparingTo("4900.00");
        assertThat(d.at("/agreement/available").decimalValue()).isEqualByComparingTo("195100.00");

        assertThat(n.credited(fx.invoice())).isEqualByComparingTo("2100");
        assertThat(n.outstanding(fx.invoice())).isEqualByComparingTo("4900");
        assertThat(n.status(fx.invoice())).isEqualTo("PARTIALLY_PAID");
        assertThat(n.utilized(fx.line())).describedAs("what is drawn went down by the note").isEqualByComparingTo("4900");
        var row = jdbc.queryForMap("select kind, reason_code, created_by, credit_agreement_id, outlet_id, supplier_store_id "
                + "from credit_note where credit_invoice_id = ?", fx.invoice());
        assertThat(row.get("kind")).isEqualTo("MANUAL");
        assertThat(row.get("created_by")).isNotNull();
        assertThat(((Number) row.get("outlet_id")).longValue()).isEqualTo(fx.line().buyer().outletId());
        assertThat(((Number) row.get("supplier_store_id")).longValue()).isEqualTo(fx.line().seller().storeId());
        var ledger = jdbc.queryForMap("select amount, credit_note_id, balance_utilized_after, credit_invoice_id from credit_transaction "
                + "where credit_agreement_id = ? and transaction_type = 'CREDIT_NOTE'", fx.line().agreementId());
        assertThat((BigDecimal) ledger.get("amount")).isEqualByComparingTo("2100");
        assertThat((BigDecimal) ledger.get("balance_utilized_after")).isEqualByComparingTo("4900");
        assertThat(ledger.get("credit_note_id")).isEqualTo(jdbc.queryForObject(
                "select id from credit_note where credit_invoice_id = ?", Long.class, fx.invoice()));
        assertThat(n.audits("CREDIT_NOTE_ISSUED", d.at("/id").asLong())).isEqualTo(1);
        assertThat(n.events("CreditNoteIssued", fx.invoice())).isEqualTo(1);
        n.assertConsistent(fx.line());

        // The restaurant reads the same on its side.
        var read = n.invoiceRead(fx.line().buyer().token(), fx.invoice()).data();
        assertThat(read.at("/creditedAmount").decimalValue()).isEqualByComparingTo("2100.00");
        assertThat(read.at("/outstanding").decimalValue()).isEqualByComparingTo("4900.00");
        assertThat(read.at("/creditNotes/0/creditNoteNumber").asText()).isEqualTo(d.at("/creditNoteNumber").asText());
        var agreement = s.api.get(fx.line().buyer().token(), "/api/v1/credit/agreements/" + fx.line().agreementId()).at("/data");
        assertThat(agreement.get("available").decimalValue()).isEqualByComparingTo("195100");
        assertThat(agreement.get("due").decimalValue()).isEqualByComparingTo("4900");
    }

    @Test
    @DisplayName("WO13: a note is not a payment: no payment row, no receipt, no payout, no wallet movement, nothing collected")
    void noPayoutNoCommissionNoPayment() throws Exception {
        var fx = seven();
        var before = s.snapshot(fx.line());
        long payoutsBefore = n.payouts(fx.line());

        n.noteOk(fx.line(), fx.invoice(), "2100.00");

        var after = s.snapshot(fx.line());
        assertThat(after.repayments()).isEqualTo(before.repayments());
        assertThat(after.creditPayments()).isEqualTo(before.creditPayments());
        assertThat(after.walletRows()).isEqualTo(before.walletRows());
        assertThat(after.wallet()).isEqualByComparingTo(before.wallet());
        assertThat(n.payouts(fx.line())).isEqualTo(payoutsBefore);
        assertThat(jdbc.queryForObject("select paid_amount from credit_invoice where id = ?", BigDecimal.class, fx.invoice()))
                .describedAs("paid is paid money only").isEqualByComparingTo("0");
        var receivables = s.api.get(fx.line().seller().token(),
                "/api/v1/supplier-stores/" + fx.line().seller().storeId() + "/credit/receivables").at("/data");
        assertThat(receivables.get("collectedThisMonth").decimalValue()).describedAs("a note is not collected")
                .isEqualByComparingTo("0");
        assertThat(receivables.get("totalReceivable").decimalValue()).isEqualByComparingTo("4900");
    }

    @Test
    @DisplayName("WO09: more than is owed is refused with 422 CREDIT_NOTE_EXCEEDS_OUTSTANDING and the outstanding; exactly what is owed settles the invoice")
    void cappedAtWhatIsOwed() throws Exception {
        var fx = seven();
        assertThat(s.recordPayment(fx.line().seller(), fx.invoice(), "1000.00").status()).isEqualTo(200);
        var before = s.snapshot(fx.line());

        var over = n.note(fx.line(), fx.invoice(), "6000.01");

        assertThat(over.status()).describedAs(over.body().toString()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_NOTE_EXCEEDS_OUTSTANDING");
        assertThat(over.body().at("/error/details/outstanding").decimalValue()).isEqualByComparingTo("6000.00");
        assertThat(s.snapshot(fx.line())).describedAs("nothing moved").isEqualTo(before);
        assertThat(n.notesOf(fx.invoice())).isZero();
        assertThat(n.credited(fx.invoice())).isEqualByComparingTo("0");

        var exact = n.note(fx.line(), fx.invoice(), "6000.00");
        assertThat(exact.status()).describedAs(exact.body().toString()).isEqualTo(201);
        assertThat(n.status(fx.invoice())).describedAs("paid + credited = amount settles it").isEqualTo("PAID");
        assertThat(n.inv(fx.invoice()).get("settled_at")).isNotNull();
        assertThat(n.utilized(fx.line())).isEqualByComparingTo("0");
        n.assertConsistent(fx.line());
    }

    @Test
    @DisplayName("WO10: a PAID or WRITTEN_OFF invoice takes no credit note: 409 CREDIT_NOTE_INVOICE_SETTLED, the supplier refunds directly")
    void refusedOnASettledInvoice() throws Exception {
        var fx = seven();
        assertThat(s.recordPayment(fx.line().seller(), fx.invoice(), "7000.00").status()).isEqualTo(200);
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        var paid = n.note(fx.line(), fx.invoice(), "100.00");
        assertThat(paid.status()).describedAs(paid.body().toString()).isEqualTo(409);
        assertThat(paid.code()).isEqualTo("CREDIT_NOTE_INVOICE_SETTLED");

        long other = s.invoice(fx.line(), "10", 10);
        jdbc.update("update credit_invoice set status = 'WRITTEN_OFF', credited_amount = amount where id = ?", other);
        jdbc.update("update credit_agreement set utilized_amount = utilized_amount - 100 where id = ?", fx.line().agreementId());
        var written = n.note(fx.line(), other, "1.00");
        assertThat(written.status()).describedAs(written.body().toString()).isEqualTo(409);
        assertThat(written.code()).isEqualTo("CREDIT_NOTE_INVOICE_SETTLED");
        assertThat(n.notesOf(fx.invoice()) + n.notesOf(other)).isZero();
    }

    @Test
    @DisplayName("WO11: the same key twice is one note; the same key with another amount is a key reuse; 1e3, 1000 and 1000.00 are one request")
    void idempotent() throws Exception {
        var fx = seven();
        String key = UUID.randomUUID().toString();
        var first = n.note(fx.line().seller().token(), fx.invoice(), "2100.00", "QUALITY", key, "Damaged");
        assertThat(first.status()).isEqualTo(201);

        var again = n.note(fx.line().seller().token(), fx.invoice(), "2100.00", "QUALITY", key, "Damaged");
        assertThat(again.status()).describedAs(again.body().toString()).isIn(200, 201);
        assertThat(again.data().at("/id").asLong()).isEqualTo(first.data().at("/id").asLong());
        assertThat(n.notesOf(fx.invoice())).isEqualTo(1);
        assertThat(n.credited(fx.invoice())).isEqualByComparingTo("2100");
        assertThat(n.ledgerRows(fx.line(), "CREDIT_NOTE")).isEqualTo(1);
        assertThat(n.events("CreditNoteIssued", fx.invoice())).isEqualTo(1);
        assertThat(n.audits("CREDIT_NOTE_ISSUED", first.data().at("/id").asLong())).isEqualTo(1);

        var other = n.note(fx.line().seller().token(), fx.invoice(), "2000.00", "QUALITY", key, "Damaged");
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(n.notesOf(fx.invoice())).isEqualTo(1);

        String k2 = UUID.randomUUID().toString();
        var one = n.note(fx.line().seller().token(), fx.invoice(), "1e3", "GOODWILL", k2, null);
        var two = n.note(fx.line().seller().token(), fx.invoice(), "1000.00", "GOODWILL", k2, null);
        assertThat(one.status()).describedAs(one.body().toString()).isEqualTo(201);
        assertThat(two.data().at("/id").asLong()).describedAs(two.body().toString()).isEqualTo(one.data().at("/id").asLong());
        assertThat(n.notesOf(fx.invoice())).isEqualTo(2);
    }

    @Test
    @DisplayName("MN01: zero, negative, three decimals, no reason code, an unknown one and a long note are 400 and leave no trace")
    void validation() throws Exception {
        var fx = seven();
        var before = s.snapshot(fx.line());
        String t = fx.line().seller().token();
        for (String bad : new String[]{"0", "0.00", "-5.00", "100.001", "abc"}) {
            var r = n.note(t, fx.invoice(), bad, "SHORT_SUPPLY", UUID.randomUUID().toString(), null);
            assertThat(r.status()).describedAs(bad + " " + r.body()).isEqualTo(400);
        }
        var noReason = e.call("POST", t, "/api/v1/credit/invoices/" + fx.invoice() + "/credit-notes",
                UUID.randomUUID().toString(), Map.of("amount", "10.00"));
        assertThat(noReason.status()).isEqualTo(400);
        var unknown = n.note(t, fx.invoice(), "10.00", "BECAUSE", UUID.randomUUID().toString(), null);
        assertThat(unknown.status()).isEqualTo(400);
        var longNote = n.note(t, fx.invoice(), "10.00", "OTHER", UUID.randomUUID().toString(), "x".repeat(501));
        assertThat(longNote.status()).isEqualTo(400);
        var noKey = n.note(t, fx.invoice(), "10.00", "OTHER", null, null);
        assertThat(noKey.status()).describedAs("the Idempotency-Key is required").isIn(400, 422);
        var badDispute = e.call("POST", t, "/api/v1/credit/invoices/" + fx.invoice() + "/credit-notes",
                UUID.randomUUID().toString(), Map.of("amount", "10.00", "reasonCode", "QUALITY", "disputeId", 999999999));
        assertThat(badDispute.status()).describedAs("a dispute that is not this order's is refused").isIn(400, 404);

        assertThat(n.notesOf(fx.invoice())).isZero();
        assertThat(s.snapshot(fx.line())).isEqualTo(before);
    }

    @Test
    @DisplayName("PM: owner, admin, finance and store manager may issue; salesperson, operations, the restaurant and another supplier get a uniform 404")
    void permissions() throws Exception {
        var fx = seven();
        var line = fx.line();
        var before = s.snapshot(line);
        var refused = new java.util.LinkedHashMap<String, String>();
        refused.put("salesperson", l.staff(line, "SUP_SALESPERSON"));
        refused.put("operations", l.staff(line, "SUP_OPERATIONS_STAFF"));
        refused.put("restaurant", line.buyer().token());
        refused.put("another supplier", s.newSeller().token());
        for (var entry : refused.entrySet()) {
            var r = n.note(entry.getValue(), fx.invoice(), "10.00", "OTHER", UUID.randomUUID().toString(), null);
            assertThat(r.status()).describedAs(entry.getKey() + " " + r.body()).isEqualTo(404);
        }
        var missing = n.note(line.seller().token(), 987654321L, "10.00", "OTHER", UUID.randomUUID().toString(), null);
        assertThat(missing.status()).describedAs("an id that does not exist looks the same").isEqualTo(404);
        assertThat(n.notesOf(fx.invoice())).isZero();
        assertThat(s.snapshot(line)).isEqualTo(before);

        for (String role : List.of("SUP_ADMIN", "SUP_FINANCE_STAFF", "SUP_STORE_MANAGER")) {
            var r = n.note(l.staff(line, role), fx.invoice(), "10.00", "GOODWILL", UUID.randomUUID().toString(), null);
            assertThat(r.status()).describedAs(role + " " + r.body()).isEqualTo(201);
        }
        assertThat(n.note(line, fx.invoice(), "10.00").status()).describedAs("owner").isEqualTo(201);
        assertThat(n.notesOf(fx.invoice())).isEqualTo(4);
    }

    @Test
    @DisplayName("WO12: a note that brings overdue back within tolerance lifts the sweep's suspension; a supplier's own suspension stays")
    void reinstatesALineTheSweepSuspended() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(l.status(line.agreementId())).isEqualTo("SUSPENDED");
        assertThat(l.column(line.agreementId(), "suspension_source")).isEqualTo("SYSTEM");

        n.noteOk(line, invoice, "20000.00");
        assertThat(l.status(line.agreementId())).describedAs("20,000 still overdue is above 10,000").isEqualTo("SUSPENDED");

        n.noteOk(line, invoice, "10000.00");
        assertThat(l.status(line.agreementId())).describedAs("10,000 overdue is within tolerance").isEqualTo("ACTIVE");
        assertThat(l.audits("CREDIT_REINSTATED", line.agreementId())).isEqualTo(1);

        // A supplier's own suspension is not the sweep's to lift, however much a note clears.
        var line2 = s.creditLine("200000");
        long inv2 = s.invoice(line2, "10", 10);
        assertThat(e.suspend(line2, "Review").status()).isEqualTo(200);
        n.noteOk(line2, inv2, "100.00");
        assertThat(l.status(line2.agreementId())).isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("a note clears the overdue floor a manual reinstate left, once nothing is overdue")
    void clearsTheOverdueFloor() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(e.call("POST", line.seller().token(), "/api/v1/credit/agreements/" + line.agreementId() + "/reinstate",
                null, Map.of()).status()).isEqualTo(200);
        assertThat((BigDecimal) l.column(line.agreementId(), "overdue_floor")).isEqualByComparingTo("40000");

        n.noteOk(line, invoice, "40000.00");

        assertThat(l.column(line.agreementId(), "overdue_floor")).isNull();
    }

    // ── claims ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("CC04: a note caps a waiting claim like a payment: confirm is limited to what is still owed; a note that settles the invoice supersedes it")
    void claimsAreCappedAndSuperseded() throws Exception {
        var fx = seven();
        var claim = e.claim(fx.line().buyer().token(), fx.invoice(), "5000.00");
        assertThat(claim.status()).describedAs(claim.body().toString()).isEqualTo(201);
        long claimId = claim.data().get("id").asLong();

        n.noteOk(fx.line(), fx.invoice(), "4000.00");
        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claimId))
                .describedAs("still waiting: money may have moved").isEqualTo("SUBMITTED");

        var confirm = e.confirm(fx.line().seller().token(), claimId, null);
        assertThat(confirm.status()).describedAs(confirm.body().toString()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select confirmed_amount from credit_payment_claim where id = ?", BigDecimal.class, claimId))
                .describedAs("capped at the 3,000 still owed").isEqualByComparingTo("3000");
        assertThat(n.status(fx.invoice())).isEqualTo("PAID");
        n.assertConsistent(fx.line());

        var fx2 = seven();
        var claim2 = e.claim(fx2.line().buyer().token(), fx2.invoice(), "5000.00");
        long claimId2 = claim2.data().get("id").asLong();
        n.noteOk(fx2.line(), fx2.invoice(), "7000.00");
        assertThat(jdbc.queryForObject("select status from credit_payment_claim where id = ?", String.class, claimId2))
                .isEqualTo("SUPERSEDED");
        var late = e.confirm(fx2.line().seller().token(), claimId2, null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.code()).isEqualTo("CREDIT_CLAIM_STATE");
    }

    @Test
    @DisplayName("what a restaurant can still claim is the outstanding after the note, not the amount less payments")
    void reportableExcludesCredited() throws Exception {
        var fx = seven();
        n.noteOk(fx.line(), fx.invoice(), "2100.00");
        var over = e.claim(fx.line().buyer().token(), fx.invoice(), "4900.01");
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.code()).isEqualTo("CREDIT_OVERPAYMENT");
        assertThat(e.claim(fx.line().buyer().token(), fx.invoice(), "4900.00").status()).isEqualTo(201);
        var read = n.invoiceRead(fx.line().buyer().token(), fx.invoice()).data();
        assertThat(read.at("/reportableAmount").decimalValue()).isEqualByComparingTo("0");
    }

    // ── statement and reads ──────────────────────────────────────────────

    @Test
    @DisplayName("WO14, WO15: the statement lists the note as 'Credit note' with its number, and opening + lines = closing on both sides")
    void statementIdentity() throws Exception {
        var fx = seven();
        long second = s.invoice(fx.line(), "10", 100);
        var note = n.noteOk(fx.line(), fx.invoice(), "2100.00");
        assertThat(s.recordPayment(fx.line().seller(), second, "400.00").status()).isEqualTo(200);
        n.noteOk(fx.line(), second, "100.00");

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
            assertThat(statement.get("closingOwed").decimalValue()).isEqualByComparingTo("5400.00");
            var notes = lines.stream().filter(x -> "CREDIT_NOTE".equals(x.get("type").asText())).toList();
            assertThat(notes).hasSize(2);
            assertThat(notes.get(0).get("label").asText()).isEqualTo("Credit note");
            assertThat(notes.get(0).get("amount").decimalValue()).isEqualByComparingTo("-2100.00");
            assertThat(notes.get(0).get("creditNoteNumber").asText()).isEqualTo(note.data().at("/creditNoteNumber").asText());
            assertThat(notes.get(0).get("invoiceNumber").asText()).startsWith("INV-");
            assertThat(notes.get(1).get("creditNoteNumber").asText()).isNotEqualTo(notes.get(0).get("creditNoteNumber").asText());
        }
    }

    @Test
    @DisplayName("GET credit-notes: both sides read a page, newest first; nobody else may")
    void listing() throws Exception {
        var fx = seven();
        for (int i = 0; i < 3; i++) {
            n.noteOk(fx.line(), fx.invoice(), "100.00");
        }
        for (String token : List.of(fx.line().seller().token(), fx.line().buyer().token())) {
            var page = n.notes(token, fx.line().agreementId(), "?page=0&size=2");
            assertThat(page.status()).describedAs(page.body().toString()).isEqualTo(200);
            assertThat(page.data().at("/items")).hasSize(2);
            assertThat(page.data().at("/total").asLong()).isEqualTo(3);
            assertThat(page.data().at("/hasNext").asBoolean()).isTrue();
            assertThat(page.data().at("/items/0/id").asLong()).isGreaterThan(page.data().at("/items/1/id").asLong());
            assertThat(page.data().at("/items/0/invoiceNumber").asText()).startsWith("INV-");
            assertThat(page.data().at("/items/0/kind").asText()).isEqualTo("MANUAL");
            assertThat(n.notes(token, fx.line().agreementId(), "?page=1&size=2").data().at("/items")).hasSize(1);
        }
        assertThat(n.notes(s.newBuyer().token(), fx.line().agreementId(), "").status()).isEqualTo(404);
        assertThat(n.notes(s.newSeller().token(), fx.line().agreementId(), "").status()).isEqualTo(404);
        assertThat(n.notes(fx.line().seller().token(), fx.line().agreementId(), "?size=0").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("WO13: the restaurant is told once, in-app and push, never SMS")
    void restaurantIsTold() throws Exception {
        var fx = seven();
        l.registerDevice(fx.line().buyer().token());
        n.noteOk(fx.line(), fx.invoice(), "2100.00");

        var payload = json.readTree(l.payload("CreditNoteIssued", fx.invoice()));
        assertThat(payload.at("/outletId").asLong()).isEqualTo(fx.line().buyer().outletId());
        assertThat(payload.at("/supplierStoreId").asLong()).isEqualTo(fx.line().seller().storeId());
        assertThat(payload.at("/amount").asText()).isEqualTo("2100.00");
        l.relayEvents("CreditNoteIssued", fx.invoice());
        var sent = l.notificationsFor("CreditNoteIssued", fx.invoice());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat((String) sent.get(0).get("body")).contains("ABC Foods").contains("CN-");
        assertThat(l.deliveries("CreditNoteIssued", fx.invoice(), "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditNoteIssued", fx.invoice(), "SMS")).isZero();
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
    @DisplayName("CC: two notes for ₹600 on a ₹1,000 invoice at once: one wins, the other is refused, never more than is owed")
    void twoNotesRaceForTheSameMoney() throws Exception {
        for (int round = 0; round < 3; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);
            var replies = concurrently(List.of(() -> n.note(line, invoice, "600.00"), () -> n.note(line, invoice, "600.00")));
            assertThat(replies.stream().map(Reply::status).sorted().toList()).describedAs(replies.toString())
                    .containsExactly(201, 422);
            assertThat(n.credited(invoice)).isEqualByComparingTo("600");
            n.assertConsistent(line);
        }
    }

    @Test
    @DisplayName("CC04: a note racing a recorded payment on one invoice ends consistent whichever runs first")
    void noteRacingAPayment() throws Exception {
        for (int round = 0; round < 3; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);
            var replies = concurrently(List.of(() -> n.note(line, invoice, "600.00"),
                    () -> s.recordPayment(line.seller(), invoice, "600.00")));
            int ok = (int) replies.stream().filter(r -> r.status() == 201 || r.status() == 200).count();
            assertThat(ok).describedAs(replies.toString()).isEqualTo(1);
            assertThat(replies.get(0).status()).describedAs("the note is never a 404").isIn(201, 422);
            assertThat(n.outstanding(invoice)).isEqualByComparingTo("400");
            n.assertConsistent(line);
        }
    }

    @Test
    @DisplayName("CC02: a note racing a wallet repayment of the same invoice ends consistent whichever runs first")
    void noteRacingAWalletRepayment() throws Exception {
        for (int round = 0; round < 2; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);
            s.topUp(line.buyer(), "5000.00");
            var replies = concurrently(List.of(() -> n.note(line, invoice, "600.00"),
                    () -> s.repay(line.buyer().token(), line.agreementId(), UUID.randomUUID().toString(),
                            Map.of("amount", "600.00", "invoiceIds", List.of(invoice)))));
            int ok = (int) replies.stream().filter(r -> r.status() == 201).count();
            assertThat(ok).describedAs(replies.toString()).isEqualTo(1);
            assertThat(replies.get(0).status()).describedAs("the note is never a 404").isIn(201, 422);
            assertThat(n.outstanding(invoice)).isEqualByComparingTo("400");
            n.assertConsistent(line);
        }
    }

    @Test
    @DisplayName("a note racing a claim confirm: claim then invoice lock order, the confirm is capped by the new outstanding")
    void noteRacingAClaimConfirm() throws Exception {
        for (int round = 0; round < 3; round++) {
            var line = s.creditLine("200000");
            long invoice = s.invoice(line, "10", 100);
            long claimId = e.claim(line.buyer().token(), invoice, "800.00").data().get("id").asLong();
            var replies = concurrently(List.of(() -> n.note(line, invoice, "600.00"),
                    () -> e.confirm(line.seller().token(), claimId, null)));
            assertThat(replies.get(0).status()).describedAs(replies.toString()).isIn(201, 422);
            assertThat(replies.get(1).status()).describedAs(replies.toString()).isIn(200, 409);
            assertThat(n.outstanding(invoice).signum()).isGreaterThanOrEqualTo(0);
            n.assertConsistent(line);
        }
    }
}
