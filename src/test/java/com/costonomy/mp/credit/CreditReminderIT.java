package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.support.ApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual reminders to a restaurant (B11, D-171): what is worth reminding about, the limits (24 hours, 3 in a rolling 7
 * days, 50 per store per India day), the claim skip, quiet hours, idempotency, who may send, and delivery. The time is
 * the test's: {@link CreditClockedIT} replaces the credit clock.
 */
class CreditReminderIT extends CreditClockedIT {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** A line with one ₹6,500 invoice due {@code daysAgo} days ago (grace 5 days): overdue from 6 days past due. */
    private record Fx(Line line, long invoice) {
    }

    private Fx fx(int daysPastDue) throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        setDue(invoice, today().minusDays(daysPastDue), 5);
        return new Fx(line, invoice);
    }

    private static Map<String, Object> body(Long... ids) {
        return ids.length == 0 ? Map.of() : Map.of("invoiceIds", List.of(ids));
    }

    // ── what is worth a reminder ─────────────────────────────────────────

    @Test
    @DisplayName("an overdue invoice: preview shows the exact text and SMS; send delivers that text in-app, push and SMS")
    void overdueReminderIsPreviewedAndDelivered() throws Exception {
        var f = fx(10);
        l.registerDevice(f.line().buyer().token());
        String expected = "ABC Foods: ₹6,500 overdue since " + DAY.format(today().minusDays(10)) + " ("
                + invoiceNumber(f.invoice()) + "). Pay in Mandi or tell them you paid.";

        var preview = preview(f.line().seller().token(), f.line().agreementId(), "");
        assertThat(preview.status()).describedAs(preview.body().toString()).isEqualTo(200);
        assertThat(preview.data().get("canRemind").asBoolean()).isTrue();
        assertThat(preview.data().get("message").asText()).isEqualTo(expected);
        assertThat(preview.data().get("status").asText()).isEqualTo("SENT");
        assertThat(preview.data().get("channels")).extracting(n -> n.asText()).containsExactly("IN_APP", "PUSH", "SMS");
        assertThat(preview.data().at("/invoices/0/included").asBoolean()).isTrue();
        assertThat(reminders(f.line().agreementId())).describedAs("a preview changes nothing").isZero();

        var sent = remind(f.line(), body());
        assertThat(sent.status()).describedAs(sent.body().toString()).isEqualTo(201);
        assertThat(sent.data().get("status").asText()).isEqualTo("SENT");
        assertThat(sent.data().get("kind").asText()).isEqualTo("MANUAL");
        assertThat(sent.data().get("message").asText()).isEqualTo(expected);
        assertThat(sent.data().get("invoiceIds").get(0).asLong()).isEqualTo(f.invoice());
        assertThat(sent.data().get("skipped")).isEmpty();

        long agreement = f.line().agreementId();
        assertThat(events("CreditReminder", agreement)).isEqualTo(1);
        assertThat(l.audits("CREDIT_REMINDER_SENT", agreement)).isEqualTo(1);
        l.relayEvents("CreditReminder", agreement);
        var inbox = l.notificationsFor("CreditReminder", agreement);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).get("body")).isEqualTo(expected);
        assertThat(inbox.get(0).get("title")).isEqualTo("Payment reminder");
        assertThat(l.deliveries("CreditReminder", agreement, "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditReminder", agreement, "SMS")).describedAs("overdue: SMS").isEqualTo(1);
    }

    @Test
    @DisplayName("due within 3 days: allowed, in-app and push only, never SMS; more than 3 days away is not worth a reminder")
    void dueSoonIsRemindedWithoutSms() throws Exception {
        var line = s.creditLine("200000");
        long soon = s.invoice(line, "65", 100);
        setDue(soon, today().plusDays(3), 5);
        l.registerDevice(line.buyer().token());

        var preview = preview(line.seller().token(), line.agreementId(), "");
        assertThat(preview.data().get("canRemind").asBoolean()).isTrue();
        assertThat(preview.data().get("channels")).extracting(n -> n.asText()).containsExactly("IN_APP", "PUSH");
        assertThat(preview.data().get("message").asText()).contains("₹6,500 due on " + DAY.format(today().plusDays(3)));

        assertThat(remind(line, body()).status()).isEqualTo(201);
        l.relayEvents("CreditReminder", line.agreementId());
        assertThat(l.deliveries("CreditReminder", line.agreementId(), "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditReminder", line.agreementId(), "SMS")).describedAs("not overdue: no SMS").isZero();
    }

    @Test
    @DisplayName("a due-today invoice reads 'due today'; one inside its grace period reads 'was due on' and gets no SMS")
    void dueTodayAndGraceWording() throws Exception {
        var line = s.creditLine("200000");
        long today = s.invoice(line, "65", 100);
        setDue(today, today().plusDays(0), 5);
        var msg = preview(line.seller().token(), line.agreementId(), "").data().get("message").asText();
        assertThat(msg).contains("₹6,500 due today");

        setDue(today, today().minusDays(2), 5);
        var grace = preview(line.seller().token(), line.agreementId(), "");
        assertThat(grace.data().get("canRemind").asBoolean()).isTrue();
        assertThat(grace.data().get("message").asText()).contains("₹6,500 was due on " + DAY.format(today().minusDays(2)));
        assertThat(grace.data().get("channels")).extracting(n -> n.asText()).doesNotContain("SMS");
    }

    @Test
    @DisplayName("nothing overdue or due within 3 days: canRemind false NOTHING_DUE, and a send is 422 CREDIT_REMINDER_NOT_NEEDED with no trace")
    void nothingDueCannotBeReminded() throws Exception {
        var line = s.creditLine("200000");
        long later = s.invoice(line, "65", 100);
        setDue(later, today().plusDays(4), 5);

        var preview = preview(line.seller().token(), line.agreementId(), "");
        assertThat(preview.data().get("canRemind").asBoolean()).isFalse();
        assertThat(preview.data().get("reason").asText()).isEqualTo("NOTHING_DUE");

        var refused = remind(line, body());
        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("CREDIT_REMINDER_NOT_NEEDED");
        assertThat(refused.body().at("/error/details/reason").asText()).isEqualTo("NOTHING_DUE");
        assertThat(reminders(line.agreementId())).isZero();
        assertThat(events("CreditReminder", line.agreementId())).isZero();
    }

    // ── the limits ───────────────────────────────────────────────────────

    @Test
    @DisplayName("one manual reminder per line per 24 hours: 429 CREDIT_REMINDER_TOO_SOON with nextAllowedAt; allowed at exactly 24 hours")
    void oncePerTwentyFourHours() throws Exception {
        var f = fx(10);
        setNow(today().atTime(10, 0));
        var first = remind(f.line(), body());
        assertThat(first.status()).isEqualTo(201);
        var firstAt = now();

        advance(Duration.ofHours(23));
        var tooSoon = remind(f.line(), body());
        assertThat(tooSoon.status()).describedAs(tooSoon.body().toString()).isEqualTo(429);
        assertThat(tooSoon.code()).isEqualTo("CREDIT_REMINDER_TOO_SOON");
        assertThat(tooSoon.body().at("/error/details/nextAllowedAt").asText())
                .isEqualTo(firstAt.plus(Duration.ofHours(24)).toString());
        assertThat(reminders(f.line().agreementId())).isEqualTo(1);
        assertThat(events("CreditReminder", f.line().agreementId())).isEqualTo(1);

        var preview = preview(f.line().seller().token(), f.line().agreementId(), "");
        assertThat(preview.data().get("canRemind").asBoolean()).isFalse();
        assertThat(preview.data().get("reason").asText()).isEqualTo("TOO_SOON");
        assertThat(preview.data().get("nextAllowedAt").asText()).isEqualTo(firstAt.plus(Duration.ofHours(24)).toString());

        advance(Duration.ofHours(1));
        assertThat(remind(f.line(), body()).status()).describedAs("exactly 24 hours later").isEqualTo(201);
        assertThat(reminders(f.line().agreementId())).isEqualTo(2);
    }

    @Test
    @DisplayName("three per line in a rolling 7 days: the fourth is 429 CREDIT_REMINDER_LIMIT (WEEK) until the first is 7 days old")
    void threePerRollingWeek() throws Exception {
        var f = fx(10);
        setNow(today().atTime(10, 0));
        var firstAt = now();
        for (int day = 0; day < 3; day++) {
            assertThat(remind(f.line(), body()).status()).describedAs("reminder " + day).isEqualTo(201);
            advance(Duration.ofDays(1));
        }
        var fourth = remind(f.line(), body());
        assertThat(fourth.status()).describedAs(fourth.body().toString()).isEqualTo(429);
        assertThat(fourth.code()).isEqualTo("CREDIT_REMINDER_LIMIT");
        assertThat(fourth.body().at("/error/details/limit").asText()).isEqualTo("WEEK");
        assertThat(fourth.body().at("/error/details/max").asInt()).isEqualTo(3);
        assertThat(fourth.body().at("/error/details/nextAllowedAt").asText())
                .isEqualTo(firstAt.plus(Duration.ofDays(7)).toString());
        var preview = preview(f.line().seller().token(), f.line().agreementId(), "");
        assertThat(preview.data().get("reason").asText()).isEqualTo("WEEK_LIMIT");

        setNow(firstAt.plus(Duration.ofDays(7)));
        assertThat(remind(f.line(), body()).status()).describedAs("the first has left the rolling week").isEqualTo(201);
        assertThat(reminders(f.line().agreementId())).isEqualTo(4);
    }

    @Test
    @DisplayName("50 per store per India day: the 51st is 429 CREDIT_REMINDER_LIMIT (STORE_DAY), nextAllowedAt is the next India midnight; tomorrow it resets")
    void fiftyPerStorePerDay() throws Exception {
        var f = fx(10);
        var other = s.creditLine("100000");
        setNow(today().atTime(14, 0));
        // 50 reminders already sent today by this store's other lines.
        for (int i = 0; i < 50; i++) {
            jdbc.update("insert into credit_reminder (credit_agreement_id, outlet_id, supplier_store_id, kind, channel, "
                    + "status, message, requested_at, sent_at) values (?, ?, ?, 'MANUAL', 'IN_APP', 'SENT', 'x', ?, ?)",
                    other.agreementId(), other.buyer().outletId(), f.line().seller().storeId(),
                    java.sql.Timestamp.from(now()), java.sql.Timestamp.from(now()));
        }
        var refused = remind(f.line(), body());
        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(429);
        assertThat(refused.code()).isEqualTo("CREDIT_REMINDER_LIMIT");
        assertThat(refused.body().at("/error/details/limit").asText()).isEqualTo("STORE_DAY");
        assertThat(refused.body().at("/error/details/max").asInt()).isEqualTo(50);
        assertThat(refused.body().at("/error/details/nextAllowedAt").asText())
                .isEqualTo(today().plusDays(1).atStartOfDay(IST).toInstant().toString());
        assertThat(preview(f.line().seller().token(), f.line().agreementId(), "")
                .data().get("reason").asText()).isEqualTo("STORE_DAY_LIMIT");
        assertThat(reminders(f.line().agreementId())).isZero();

        setNow(today().plusDays(1).atTime(10, 0));
        assertThat(remind(f.line(), body()).status()).describedAs("a new India day").isEqualTo(201);
    }

    // ── a claim that already covers the invoice ──────────────────────────

    @Test
    @DisplayName("an invoice whose outstanding a SUBMITTED claim covers is skipped and the supplier is told; a partial claim still gets reminded")
    void claimCoveredInvoiceIsSkipped() throws Exception {
        var line = s.creditLine("200000");
        long covered = s.invoice(line, "65", 100);
        long open = s.invoice(line, "30", 100);
        long partial = s.invoice(line, "20", 100);
        setDue(covered, today().minusDays(10), 5);
        setDue(open, today().minusDays(9), 5);
        setDue(partial, today().minusDays(8), 5);
        assertThat(e.claim(line.buyer().token(), covered, "6500.00").status()).isEqualTo(201);
        assertThat(e.claim(line.buyer().token(), partial, "500.00").status()).isEqualTo(201);

        var preview = preview(line.seller().token(), line.agreementId(), "");
        assertThat(preview.data().get("canRemind").asBoolean()).isTrue();
        String text = preview.data().get("message").asText();
        assertThat(text).doesNotContain(invoiceNumber(covered)).contains(invoiceNumber(open), invoiceNumber(partial));
        var rows = preview.data().get("invoices");
        for (var row : rows) {
            if (row.get("invoiceId").asLong() == covered) {
                assertThat(row.get("included").asBoolean()).isFalse();
                assertThat(row.get("skipReason").asText()).isEqualTo("CLAIM_SUBMITTED");
            } else {
                assertThat(row.get("included").asBoolean()).isTrue();
            }
        }

        var sent = remind(line, body());
        assertThat(sent.status()).describedAs(sent.body().toString()).isEqualTo(201);
        assertThat(sent.data().get("invoiceIds")).extracting(n -> n.asLong()).containsExactlyInAnyOrder(open, partial);
        assertThat(sent.data().at("/skipped/0/invoiceId").asLong()).isEqualTo(covered);
        assertThat(sent.data().at("/skipped/0/reason").asText()).isEqualTo("CLAIM_SUBMITTED");
        assertThat(sent.data().get("message").asText()).doesNotContain(invoiceNumber(covered));
    }

    @Test
    @DisplayName("every remindable invoice covered by claims: canRemind false CLAIM_COVERED, a send is 422 CLAIM_COVERED and nothing is stored")
    void allCoveredByClaims() throws Exception {
        var f = fx(10);
        assertThat(e.claim(f.line().buyer().token(), f.invoice(), "6500.00").status()).isEqualTo(201);

        var preview = preview(f.line().seller().token(), f.line().agreementId(), "");
        assertThat(preview.data().get("canRemind").asBoolean()).isFalse();
        assertThat(preview.data().get("reason").asText()).isEqualTo("CLAIM_COVERED");

        var refused = remind(f.line(), body(f.invoice()));
        assertThat(refused.status()).describedAs(refused.body().toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("CREDIT_REMINDER_NOT_NEEDED");
        assertThat(refused.body().at("/error/details/reason").asText()).isEqualTo("CLAIM_COVERED");
        assertThat(reminders(f.line().agreementId())).isZero();
        assertThat(events("CreditReminder", f.line().agreementId())).isZero();
    }

    // ── quiet hours ──────────────────────────────────────────────────────

    @Test
    @DisplayName("asked for outside 09:00-20:00 IST: stored QUEUED with sendAt at 09:00, nothing published; 09:00 and 19:59 send at once")
    void quietHoursQueue() throws Exception {
        var f = fx(10);
        long agreement = f.line().agreementId();

        setNow(today().atTime(7, 0));
        var preview = preview(f.line().seller().token(), agreement, "");
        assertThat(preview.data().get("status").asText()).isEqualTo("QUEUED");
        assertThat(preview.data().get("sendAt").asText()).isEqualTo(today().atTime(9, 0).atZone(IST).toInstant().toString());

        var queued = remind(f.line(), body());
        assertThat(queued.status()).describedAs(queued.body().toString()).isEqualTo(201);
        assertThat(queued.data().get("status").asText()).isEqualTo("QUEUED");
        assertThat(queued.data().get("sendAt").asText()).isEqualTo(today().atTime(9, 0).atZone(IST).toInstant().toString());
        assertThat(queued.data().get("sentAt").isNull()).isTrue();
        assertThat(events("CreditReminder", agreement)).describedAs("nothing goes out in the quiet hours").isZero();
        assertThat(reminderRows(agreement)).hasSize(1);
        assertThat(reminderRows(agreement).get(0).get("status")).isEqualTo("QUEUED");

        // A queued reminder counts: asking again at 08:00 is too soon.
        setNow(today().atTime(8, 0));
        assertThat(remind(f.line(), body()).code()).isEqualTo("CREDIT_REMINDER_TOO_SOON");
    }

    @Test
    @DisplayName("20:00 sharp and 21:00 queue for tomorrow 09:00; 09:00 and 19:59 send now")
    void windowBoundaries() throws Exception {
        for (var at : List.of(LocalDateTime.of(today(), java.time.LocalTime.of(20, 0)),
                LocalDateTime.of(today(), java.time.LocalTime.of(21, 0)))) {
            var f = fx(10);
            setNow(at);
            var r = remind(f.line(), body());
            assertThat(r.data().get("status").asText()).describedAs(at.toString()).isEqualTo("QUEUED");
            assertThat(r.data().get("sendAt").asText())
                    .isEqualTo(today().plusDays(1).atTime(9, 0).atZone(IST).toInstant().toString());
        }
        for (var at : List.of(LocalDateTime.of(today(), java.time.LocalTime.of(9, 0)),
                LocalDateTime.of(today(), java.time.LocalTime.of(19, 59)))) {
            var f = fx(10);
            setNow(at);
            assertThat(remind(f.line(), body()).data().get("status").asText()).describedAs(at.toString())
                    .isEqualTo("SENT");
        }
    }

    // ── idempotency ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a retry with the same key replays the first answer: one reminder, one event, one audit row")
    void retryIsIdempotent() throws Exception {
        var f = fx(10);
        String key = UUID.randomUUID().toString();
        var first = remind(f.line().seller().token(), f.line().agreementId(), body(), key);
        var retry = remind(f.line().seller().token(), f.line().agreementId(), body(), key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(retry.status()).describedAs(retry.body().toString()).isEqualTo(201);
        assertThat(retry.data().get("id").asLong()).isEqualTo(first.data().get("id").asLong());
        assertThat(reminders(f.line().agreementId())).isEqualTo(1);
        assertThat(events("CreditReminder", f.line().agreementId())).isEqualTo(1);
        assertThat(l.audits("CREDIT_REMINDER_SENT", f.line().agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is refused (409 IDEMPOTENCY_KEY_REUSE) and sends nothing more")
    void keyReuseWithDifferentBody() throws Exception {
        var f = fx(10);
        String key = UUID.randomUUID().toString();
        assertThat(remind(f.line().seller().token(), f.line().agreementId(), Map.of("note", "first"), key).status())
                .isEqualTo(201);
        var reused = remind(f.line().seller().token(), f.line().agreementId(), Map.of("note", "second"), key);
        assertThat(reused.status()).describedAs(reused.body().toString()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSE");
        assertThat(reminders(f.line().agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("no Idempotency-Key header is a 400 and sends nothing")
    void keyIsRequired() throws Exception {
        var f = fx(10);
        var r = e.call("POST", f.line().seller().token(), "/api/v1/credit/agreements/" + f.line().agreementId()
                + "/reminders", null, Map.of());
        assertThat(r.status()).isEqualTo(400);
        assertThat(reminders(f.line().agreementId())).isZero();
    }

    // ── the request ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the note is part of the exact message, with whitespace collapsed and braces dropped; over 300 characters is a 400")
    void noteRules() throws Exception {
        var f = fx(10);
        var tooLong = remind(f.line(), Map.of("note", "x".repeat(301)));
        assertThat(tooLong.status()).describedAs(tooLong.body().toString()).isEqualTo(400);
        assertThat(reminders(f.line().agreementId())).isZero();

        var sent = remind(f.line(), Map.of("note", "  Please   pay\n today {supplierName} "));
        assertThat(sent.status()).describedAs(sent.body().toString()).isEqualTo(201);
        assertThat(sent.data().get("message").asText()).endsWith("Message from ABC Foods: Please pay today supplierName");
        assertThat(sent.data().get("note").asText()).isEqualTo("Please pay today supplierName");
        assertThat(remind(f.line(), Map.of("note", "x".repeat(300))).code()).isEqualTo("CREDIT_REMINDER_TOO_SOON");
    }

    @Test
    @DisplayName("invoiceIds: only open invoices of this line (400 otherwise); one not due soon is skipped NOT_DUE; the others are reminded")
    void explicitInvoices() throws Exception {
        var f = fx(10);
        long later = s.invoice(f.line(), "10", 100);
        setDue(later, today().plusDays(20), 5);
        var other = fx(10);

        var foreign = remind(f.line(), body(other.invoice()));
        assertThat(foreign.status()).describedAs(foreign.body().toString()).isEqualTo(400);
        assertThat(foreign.code()).isEqualTo("VALIDATION_ERROR");
        jdbc.update("update credit_invoice set status = 'PAID' where id = ?", later);
        assertThat(remind(f.line(), body(later)).status()).describedAs("a settled invoice").isEqualTo(400);
        jdbc.update("update credit_invoice set status = 'ISSUED' where id = ?", later);
        assertThat(reminders(f.line().agreementId())).isZero();

        var sent = remind(f.line(), body(f.invoice(), later));
        assertThat(sent.status()).describedAs(sent.body().toString()).isEqualTo(201);
        assertThat(sent.data().get("invoiceIds")).extracting(n -> n.asLong()).containsExactly(f.invoice());
        assertThat(sent.data().at("/skipped/0/invoiceId").asLong()).isEqualTo(later);
        assertThat(sent.data().at("/skipped/0/reason").asText()).isEqualTo("NOT_DUE");
    }

    @Test
    @DisplayName("history lists the line's reminders newest first, with status, kind and invoices")
    void history() throws Exception {
        var f = fx(10);
        setNow(today().atTime(10, 0));
        var first = remind(f.line(), body());
        advance(Duration.ofHours(25));
        var second = remind(f.line(), body());
        var list = e.call("GET", f.line().seller().token(), "/api/v1/credit/agreements/" + f.line().agreementId()
                + "/reminders?size=1", null, null);
        assertThat(list.status()).describedAs(list.body().toString()).isEqualTo(200);
        assertThat(list.data().get("total").asLong()).isEqualTo(2);
        assertThat(list.data().get("hasNext").asBoolean()).isTrue();
        assertThat(list.data().at("/items/0/id").asLong()).isEqualTo(second.data().get("id").asLong());
        assertThat(list.data().at("/items/0/kind").asText()).isEqualTo("MANUAL");
        assertThat(list.data().at("/items/0/status").asText()).isEqualTo("SENT");
        assertThat(list.data().at("/items/0/invoiceIds/0").asLong()).isEqualTo(f.invoice());
        assertThat(first.data().get("id").asLong()).isNotEqualTo(second.data().get("id").asLong());
    }

    // ── who may ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("owner, admin, finance and store manager may preview; a salesperson, operations, another store and the restaurant get 404")
    void permissionMatrix() throws Exception {
        var f = fx(10);
        long agreement = f.line().agreementId();
        for (String role : List.of("SUP_ADMIN", "SUP_FINANCE_STAFF", "SUP_STORE_MANAGER")) {
            var r = preview(l.staff(f.line(), role), agreement, "");
            assertThat(r.status()).describedAs(role + " " + r.body()).isEqualTo(200);
        }
        assertThat(preview(f.line().seller().token(), agreement, "").status()).isEqualTo(200);

        var before = reminders(agreement);
        var refused = new java.util.LinkedHashMap<String, String>();
        refused.put("salesperson", l.staff(f.line(), "SUP_SALESPERSON"));
        refused.put("operations", l.staff(f.line(), "SUP_OPERATIONS_STAFF"));
        refused.put("restaurant", f.line().buyer().token());
        refused.put("other store", s.newSeller().token());
        for (var entry : refused.entrySet()) {
            var preview = preview(entry.getValue(), agreement, "");
            var send = remind(entry.getValue(), agreement, body(), UUID.randomUUID().toString());
            var history = e.call("GET", entry.getValue(), "/api/v1/credit/agreements/" + agreement + "/reminders",
                    null, null);
            assertThat(preview.status()).describedAs(entry.getKey() + " preview").isEqualTo(404);
            assertThat(send.status()).describedAs(entry.getKey() + " send " + send.body()).isEqualTo(404);
            assertThat(history.status()).describedAs(entry.getKey() + " history").isEqualTo(404);
        }
        assertThat(reminders(agreement)).isEqualTo(before);
        assertThat(events("CreditReminder", agreement)).isZero();

        // The role that may collect can send; the audit row names who.
        String finance = l.staff(f.line(), "SUP_FINANCE_STAFF");
        var sent = remind(finance, agreement, body(), UUID.randomUUID().toString());
        assertThat(sent.status()).describedAs(sent.body().toString()).isEqualTo(201);
        assertThat(sent.data().get("createdBy").asLong()).isEqualTo(e.userId(finance));
        assertThat(l.audits("CREDIT_REMINDER_SENT", agreement)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown line is 404, and a salesperson with no collect right cannot send even to their own store")
    void unknownLine() throws Exception {
        var f = fx(10);
        assertThat(remind(f.line().seller().token(), 999_999_999L, body(), UUID.randomUUID().toString()).status())
                .isEqualTo(404);
    }

    // ── the setting ──────────────────────────────────────────────────────

    @Test
    @DisplayName("credit policy: automatic reminders are on by default, can be turned off and back, and an update that leaves the field out keeps it")
    void policyCarriesTheAutoRemindersSetting() throws Exception {
        var seller = s.newSeller();
        String path = "/api/v1/supplier-stores/" + seller.storeId() + "/credit-policy";
        assertThat(e.call("GET", seller.token(), path, null, null).data().get("autoRemindersEnabled").asBoolean()).isTrue();

        var off = put(seller.token(), path, Map.of("creditEnabled", true, "autoRemindersEnabled", false));
        assertThat(off.status()).describedAs(off.body().toString()).isEqualTo(200);
        assertThat(off.data().get("autoRemindersEnabled").asBoolean()).isFalse();
        assertThat(e.call("GET", seller.token(), path, null, null).data().get("autoRemindersEnabled").asBoolean()).isFalse();

        var kept = put(seller.token(), path, Map.of("creditEnabled", true));
        assertThat(kept.data().get("autoRemindersEnabled").asBoolean()).describedAs("left out keeps it").isFalse();
        var on = put(seller.token(), path, Map.of("creditEnabled", true, "autoRemindersEnabled", true));
        assertThat(on.data().get("autoRemindersEnabled").asBoolean()).isTrue();
    }
}
