package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the credit note, write-off and cancel suites share (B7, B8): the verbs through the public endpoints, and
 * readers for the rows a refusal must leave alone. Beside {@link CreditEdgeSupport}, which it does not edit.
 */
final class CreditNoteSupport {

    final CreditEdgeSupport e;
    private final JdbcTemplate jdbc;

    CreditNoteSupport(CreditEdgeSupport e, JdbcTemplate jdbc) {
        this.e = e;
        this.jdbc = jdbc;
    }

    // ── verbs ────────────────────────────────────────────────────────────

    Reply note(String token, long invoice, String amount, String reason, String key, String text) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("amount", amount);
        body.put("reasonCode", reason);
        if (text != null) {
            body.put("note", text);
        }
        return e.call("POST", token, "/api/v1/credit/invoices/" + invoice + "/credit-notes", key, body);
    }

    Reply note(Line line, long invoice, String amount) throws Exception {
        return note(line.seller().token(), invoice, amount, "SHORT_SUPPLY", UUID.randomUUID().toString(), "Two crates short");
    }

    /** A note that must succeed. */
    Reply noteOk(Line line, long invoice, String amount) throws Exception {
        var reply = note(line, invoice, amount);
        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(201);
        return reply;
    }

    Reply writeOffInvoice(String token, long invoice, Map<String, Object> body, String key) throws Exception {
        return e.call("POST", token, "/api/v1/credit/invoices/" + invoice + "/write-off", key, body);
    }

    Reply writeOffLine(String token, long agreementId, Map<String, Object> body, String key) throws Exception {
        return e.call("POST", token, "/api/v1/credit/agreements/" + agreementId + "/write-off", key, body);
    }

    static Map<String, Object> writeOff(String amount, String reason, Boolean keepLineOpen) {
        var body = new HashMap<String, Object>();
        if (amount != null) {
            body.put("amount", amount);
        }
        body.put("reason", reason);
        if (keepLineOpen != null) {
            body.put("keepLineOpen", keepLineOpen);
        }
        return body;
    }

    Reply statement(String token, Line line) throws Exception {
        return e.call("GET", token, "/api/v1/credit/agreements/" + line.agreementId() + "/statement", null, null);
    }

    Reply notes(String token, long agreementId, String query) throws Exception {
        return e.call("GET", token, "/api/v1/credit/agreements/" + agreementId + "/credit-notes" + query, null, null);
    }

    Reply invoiceRead(String token, long invoice) throws Exception {
        return e.call("GET", token, "/api/v1/credit/invoices/" + invoice, null, null);
    }

    // ── readers ──────────────────────────────────────────────────────────

    Map<String, Object> inv(long invoice) {
        return jdbc.queryForMap("select status, amount, paid_amount, credited_amount, settled_at from credit_invoice where id = ?",
                invoice);
    }

    String status(long invoice) {
        return (String) inv(invoice).get("status");
    }

    BigDecimal outstanding(long invoice) {
        return jdbc.queryForObject("select amount - paid_amount - credited_amount from credit_invoice where id = ?",
                BigDecimal.class, invoice);
    }

    BigDecimal credited(long invoice) {
        return jdbc.queryForObject("select credited_amount from credit_invoice where id = ?", BigDecimal.class, invoice);
    }

    BigDecimal utilized(Line line) {
        return jdbc.queryForObject("select utilized_amount from credit_agreement where id = ?", BigDecimal.class,
                line.agreementId());
    }

    long notesOf(long invoice) {
        return e.count("select count(*) from credit_invoice_note where credit_invoice_id = ?", invoice);
    }

    long notesOfLine(Line line) {
        return e.count("select count(*) from credit_invoice_note where credit_agreement_id = ?", line.agreementId());
    }

    long refundsOf(long invoice) {
        return e.count("select count(*) from credit_refund_due where credit_invoice_id = ?", invoice);
    }

    long audits(String action, long entityId) {
        return e.count("select count(*) from audit_log where action = ? and entity_id = ?", action, entityId);
    }

    long events(String type, long aggregateId) {
        return e.count("select count(*) from outbox_event where event_type = ? and aggregate_id = ?", type, aggregateId);
    }

    long ledgerRows(Line line, String type) {
        return e.count("select count(*) from credit_transaction where credit_agreement_id = ? and transaction_type = ?",
                line.agreementId(), type);
    }

    long payouts(Line line) {
        return e.count("select count(*) from credit_repayment_payout p join credit_repayment r "
                + "on r.id = p.credit_repayment_id where r.outlet_id = ?", line.buyer().outletId());
    }

    /**
     * The invariant the whole feature protects: what the line says is drawn equals what its open invoices say is
     * owed, and no invoice is over-reduced or negative.
     */
    void assertConsistent(Line line) {
        BigDecimal owed = jdbc.queryForObject("select coalesce(sum(amount - paid_amount - credited_amount), 0) "
                + "from credit_invoice where credit_agreement_id = ? and status not in ('PAID', 'WRITTEN_OFF')",
                BigDecimal.class, line.agreementId());
        assertThat(utilized(line)).describedAs("utilized equals what open invoices owe").isEqualByComparingTo(owed);
        assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ? "
                + "and (amount - paid_amount - credited_amount < 0 or credited_amount < 0)", line.agreementId())).isZero();
        assertThat(e.count("select count(*) from credit_invoice where credit_agreement_id = ? "
                + "and ((status in ('PAID', 'WRITTEN_OFF')) <> (amount - paid_amount - credited_amount = 0))",
                line.agreementId())).describedAs("settled exactly when nothing is owed").isZero();
    }
}
