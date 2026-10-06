package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.*;
import com.costonomy.mp.credit.service.CreditReminderService.Row;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The reminders the system sends by itself, and the release of the ones that waited for the morning (B11, D-142).
 *
 * <p>Three kinds, each at most once per invoice per India day, whatever the number of nodes, because the key
 * {@code KIND:day} is unique per invoice in {@code credit_reminder_invoice}:
 * <ul>
 *   <li>{@code AUTO_T3}: three days before the due date; in-app only;</li>
 *   <li>{@code AUTO_DUE}: on the due date; in-app and push;</li>
 *   <li>{@code AUTO_WEEKLY}: seven days after the grace period ended, then every seven days, at most four times; in-app
 *       and push. (The overdue notice itself is {@code CreditOverdue}, from the sweep.)</li>
 * </ul>
 * Only from 10:00 to 20:00 India time, only for ACTIVE or SUSPENDED lines whose store has not turned them off, and
 * never for an invoice that is settled or whose outstanding a SUBMITTED claim already covers. SMS is never used by the
 * automatic ones.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditAutoReminderService {

    static final int AUTO_FROM_HOUR = 10;
    static final int WEEKLY_MAX = 4;
    static final int WEEKLY_EVERY_DAYS = 7;

    private final CreditReminderService manual;
    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceService invoiceService;
    private final CreditReminderRepository reminders;
    private final CreditReminderInvoiceRepository reminderInvoices;
    private final SupplierCreditPolicyRepository policies;
    private final CreditDirectory directory;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;

    // ── Queued manual reminders ──────────────────────────────────────────

    /** Sends the reminders that waited for 09:00 IST, once the window is open. Returns how many went. */
    public int releaseQueued() {
        var now = manual.now();
        if (!manual.inWindow(now)) {
            return 0;
        }
        int sent = 0;
        for (var queued : reminders.findByStatusAndRequestedAtLessThanEqualOrderByIdAsc(
                CreditReminderStatus.QUEUED, now)) {
            try {
                if (Boolean.TRUE.equals(txTemplate.execute(status -> releaseOne(queued.getId())))) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                // One reminder must not stop the rest; the next run tries it again.
                log.error("Could not release credit reminder {}", queued.getId(), ex);
            }
        }
        return sent;
    }

    private boolean releaseOne(Long reminderId) {
        var reminder = reminders.findById(reminderId).orElse(null);
        if (reminder == null || reminder.getStatus() != CreditReminderStatus.QUEUED) {
            return false;
        }
        var agreement = agreements.findById(reminder.getCreditAgreementId()).orElseThrow();
        var ids = reminderInvoices.findByCreditReminderIdOrderByCreditInvoiceIdAsc(reminderId).stream()
                .map(CreditReminderInvoice::getCreditInvoiceId).toList();

        // Looked at again: it may have been paid, or claimed, overnight. A restaurant that paid is not chased.
        List<Row> included = List.of();
        try {
            included = manual.evaluate(agreement, ids, manual.today()).included();
        } catch (com.costonomy.mp.common.error.BusinessException settled) {
            // An invoice on the list was settled in the meantime: re-evaluate what is still open.
            var open = ids.stream().map(invoices::findById).flatMap(Optional::stream)
                    .filter(i -> !i.getStatus().isSettled()).map(CreditInvoice::getId).toList();
            if (!open.isEmpty()) {
                included = manual.evaluate(agreement, open, manual.today()).included();
            }
        }

        if (included.isEmpty()) {
            reminder.setStatus(CreditReminderStatus.CANCELLED);
            reminders.save(reminder);
            auditService.record(null, null, "CREDIT_REMINDER_CANCELLED", "CREDIT_AGREEMENT", agreement.getId(),
                    CreditReminderStatus.QUEUED.name(), CreditReminderStatus.CANCELLED.name(),
                    "Nothing left to remind about by the time the window opened", "JOB");
            return false;
        }
        var channels = CreditReminderService.channelsFor(included);
        reminder.setChannel(String.join(",", channels));
        reminder.setMessage(manual.compose(supplierName(agreement.getSupplierStoreId()), included, reminder.getNote()));
        reminder.setStatus(CreditReminderStatus.SENT);
        reminder.setSentAt(manual.now());
        reminders.save(reminder);
        auditService.record(reminder.getCreatedBy(), null, "CREDIT_REMINDER_SENT", "CREDIT_AGREEMENT",
                agreement.getId(), CreditReminderStatus.QUEUED.name(), CreditReminderStatus.SENT.name(),
                "Sent when the window opened", "JOB");
        manual.publish(reminder, agreement, channels);
        return true;
    }

    // ── Automatic reminders ──────────────────────────────────────────────

    /** Sends every automatic reminder due now. Returns how many reminders (not invoices) went. */
    public int sendAutomatic() {
        var now = manual.now();
        int hour = now.atZone(manual.zone()).getHour();
        if (hour < AUTO_FROM_HOUR || hour >= CreditReminderService.WINDOW_CLOSES) {
            return 0;
        }
        LocalDate today = manual.today();
        int sent = 0;
        var lines = new ArrayList<CreditAgreement>();
        lines.addAll(agreements.findByStatus(CreditAgreementStatus.ACTIVE));
        lines.addAll(agreements.findByStatus(CreditAgreementStatus.SUSPENDED));
        var enabledByStore = new HashMap<Long, Boolean>();

        for (CreditAgreement line : lines) {
            boolean enabled = enabledByStore.computeIfAbsent(line.getSupplierStoreId(), id -> policies
                    .findBySupplierStoreId(id).map(p -> !Boolean.FALSE.equals(p.getAutoRemindersEnabled())).orElse(true));
            if (!enabled) {
                continue;
            }
            try {
                sent += forLine(line, today);
            } catch (RuntimeException ex) {
                log.error("Could not send automatic reminders for credit line {}", line.getId(), ex);
            }
        }
        return sent;
    }

    private int forLine(CreditAgreement line, LocalDate today) {
        var claims = invoiceService.openClaimsByInvoice(line.getId());
        var byKind = new EnumMap<CreditReminderKind, List<Long>>(CreditReminderKind.class);
        for (CreditInvoice invoice : invoices.findByCreditAgreementIdOrderByDueDateAsc(line.getId())) {
            if (invoice.getStatus().isSettled() || invoice.reportable(claims.get(invoice.getId())).signum() == 0) {
                continue;
            }
            var state = CreditDueState.of(invoice.getStatus(), invoice.getDueDate(), invoice.getOverdueAfter(), today);
            CreditReminderKind kind = null;
            if (state == CreditDueState.DUE_SOON
                    && ChronoUnit.DAYS.between(today, invoice.getDueDate()) == CreditDueState.SOON_DAYS) {
                kind = CreditReminderKind.AUTO_T3;
            } else if (state == CreditDueState.DUE_TODAY) {
                kind = CreditReminderKind.AUTO_DUE;
            } else if (state == CreditDueState.OVERDUE && weeklyDue(invoice, today)) {
                kind = CreditReminderKind.AUTO_WEEKLY;
            }
            if (kind != null) {
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(invoice.getId());
            }
        }

        int sent = 0;
        for (var entry : byKind.entrySet()) {
            try {
                if (Boolean.TRUE.equals(txTemplate.execute(status -> create(line.getId(), entry.getKey(),
                        entry.getValue(), today)))) {
                    sent++;
                }
            } catch (DataIntegrityViolationException twice) {
                // Another node made this reminder first (the unique key); nothing is sent twice.
                log.debug("{} for credit line {} was already sent today", entry.getKey(), line.getId());
            }
        }
        return sent;
    }

    /** At most four weekly reminders, the first seven days after the grace period ended and then every seven days. */
    private boolean weeklyDue(CreditInvoice invoice, LocalDate today) {
        if (reminderInvoices.countByCreditInvoiceIdAndKind(invoice.getId(), CreditReminderKind.AUTO_WEEKLY)
                >= WEEKLY_MAX) {
            return false;
        }
        LocalDate last = reminderInvoices.lastRemindDate(invoice.getId(), CreditReminderKind.AUTO_WEEKLY);
        LocalDate since = last != null ? last : invoice.getOverdueAfter();
        return ChronoUnit.DAYS.between(since, today) >= WEEKLY_EVERY_DAYS;
    }

    private boolean create(Long lineId, CreditReminderKind kind, List<Long> invoiceIds, LocalDate today) {
        var line = agreements.findById(lineId).orElseThrow();
        String autoKey = kind.name() + ":" + today;
        var fresh = new ArrayList<>(invoiceIds);
        fresh.removeAll(reminderInvoices.alreadyReminded(invoiceIds, autoKey));
        if (fresh.isEmpty()) {
            return false;
        }
        var included = manual.evaluate(line, fresh, today).included();
        if (included.isEmpty()) {
            return false;
        }
        var channels = kind == CreditReminderKind.AUTO_T3 ? List.of("IN_APP") : List.of("IN_APP", "PUSH");

        var reminder = new CreditReminder();
        reminder.setCreditAgreementId(lineId);
        reminder.setOutletId(line.getOutletId());
        reminder.setSupplierStoreId(line.getSupplierStoreId());
        reminder.setKind(kind);
        reminder.setChannel(String.join(",", channels));
        reminder.setStatus(CreditReminderStatus.SENT);
        reminder.setMessage(manual.compose(supplierName(line.getSupplierStoreId()), included, null));
        reminder.setRequestedAt(manual.now());
        reminder.setSentAt(manual.now());
        reminders.save(reminder);
        for (Row row : included) {
            // The unique key (invoice, KIND:day) is what makes this once per day: a second insert fails here.
            manual.saveChild(reminder, row.invoice().getId(), today, autoKey);
        }
        auditService.record(null, null, "CREDIT_REMINDER_SENT", "CREDIT_AGREEMENT", lineId, null,
                kind.name(), "Automatic reminder for " + included.size() + " invoice(s)", "JOB");
        manual.publish(reminder, line, channels);
        return true;
    }

    private String supplierName(Long storeId) {
        var store = directory.store(storeId);
        return store == null ? null : store.supplierName();
    }
}
