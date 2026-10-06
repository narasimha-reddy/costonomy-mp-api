package com.costonomy.mp.credit;

import com.costonomy.mp.credit.CreditWalletSupport.Line;
import com.costonomy.mp.credit.service.CreditReminderJobs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reminder job (B11, D-142): automatic reminders per kind exactly once per India day, the setting that turns them
 * off, the weekly cap of four, who is never reminded, SMS only for a manual overdue reminder, and the release of
 * reminders that waited for 09:00.
 */
class CreditReminderJobIT extends CreditClockedIT {

    @Autowired CreditReminderJobs jobs;

    private record Fx(Line line, long invoice) {
    }

    /** A line with one ₹6,500 invoice due on {@code due}, grace 5 days, and a device for the restaurant. */
    private Fx line(LocalDate due) throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        setDue(invoice, due, 5);
        l.registerDevice(line.buyer().token());
        return new Fx(line, invoice);
    }

    private void runAt(int hour, int minute) {
        setNow(nowIst().toLocalDate().atTime(hour, minute));
        jobs.runReminders();
    }

    private long relayed(Fx f, String channel) {
        l.relayEvents("CreditReminder", f.line().agreementId());
        return l.deliveries("CreditReminder", f.line().agreementId(), channel);
    }

    @Test
    @DisplayName("AUTO_T3: three days before the due date, in-app only, once; not two days before")
    void threeDaysBefore() throws Exception {
        var f = line(today().plusDays(3));
        long a = f.line().agreementId();

        runAt(10, 0);
        runAt(10, 5);
        runAt(15, 0);
        assertThat(reminders(a, "AUTO_T3")).describedAs("once per invoice per day").isEqualTo(1);
        assertThat(reminders(a)).isEqualTo(1);
        assertThat(l.audits("CREDIT_REMINDER_SENT", a)).isEqualTo(1);
        assertThat(events("CreditReminder", a)).isEqualTo(1);
        l.relayEvents("CreditReminder", a);
        assertThat(l.notificationsFor("CreditReminder", a)).hasSize(1);
        assertThat(l.notificationsFor("CreditReminder", a).get(0).get("body").toString())
                .contains("₹6,500 due on").contains(invoiceNumber(f.invoice()));
        assertThat(l.deliveries("CreditReminder", a, "PUSH")).describedAs("T-3 is in-app only").isZero();
        assertThat(l.deliveries("CreditReminder", a, "SMS")).isZero();

        // Tomorrow it is two days away: no second T-3.
        setNow(today().plusDays(1).atTime(10, 0));
        jobs.runReminders();
        assertThat(reminders(a)).isEqualTo(1);
    }

    @Test
    @DisplayName("AUTO_DUE: on the due date from 10:00 IST, in-app and push, once; not before 10:00 and not after 20:00")
    void onTheDueDate() throws Exception {
        var f = line(today());
        long a = f.line().agreementId();

        runAt(9, 59);
        assertThat(reminders(a)).describedAs("before 10:00").isZero();
        runAt(20, 0);
        assertThat(reminders(a)).describedAs("the window is closed").isZero();
        runAt(10, 0);
        runAt(11, 0);
        assertThat(reminders(a, "AUTO_DUE")).isEqualTo(1);
        assertThat(reminders(a)).isEqualTo(1);
        assertThat(relayed(f, "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditReminder", a, "SMS")).isZero();
        assertThat(jdbc.queryForObject("select channel from credit_reminder where credit_agreement_id = ?",
                String.class, a)).isEqualTo("IN_APP,PUSH");
    }

    @Test
    @DisplayName("AUTO_WEEKLY: seven days after the grace ended, then every seven days, push, at most four, never SMS")
    void weeklyWhileOverdueUpToFour() throws Exception {
        var f = line(today().minusDays(12));   // overdue-after = 7 days ago
        long a = f.line().agreementId();

        runAt(10, 0);
        runAt(12, 0);
        assertThat(reminders(a, "AUTO_WEEKLY")).describedAs("first, and once that day").isEqualTo(1);
        for (int week = 1; week <= 6; week++) {
            // A day short of a week later nothing is sent.
            setNow(today().plusDays(7L * week - 1).atTime(10, 0));
            jobs.runReminders();
            long expected = Math.min(week, 4);
            assertThat(reminders(a, "AUTO_WEEKLY")).describedAs("day before week " + week).isEqualTo(expected);
            setNow(today().plusDays(7L * week).atTime(10, 0));
            jobs.runReminders();
            assertThat(reminders(a, "AUTO_WEEKLY")).describedAs("week " + week).isEqualTo(Math.min(week + 1, 4));
        }
        assertThat(reminders(a)).describedAs("four weekly reminders in all").isEqualTo(4);
        assertThat(relayed(f, "PUSH")).isEqualTo(4);
        assertThat(l.deliveries("CreditReminder", a, "SMS")).describedAs("automatic reminders never SMS").isZero();
    }

    @Test
    @DisplayName("the store setting turns automatic reminders off and on; manual reminders are not affected")
    void autoRemindersSetting() throws Exception {
        var f = line(today());
        long a = f.line().agreementId();
        String path = "/api/v1/supplier-stores/" + f.line().seller().storeId() + "/credit-policy";
        assertThat(put(f.line().seller().token(), path, Map.of("creditEnabled", true, "autoRemindersEnabled", false))
                .status()).isEqualTo(200);

        runAt(10, 0);
        assertThat(reminders(a)).describedAs("switched off").isZero();
        assertThat(remind(f.line(), Map.of()).status()).describedAs("manual still works").isEqualTo(201);
        assertThat(reminders(a, "MANUAL")).isEqualTo(1);

        assertThat(put(f.line().seller().token(), path, Map.of("creditEnabled", true, "autoRemindersEnabled", true))
                .status()).isEqualTo(200);
        runAt(10, 30);
        assertThat(reminders(a, "AUTO_DUE")).describedAs("switched back on").isEqualTo(1);
    }

    @Test
    @DisplayName("a store with no policy row gets automatic reminders (on by default)")
    void noPolicyRowMeansOn() throws Exception {
        var f = line(today());
        jdbc.update("delete from supplier_credit_policy where supplier_store_id = ?", f.line().seller().storeId());
        runAt(10, 0);
        assertThat(reminders(f.line().agreementId(), "AUTO_DUE")).isEqualTo(1);
    }

    @Test
    @DisplayName("never for a claim-covered, PAID or WRITTEN_OFF invoice, nor for a line that is not ACTIVE or SUSPENDED")
    void whoIsNeverReminded() throws Exception {
        // Claim covers the invoice.
        var covered = line(today());
        assertThat(e.claim(covered.line().buyer().token(), covered.invoice(), "6500.00").status()).isEqualTo(201);
        // Paid, written off.
        var paid = line(today());
        assertThat(s.recordPayment(paid.line().seller(), paid.invoice(), "6500.00").status()).isEqualTo(200);
        var writtenOff = line(today());
        jdbc.update("update credit_invoice set status = 'WRITTEN_OFF' where id = ?", writtenOff.invoice());
        // Line closed / expired.
        var closed = line(today());
        jdbc.update("update credit_agreement set status = 'CLOSED' where id = ?", closed.line().agreementId());
        var expired = line(today());
        jdbc.update("update credit_agreement set status = 'EXPIRED' where id = ?", expired.line().agreementId());
        // Suspended lines are still reminded: the debt remains.
        var suspended = line(today());
        jdbc.update("update credit_agreement set status = 'SUSPENDED' where id = ?", suspended.line().agreementId());
        // A partial claim does not cover it.
        var partial = line(today());
        assertThat(e.claim(partial.line().buyer().token(), partial.invoice(), "100.00").status()).isEqualTo(201);

        runAt(10, 0);

        for (var f : List.of(covered, paid, writtenOff, closed, expired)) {
            assertThat(reminders(f.line().agreementId())).describedAs("line " + f.line().agreementId()).isZero();
        }
        assertThat(reminders(suspended.line().agreementId(), "AUTO_DUE")).isEqualTo(1);
        assertThat(reminders(partial.line().agreementId(), "AUTO_DUE")).isEqualTo(1);
    }

    @Test
    @DisplayName("an invoice paid later the same day is not reminded the next morning; one invoice reminded once even with two lines of kinds")
    void groupsInvoicesIntoOneReminderPerKind() throws Exception {
        var f = line(today());
        long second = s.invoice(f.line(), "10", 100);
        setDue(second, today(), 5);
        long other = s.invoice(f.line(), "20", 100);
        setDue(other, today().plusDays(3), 5);
        runAt(10, 0);
        long a = f.line().agreementId();
        assertThat(reminders(a, "AUTO_DUE")).describedAs("one reminder for both due-today invoices").isEqualTo(1);
        assertThat(reminders(a, "AUTO_T3")).isEqualTo(1);
        assertThat(e.count("select count(*) from credit_reminder_invoice where credit_invoice_id in (?, ?) "
                + "and kind = 'AUTO_DUE'", f.invoice(), second)).isEqualTo(2);
    }

    @Test
    @DisplayName("two nodes running the job at once send each automatic reminder once (unique invoice, kind, day)")
    void twoNodesSendOnce() throws Exception {
        var f = line(today());
        setNow(today().atTime(10, 0));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            List<Future<?>> runs = List.of(
                    pool.submit(() -> { start.await(); jobs.runReminders(); return null; }),
                    pool.submit(() -> { start.await(); jobs.runReminders(); return null; }));
            start.countDown();
            for (var run : runs) {
                run.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(reminders(f.line().agreementId(), "AUTO_DUE")).isEqualTo(1);
        assertThat(events("CreditReminder", f.line().agreementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the database itself refuses a second automatic reminder for one invoice, kind and day")
    void uniqueKeyBacksTheRule() throws Exception {
        var f = line(today());
        runAt(10, 0);
        long reminder = e.count("select id from credit_reminder where credit_agreement_id = ?", f.line().agreementId());
        assertThatThrownBy(() -> jdbc.update("insert into credit_reminder_invoice (credit_reminder_id, credit_invoice_id, "
                + "kind, remind_date, auto_key) values (?, ?, 'AUTO_DUE', ?, ?)", reminder, f.invoice(), today(),
                "AUTO_DUE:" + today())).isInstanceOf(DuplicateKeyException.class);
        // A manual reminder (no key) is not limited by it.
        jdbc.update("insert into credit_reminder_invoice (credit_reminder_id, credit_invoice_id, kind, remind_date) "
                + "values (?, ?, 'MANUAL', ?)", reminder, f.invoice(), today());
        jdbc.update("insert into credit_reminder_invoice (credit_reminder_id, credit_invoice_id, kind, remind_date) "
                + "values (?, ?, 'MANUAL', ?)", reminder, f.invoice(), today());
    }

    // ── queued manual reminders ──────────────────────────────────────────

    @Test
    @DisplayName("a reminder queued overnight is sent by the job at 09:00, once, with the push and SMS an overdue one gets")
    void queuedReminderIsReleasedAtNine() throws Exception {
        var f = line(today().minusDays(10));
        long a = f.line().agreementId();
        setNow(today().atTime(7, 0));
        assertThat(remind(f.line(), Map.of()).data().get("status").asText()).isEqualTo("QUEUED");

        runAt(8, 59);
        assertThat(events("CreditReminder", a)).describedAs("window not open").isZero();
        runAt(9, 0);
        assertThat(events("CreditReminder", a)).isEqualTo(1);
        var row = reminderRows(a).get(0);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("sent_at")).isNotNull();
        runAt(9, 30);
        runAt(12, 0);
        assertThat(events("CreditReminder", a)).describedAs("released once").isEqualTo(1);
        assertThat(l.audits("CREDIT_REMINDER_SENT", a)).isEqualTo(1);
        assertThat(relayed(f, "PUSH")).isEqualTo(1);
        assertThat(l.deliveries("CreditReminder", a, "SMS")).isEqualTo(1);
        // No automatic weekly reminder piled onto the same day: the invoice is only 5 days overdue.
        assertThat(reminders(a, "MANUAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("a queued reminder whose invoice was paid overnight is CANCELLED, not sent")
    void queuedReminderForAPaidInvoiceIsCancelled() throws Exception {
        var f = line(today().minusDays(10));
        long a = f.line().agreementId();
        setNow(today().atTime(21, 0));
        assertThat(remind(f.line(), Map.of()).data().get("status").asText()).isEqualTo("QUEUED");
        assertThat(s.recordPayment(f.line().seller(), f.invoice(), "6500.00").status()).isEqualTo(200);

        setNow(today().plusDays(1).atTime(9, 0));
        jobs.runReminders();
        assertThat(events("CreditReminder", a)).isZero();
        assertThat(reminderRows(a).get(0).get("status")).isEqualTo("CANCELLED");
        assertThat(l.audits("CREDIT_REMINDER_CANCELLED", a)).isEqualTo(1);
    }

    @Test
    @DisplayName("a queued reminder whose invoice was claimed as paid overnight is CANCELLED too")
    void queuedReminderForAClaimedInvoiceIsCancelled() throws Exception {
        var f = line(today().minusDays(10));
        long a = f.line().agreementId();
        setNow(today().atTime(21, 0));
        assertThat(remind(f.line(), Map.of()).data().get("status").asText()).isEqualTo("QUEUED");
        assertThat(e.claim(f.line().buyer().token(), f.invoice(), "6500.00").status()).isEqualTo(201);

        setNow(today().plusDays(1).atTime(9, 0));
        jobs.runReminders();
        assertThat(events("CreditReminder", a)).isZero();
        assertThat(reminderRows(a).get(0).get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("SMS only when overdue: a manual overdue reminder sends one SMS, a due-soon manual one and every automatic one none")
    void smsOnlyWhenOverdue() throws Exception {
        var overdue = line(today().minusDays(10));
        var soon = line(today().plusDays(2));
        assertThat(remind(overdue.line(), Map.of()).status()).isEqualTo(201);
        assertThat(remind(soon.line(), Map.of()).status()).isEqualTo(201);
        assertThat(relayed(overdue, "SMS")).isEqualTo(1);
        assertThat(relayed(soon, "SMS")).isZero();
        assertThat(relayed(soon, "PUSH")).isEqualTo(1);
    }
}
