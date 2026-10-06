package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.CreditWalletSupport.Reply;
import com.costonomy.mp.credit.service.CreditJobs;
import com.costonomy.mp.notification.NotificationRelayAccess;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giving a restaurant longer to pay (B10, D-138): later only, at most 60 days past the original due date, never on a
 * settled invoice. An overdue invoice that is no longer late goes back to open, a suspension the sweep imposed for it
 * lifts, and every extension is kept and shown.
 */
@AutoConfigureMockMvc
class CreditExtendDueIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CreditJobs creditJobs;
    @Autowired private NotificationRelayAccess relay;

    private CreditEdgeSupport e;
    private CreditWalletSupport s;
    private CreditLifecycleSupport l;

    @BeforeEach
    void setUp() {
        e = new CreditEdgeSupport(mvc, json, jdbc);
        s = e.s;
        l = new CreditLifecycleSupport(e, jdbc, relay);
    }

    private LocalDate today() {
        return CreditEdgeSupport.today();
    }

    private Reply extend(String token, long invoice, LocalDate to, String key) throws Exception {
        return e.call("POST", token, "/api/v1/credit/invoices/" + invoice + "/extend-due", key,
                Map.of("newDueDate", to.toString(), "reason", "Restaurant asked for a week"));
    }

    private Reply extend(Line line, long invoice, LocalDate to) throws Exception {
        return extend(line.seller().token(), invoice, to, UUID.randomUUID().toString());
    }

    private Map<String, Object> row(long invoice) {
        return jdbc.queryForMap("select status, due_date, overdue_after, paid_amount from credit_invoice where id = ?",
                invoice);
    }

    private LocalDate dueDate(long invoice) {
        return LocalDate.parse(String.valueOf(row(invoice).get("due_date")));
    }

    private LocalDate overdueAfter(long invoice) {
        return LocalDate.parse(String.valueOf(row(invoice).get("overdue_after")));
    }

    private String status(long invoice) {
        return (String) row(invoice).get("status");
    }

    private long extensions(long invoice) {
        return e.count("select count(*) from credit_due_extension where credit_invoice_id = ?", invoice);
    }

    /** An invoice of ₹40,000 now OVERDUE: due 15 days ago, grace 5 days, marked by the sweep. */
    private long overdueInvoice(Line line) throws Exception {
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(status(invoice)).isEqualTo("OVERDUE");
        assertThat(dueDate(invoice)).isEqualTo(today().minusDays(15));
        assertThat(overdueAfter(invoice)).isEqualTo(today().minusDays(10));
        return invoice;
    }

    @Test
    @DisplayName("later only: the same date or an earlier one is refused (400) and leaves no trace")
    void laterOnly() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        LocalDate due = dueDate(invoice);
        var before = s.snapshot(line);

        for (LocalDate bad : new LocalDate[]{due, due.minusDays(1), today().minusDays(40)}) {
            var reply = extend(line, invoice, bad);
            assertThat(reply.status()).describedAs(bad + " " + reply.body()).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(extensions(invoice)).isZero();
        assertThat(l.audits("CREDIT_DUE_EXTENDED", invoice)).isZero();
        assertThat(l.events("CreditDueDateExtended", invoice)).isZero();
        assertThat(dueDate(invoice)).isEqualTo(due);
        assertThat(s.snapshot(line)).isEqualTo(before);

        var ok = extend(line, invoice, due.plusDays(1));
        assertThat(ok.status()).describedAs(ok.body().toString()).isEqualTo(200);
        assertThat(dueDate(invoice)).isEqualTo(due.plusDays(1));
    }

    @Test
    @DisplayName("at most 60 days beyond the ORIGINAL due date, however many extensions it takes")
    void cappedAtSixtyDaysFromTheOriginal() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        LocalDate original = dueDate(invoice);

        var tooFar = extend(line, invoice, original.plusDays(61));
        assertThat(tooFar.status()).describedAs(tooFar.body().toString()).isEqualTo(400);
        assertThat(tooFar.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(extensions(invoice)).isZero();

        assertThat(extend(line, invoice, original.plusDays(30)).status()).isEqualTo(200);
        var second = extend(line, invoice, original.plusDays(60));
        assertThat(second.status()).describedAs(second.body().toString()).isEqualTo(200);
        assertThat(dueDate(invoice)).isEqualTo(original.plusDays(60));

        var beyond = extend(line, invoice, original.plusDays(61));
        assertThat(beyond.status()).describedAs("60 days from the original, not from the latest").isEqualTo(400);
        assertThat(dueDate(invoice)).isEqualTo(original.plusDays(60));
        assertThat(extensions(invoice)).isEqualTo(2);
    }

    @Test
    @DisplayName("a settled invoice (PAID, WRITTEN_OFF) cannot be extended: 409")
    void settledInvoicesAreRefused() throws Exception {
        var line = s.creditLine("200000");
        long paid = s.invoice(line, "10", 10);
        assertThat(s.recordPayment(line.seller(), paid, "100.00").status()).isEqualTo(200);
        long writtenOff = s.invoice(line, "10", 10);
        jdbc.update("update credit_invoice set status = 'WRITTEN_OFF' where id = ?", writtenOff);

        for (long invoice : new long[]{paid, writtenOff}) {
            var reply = extend(line, invoice, dueDate(invoice).plusDays(5));
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(409);
            assertThat(reply.code()).isEqualTo("INVALID_STATE_TRANSITION");
            assertThat(extensions(invoice)).isZero();
        }
    }

    @Test
    @DisplayName("an OVERDUE invoice given a date that is no longer late goes back to ISSUED, with its grace kept; no money moves")
    void overdueGoesBackToIssued() throws Exception {
        var line = s.creditLine("200000");
        long invoice = overdueInvoice(line);
        var before = e.agreementRow(line.agreementId());
        LocalDate to = today().plusDays(20);

        var reply = extend(line, invoice, to);

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/invoice/status").asText()).isEqualTo("ISSUED");
        assertThat(reply.data().at("/invoice/dueDate").asText()).isEqualTo(to.toString());
        assertThat(reply.data().at("/invoice/overdueAfter").asText()).describedAs("the 5 grace days travel with it")
                .isEqualTo(to.plusDays(5).toString());
        assertThat(reply.data().at("/invoice/dueState").asText()).isEqualTo("DUE_LATER");
        assertThat(reply.data().at("/invoice/daysToDue").asInt()).isEqualTo(20);
        assertThat(reply.data().at("/extension/oldDueDate").asText()).isEqualTo(today().minusDays(15).toString());
        assertThat(reply.data().at("/extension/newDueDate").asText()).isEqualTo(to.toString());
        assertThat(reply.data().at("/extension/reason").asText()).isEqualTo("Restaurant asked for a week");
        assertThat(reply.data().at("/agreementStatus").asText()).isEqualTo("ACTIVE");
        assertThat(status(invoice)).isEqualTo("ISSUED");
        assertThat(e.agreementRow(line.agreementId()).get("utilized_amount")).isEqualTo(before.get("utilized_amount"));
        assertThat(e.count("select count(*) from credit_payment where credit_invoice_id = ?", invoice)).isZero();
        assertThat(l.audits("CREDIT_DUE_EXTENDED", invoice)).isEqualTo(1);

        // The sweep must not mark it overdue again while the new date stands.
        creditJobs.sweepOverdue();
        assertThat(status(invoice)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("a part-paid OVERDUE invoice goes back to PARTIALLY_PAID")
    void partPaidGoesBackToPartiallyPaid() throws Exception {
        var line = s.creditLine("200000");
        long invoice = overdueInvoice(line);
        assertThat(s.recordPayment(line.seller(), invoice, "1000.00").status()).isEqualTo(200);
        assertThat(status(invoice)).describedAs("a part payment does not cure lateness").isEqualTo("OVERDUE");

        assertThat(extend(line, invoice, today().plusDays(3)).status()).isEqualTo(200);

        assertThat(status(invoice)).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("IST boundary: overdue ends when overdue-after is today (India), not before: grace 5, new due today-5 reverts; today-6 stays OVERDUE")
    void overdueBoundaryIsTheIndiaDay() throws Exception {
        var line = s.creditLine("200000");
        long stays = overdueInvoice(line);
        var still = extend(line, stays, today().minusDays(6));
        assertThat(still.status()).describedAs(still.body().toString()).isEqualTo(200);
        assertThat(status(stays)).describedAs("overdue-after is yesterday: still late").isEqualTo("OVERDUE");
        assertThat(still.data().at("/invoice/dueState").asText()).isEqualTo("OVERDUE");
        assertThat(overdueAfter(stays)).isEqualTo(today().minusDays(1));

        long reverts = overdueInvoice(line);
        var back = extend(line, reverts, today().minusDays(5));
        assertThat(back.status()).describedAs(back.body().toString()).isEqualTo(200);
        assertThat(overdueAfter(reverts)).isEqualTo(today());
        assertThat(status(reverts)).describedAs("overdue-after is today: not late until tomorrow").isEqualTo("ISSUED");
        assertThat(back.data().at("/invoice/dueState").asText()).isEqualTo("IN_GRACE");
    }

    @Test
    @DisplayName("an extension that clears what the sweep suspended for lifts the suspension; a supplier's own suspension stays")
    void reinstateHook() throws Exception {
        var line = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long invoice = s.invoice(line, "400", 100);
        s.age(invoice, 10);
        creditJobs.sweepOverdue();
        assertThat(l.status(line.agreementId())).isEqualTo("SUSPENDED");
        assertThat(l.column(line.agreementId(), "suspension_source")).isEqualTo("SYSTEM");

        var reply = extend(line, invoice, today().plusDays(10));

        assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().at("/agreementStatus").asText()).isEqualTo("ACTIVE");
        assertThat(l.status(line.agreementId())).isEqualTo("ACTIVE");
        assertThat(l.events("CreditReinstated", line.agreementId())).isEqualTo(1);

        var own = s.creditLine("200000", Map.of("maxOverdueAmount", "10000"));
        long ownInvoice = s.invoice(own, "400", 100);
        s.age(ownInvoice, 10);
        creditJobs.sweepOverdue();
        assertThat(l.status(own.agreementId())).isEqualTo("SUSPENDED");
        jdbc.update("update credit_agreement set status = 'SUSPENDED', suspension_source = 'SUPPLIER' where id = ?",
                own.agreementId());
        assertThat(extend(own, ownInvoice, today().plusDays(10)).status()).isEqualTo(200);
        assertThat(l.status(own.agreementId())).describedAs("a supplier's suspension is the supplier's to lift")
                .isEqualTo("SUSPENDED");
    }

    @Test
    @DisplayName("a retry with the same Idempotency-Key replays: one extension, one event; the same key for another date is refused")
    void idempotent() throws Exception {
        var line = s.creditLine("200000");
        long invoice = overdueInvoice(line);
        String key = UUID.randomUUID().toString();
        LocalDate to = today().plusDays(7);

        var first = extend(line.seller().token(), invoice, to, key);
        var again = extend(line.seller().token(), invoice, to, key);

        assertThat(first.status()).isEqualTo(200);
        assertThat(again.status()).describedAs(again.body().toString()).isEqualTo(200);
        assertThat(again.data()).isEqualTo(first.data());
        assertThat(extensions(invoice)).isEqualTo(1);
        assertThat(l.audits("CREDIT_DUE_EXTENDED", invoice)).isEqualTo(1);
        assertThat(l.events("CreditDueDateExtended", invoice)).isEqualTo(1);

        var other = extend(line.seller().token(), invoice, to.plusDays(1), key);
        assertThat(other.status()).describedAs(other.body().toString()).isEqualTo(409);
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(dueDate(invoice)).isEqualTo(to);

        var keyless = e.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/extend-due", null,
                Map.of("newDueDate", to.plusDays(2).toString(), "reason", "Another"));
        assertThat(keyless.status()).describedAs("the key is required").isEqualTo(400);
        assertThat(extensions(invoice)).isEqualTo(1);
    }

    @Test
    @DisplayName("a reason is required, and so is a date")
    void validation() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        String token = line.seller().token();
        String path = "/api/v1/credit/invoices/" + invoice + "/extend-due";
        assertThat(e.call("POST", token, path, UUID.randomUUID().toString(),
                Map.of("newDueDate", dueDate(invoice).plusDays(1).toString())).status()).isEqualTo(400);
        assertThat(e.call("POST", token, path, UUID.randomUUID().toString(),
                Map.of("newDueDate", dueDate(invoice).plusDays(1).toString(), "reason", " ")).status()).isEqualTo(400);
        assertThat(e.call("POST", token, path, UUID.randomUUID().toString(), Map.of("reason", "Because")).status())
                .isEqualTo(400);
        assertThat(extensions(invoice)).isZero();
    }

    @Test
    @DisplayName("who may extend: owner, admin, finance (CREDIT_MODIFY) and store manager (CREDIT_COLLECT); a salesperson, another supplier and the restaurant get 404")
    void permissions() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        var refused = new java.util.LinkedHashMap<String, String>();
        refused.put("salesperson", l.staff(line, "SUP_SALESPERSON"));
        refused.put("operations", l.staff(line, "SUP_OPERATIONS_STAFF"));
        refused.put("another store's owner", s.creditLine("200000").seller().token());
        refused.put("the restaurant", line.buyer().token());
        LocalDate to = dueDate(invoice).plusDays(1);
        for (var who : refused.entrySet()) {
            var reply = extend(who.getValue(), invoice, to, UUID.randomUUID().toString());
            assertThat(reply.status()).describedAs(who.getKey() + " " + reply.body()).isEqualTo(404);
        }
        assertThat(extensions(invoice)).isZero();
        assertThat(dueDate(invoice)).isEqualTo(to.minusDays(1));

        int n = 0;
        for (String token : new String[]{line.seller().token(), l.staff(line, "SUP_ADMIN"),
                l.staff(line, "SUP_FINANCE_STAFF"), l.staff(line, "SUP_STORE_MANAGER")}) {
            var reply = extend(token, invoice, to.plusDays(n++), UUID.randomUUID().toString());
            assertThat(reply.status()).describedAs(reply.body().toString()).isEqualTo(200);
        }
        assertThat(extensions(invoice)).isEqualTo(4);
        assertThat(extend(line.seller().token(), 999999999L, to, UUID.randomUUID().toString()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("the invoice detail lists the extensions newest first, to both sides")
    void detailShowsTheHistory() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "100", 10);
        LocalDate due = dueDate(invoice);
        var detail = e.call("GET", line.buyer().token(), "/api/v1/credit/invoices/" + invoice, null, null);
        assertThat(detail.data().at("/extensions").isArray()).isTrue();
        assertThat(detail.data().at("/extensions").size()).isZero();

        assertThat(extend(line, invoice, due.plusDays(5)).status()).isEqualTo(200);
        assertThat(extend(line, invoice, due.plusDays(12)).status()).isEqualTo(200);

        for (String token : new String[]{line.buyer().token(), line.seller().token()}) {
            var seen = e.call("GET", token, "/api/v1/credit/invoices/" + invoice, null, null);
            var list = seen.data().at("/extensions");
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.get(0).at("/oldDueDate").asText()).isEqualTo(due.plusDays(5).toString());
            assertThat(list.get(0).at("/newDueDate").asText()).isEqualTo(due.plusDays(12).toString());
            assertThat(list.get(1).at("/oldDueDate").asText()).isEqualTo(due.toString());
            assertThat(list.get(1).at("/newDueDate").asText()).isEqualTo(due.plusDays(5).toString());
            assertThat(list.get(0).at("/reason").asText()).isEqualTo("Restaurant asked for a week");
            assertThat(list.get(0).at("/extendedBy").asLong()).isPositive();
            assertThat(list.get(0).at("/createdAt").asText()).isNotBlank();
            assertThat(seen.data().at("/dueDate").asText()).isEqualTo(due.plusDays(12).toString());
        }
    }

    @Test
    @DisplayName("the restaurant is told, in-app and push, with the new date; never SMS")
    void restaurantIsNotified() throws Exception {
        var line = s.creditLine("200000");
        l.registerDevice(line.buyer().token());
        long invoice = overdueInvoice(line);
        String number = jdbc.queryForObject("select invoice_number from credit_invoice where id = ?", String.class,
                invoice);
        LocalDate to = today().plusDays(9);
        assertThat(extend(line, invoice, to).status()).isEqualTo(200);

        var payload = json.readTree(l.payload("CreditDueDateExtended", invoice));
        assertThat(payload.at("/outletId").asLong()).isEqualTo(line.buyer().outletId());
        assertThat(payload.at("/supplierStoreId").asLong()).isEqualTo(line.seller().storeId());
        assertThat(payload.at("/newDueDate").asText()).isEqualTo(to.toString());
        l.relayEvents("CreditDueDateExtended", invoice);

        var sent = l.notificationsFor("CreditDueDateExtended", invoice);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("audience")).isEqualTo("OUTLET");
        assertThat((String) sent.get(0).get("body")).contains(number).contains("ABC Foods");
        assertThat(l.deliveries("CreditDueDateExtended", invoice, "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditDueDateExtended", invoice, "SMS")).isZero();
    }
}
