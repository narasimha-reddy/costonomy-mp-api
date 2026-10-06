package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.*;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditReminderDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manual reminders to a restaurant about what it owes (B11, D-142).
 *
 * <p>A reminder is a message and moves no money. The server decides everything: what is worth a reminder, how often a
 * supplier may send one, and whether it goes now or waits for the morning. Dates are India days from the credit clock.
 * The rules:
 * <ul>
 *   <li>only an open invoice that is overdue, past its due date inside the grace period, or due within 3 days;</li>
 *   <li>never for an invoice whose outstanding the restaurant has already claimed to have paid (a SUBMITTED claim
 *       covers it): it is skipped and the supplier is told;</li>
 *   <li>one manual reminder per line per 24 hours, three per line in a rolling 7 days, 50 per store per India day;</li>
 *   <li>09:00 to 20:00 IST only: asked for outside it, the reminder is QUEUED and a job sends it when the window opens.</li>
 * </ul>
 *
 * <p>The agreement row is locked for the write, so two reminders for one line cannot both pass the limits. It is the
 * only lock taken: nothing here changes an invoice, a claim or the wallet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditReminderService {

    static final int WINDOW_OPENS = 9;
    static final int WINDOW_CLOSES = 20;
    static final Duration MIN_GAP = Duration.ofHours(24);
    static final Duration WEEK = Duration.ofDays(7);
    static final int MAX_PER_WEEK = 3;
    static final int MAX_PER_STORE_DAY = 50;

    static final String SKIP_CLAIM = "CLAIM_SUBMITTED";
    static final String SKIP_NOT_DUE = "NOT_DUE";

    private static final Set<CreditDueState> REMINDABLE = EnumSet.of(CreditDueState.OVERDUE, CreditDueState.IN_GRACE,
            CreditDueState.DUE_TODAY, CreditDueState.DUE_SOON);

    private final CreditAgreementRepository agreements;
    private final CreditAgreementLockRepository agreementLocks;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceService invoiceService;
    private final CreditReminderRepository reminders;
    private final CreditReminderInvoiceRepository reminderInvoices;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;
    // Field-injected: Lombok's constructor would drop the @Qualifier, and there must be no unqualified Clock here.
    @Autowired
    @Qualifier("creditClock")
    private Clock clock;

    // ── What the evaluation found ────────────────────────────────────────

    /** One invoice considered for a reminder; {@code skipReason} is null when it is included. */
    record Row(CreditInvoice invoice, CreditDueState state, String skipReason) {
        boolean included() {
            return skipReason == null;
        }
    }

    /** Which invoices a reminder would be about, and which were left out and why. */
    record Evaluation(List<Row> rows) {
        List<Row> included() {
            return rows.stream().filter(Row::included).toList();
        }

        List<CreditReminderDtos.Skipped> skipped() {
            return rows.stream().filter(r -> !r.included())
                    .map(r -> new CreditReminderDtos.Skipped(r.invoice().getId(), r.invoice().getInvoiceNumber(),
                            r.skipReason())).toList();
        }

        boolean anyClaimSkip() {
            return rows.stream().anyMatch(r -> SKIP_CLAIM.equals(r.skipReason()));
        }
    }

    /** Whether a reminder is worth sending for this invoice's state. */
    static boolean remindable(CreditDueState state) {
        return REMINDABLE.contains(state);
    }

    Instant now() {
        return clock.instant();
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    ZoneId zone() {
        return clock.getZone();
    }

    /** 09:00 up to, not including, 20:00 India time. */
    boolean inWindow(Instant at) {
        int hour = at.atZone(zone()).getHour();
        return hour >= WINDOW_OPENS && hour < WINDOW_CLOSES;
    }

    /** The next 09:00 India time after {@code at}, for a reminder that has to wait. */
    Instant nextWindowOpening(Instant at) {
        ZonedDateTime local = at.atZone(zone());
        ZonedDateTime opening = local.toLocalDate().atTime(WINDOW_OPENS, 0).atZone(zone());
        return local.getHour() < WINDOW_OPENS ? opening.toInstant() : opening.plusDays(1).toInstant();
    }

    /** The invoices a reminder on this line would cover; invoice ids given must be open invoices of this line. */
    Evaluation evaluate(CreditAgreement agreement, List<Long> requested, LocalDate today) {
        var all = invoices.findByCreditAgreementIdOrderByDueDateAsc(agreement.getId());
        boolean explicit = requested != null && !requested.isEmpty();
        List<CreditInvoice> chosen;
        if (explicit) {
            var byId = all.stream().collect(Collectors.toMap(CreditInvoice::getId, i -> i));
            chosen = new ArrayList<>();
            for (Long id : new LinkedHashSet<>(requested)) {
                var invoice = byId.get(id);
                if (invoice == null || invoice.getStatus().isSettled()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "Invoice %d is not an open invoice on this credit line.".formatted(id));
                }
                chosen.add(invoice);
            }
            chosen.sort(Comparator.comparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId));
        } else {
            chosen = all.stream().filter(i -> !i.getStatus().isSettled()).toList();
        }

        var claims = invoiceService.openClaimsByInvoice(agreement.getId());
        var rows = new ArrayList<Row>();
        for (CreditInvoice invoice : chosen) {
            var state = CreditDueState.of(invoice.getStatus(), invoice.getDueDate(), invoice.getOverdueAfter(), today);
            if (!remindable(state)) {
                if (explicit) {
                    rows.add(new Row(invoice, state, SKIP_NOT_DUE));
                }
                continue;
            }
            // The restaurant already says it paid all of it and the supplier has not answered: asking again would be
            // nagging someone who is waiting on the supplier.
            boolean covered = invoice.reportable(claims.get(invoice.getId())).signum() == 0;
            rows.add(new Row(invoice, state, covered ? SKIP_CLAIM : null));
        }
        return new Evaluation(rows);
    }

    static List<String> channelsFor(List<Row> included) {
        boolean overdue = included.stream().anyMatch(r -> r.state() == CreditDueState.OVERDUE);
        return overdue ? List.of("IN_APP", "PUSH", "SMS") : List.of("IN_APP", "PUSH");
    }

    static String variantOf(List<String> channels) {
        return channels.contains("SMS") ? "SMS" : channels.contains("PUSH") ? "PUSH" : "IN_APP";
    }

    String compose(String supplierName, List<Row> included, String note) {
        var items = included.stream().map(r -> new CreditReminderText.Item(r.invoice().getInvoiceNumber(),
                r.invoice().outstanding(), r.invoice().getDueDate(), r.state())).toList();
        return CreditReminderText.reminder(supplierName, items, note);
    }

    private String supplierName(Long storeId) {
        var store = directory.store(storeId);
        return store == null ? null : store.supplierName();
    }

    /** What a limit refused, or null if the supplier may send one now. */
    private record Limit(String reason, ErrorCode code, Instant nextAllowedAt, String limit, Integer max) {
    }

    private Limit limitFor(CreditAgreement agreement, Instant now, LocalDate today) {
        var recent = reminders.findByCreditAgreementIdAndKindAndRequestedAtGreaterThanOrderByRequestedAtAsc(
                agreement.getId(), CreditReminderKind.MANUAL, now.minus(WEEK));
        if (!recent.isEmpty()) {
            Instant last = recent.get(recent.size() - 1).getRequestedAt();
            if (last.plus(MIN_GAP).isAfter(now)) {
                return new Limit("TOO_SOON", ErrorCode.CREDIT_REMINDER_TOO_SOON, last.plus(MIN_GAP), null, null);
            }
        }
        if (recent.size() >= MAX_PER_WEEK) {
            // The oldest of the latest three leaves the rolling week first.
            Instant free = recent.get(recent.size() - MAX_PER_WEEK).getRequestedAt().plus(WEEK);
            return new Limit("WEEK_LIMIT", ErrorCode.CREDIT_REMINDER_LIMIT, free, "WEEK", MAX_PER_WEEK);
        }
        Instant dayStart = today.atStartOfDay(zone()).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(zone()).toInstant();
        long today50 = reminders.countBySupplierStoreIdAndKindAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                agreement.getSupplierStoreId(), CreditReminderKind.MANUAL, dayStart, dayEnd);
        if (today50 >= MAX_PER_STORE_DAY) {
            return new Limit("STORE_DAY_LIMIT", ErrorCode.CREDIT_REMINDER_LIMIT, dayEnd, "STORE_DAY",
                    MAX_PER_STORE_DAY);
        }
        return null;
    }

    // ── Reads ────────────────────────────────────────────────────────────

    private CreditAgreement requireCollector(Long actorId, Long agreementId) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        // Anyone who may record a payment may remind about it. A restaurant user, another store and a salesperson
        // learn nothing exists.
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, agreement.getSupplierStoreId(),
                "CreditAgreement", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);
        return agreement;
    }

    /** What a reminder would say and whether it may go now; changes nothing. */
    @Transactional(readOnly = true)
    public CreditReminderDtos.PreviewResponse preview(Long actorId, Long agreementId, List<Long> invoiceIds) {
        var agreement = requireCollector(actorId, agreementId);
        Instant now = now();
        var evaluation = evaluate(agreement, invoiceIds, today());
        var included = evaluation.included();

        var rows = evaluation.rows().stream()
                .map(r -> new CreditReminderDtos.PreviewInvoice(r.invoice().getId(), r.invoice().getInvoiceNumber(),
                        r.invoice().outstanding(), r.invoice().getDueDate(), r.state(), r.included(), r.skipReason()))
                .toList();
        if (included.isEmpty()) {
            return new CreditReminderDtos.PreviewResponse(false,
                    evaluation.anyClaimSkip() ? "CLAIM_COVERED" : "NOTHING_DUE", null, null, List.of(), null, null,
                    rows);
        }

        var channels = channelsFor(included);
        String message = compose(supplierName(agreement.getSupplierStoreId()), included, null);
        var limit = limitFor(agreement, now, today());
        if (limit != null) {
            return new CreditReminderDtos.PreviewResponse(false, limit.reason(), limit.nextAllowedAt(), message,
                    channels, null, null, rows);
        }
        boolean now_ = inWindow(now);
        return new CreditReminderDtos.PreviewResponse(true, null, null, message, channels,
                now_ ? CreditReminderStatus.SENT : CreditReminderStatus.QUEUED,
                now_ ? null : nextWindowOpening(now), rows);
    }

    /** The line's reminders, newest first. Supplier side only (CREDIT_VIEW on the store). */
    @Transactional(readOnly = true)
    public CreditDtos.PageOf<CreditReminderDtos.ReminderResponse> history(Long actorId, Long agreementId, int page,
                                                                          int size) {
        var agreement = agreements.findById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        accessControl.requireScoped(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE,
                agreement.getSupplierStoreId(), "CreditAgreement");
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Page must be 0 or more and size at least 1.");
        }
        size = Math.min(size, CreditSupplierReadService.MAX_PAGE_SIZE);
        var result = reminders.findByCreditAgreementIdOrderByIdDesc(agreementId, PageRequest.of(page, size));
        var children = reminderInvoices.findByCreditReminderIdIn(
                result.getContent().stream().map(CreditReminder::getId).toList()).stream()
                .collect(Collectors.groupingBy(CreditReminderInvoice::getCreditReminderId));
        var items = result.getContent().stream().map(r -> toResponse(r,
                children.getOrDefault(r.getId(), List.of()).stream().map(CreditReminderInvoice::getCreditInvoiceId)
                        .sorted().toList(), List.of())).toList();
        return new CreditDtos.PageOf<>(items, page, size, result.getTotalElements(), result.hasNext());
    }

    // ── Send ─────────────────────────────────────────────────────────────

    /** Idempotent on the key: a retry replays the first answer and sends nothing more. */
    public CreditReminderDtos.ReminderResponse send(Long actorId, Long agreementId,
                                                    CreditReminderDtos.SendRequest request, String idempotencyKey) {
        requireCollector(actorId, agreementId);
        String note = CreditReminderText.cleanNote(request.note());
        var ids = request.invoiceIds() == null ? null : request.invoiceIds().stream().sorted().toList();

        var payload = new HashMap<String, Object>();
        payload.put("agreementId", agreementId);
        payload.put("invoiceIds", ids);
        payload.put("note", note);

        try (var trace = TraceScope.of("credit-agreement", agreementId)) {
            return idempotency.execute(actorId, "credit.reminder", idempotencyKey, payload,
                    CreditReminderDtos.ReminderResponse.class,
                    () -> txTemplate.execute(status -> doSend(actorId, agreementId, ids, note, idempotencyKey)));
        }
    }

    private CreditReminderDtos.ReminderResponse doSend(Long actorId, Long agreementId, List<Long> invoiceIds,
                                                       String note, String idempotencyKey) {
        // Taken first: two reminders for one line read the limits one after the other.
        var agreement = agreementLocks.lockById(agreementId)
                .orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        Instant now = now();
        LocalDate today = today();

        var evaluation = evaluate(agreement, invoiceIds, today);
        var included = evaluation.included();
        if (included.isEmpty()) {
            String reason = evaluation.anyClaimSkip() ? "CLAIM_COVERED" : "NOTHING_DUE";
            throw new BusinessException(ErrorCode.CREDIT_REMINDER_NOT_NEEDED,
                    evaluation.anyClaimSkip()
                            ? "The restaurant has already said it paid these. Confirm or reject its payment instead."
                            : ErrorCode.CREDIT_REMINDER_NOT_NEEDED.defaultMessage(),
                    Map.of("reason", reason, "skipped", evaluation.skipped()));
        }

        var limit = limitFor(agreement, now, today);
        if (limit != null) {
            var details = new HashMap<String, Object>();
            details.put("nextAllowedAt", limit.nextAllowedAt().toString());
            if (limit.limit() != null) {
                details.put("limit", limit.limit());
                details.put("max", limit.max());
            }
            throw new BusinessException(limit.code(), limit.code().defaultMessage(), details);
        }

        var channels = channelsFor(included);
        boolean sendNow = inWindow(now);

        var reminder = new CreditReminder();
        reminder.setCreditAgreementId(agreementId);
        reminder.setOutletId(agreement.getOutletId());
        reminder.setSupplierStoreId(agreement.getSupplierStoreId());
        reminder.setKind(CreditReminderKind.MANUAL);
        reminder.setChannel(String.join(",", channels));
        reminder.setStatus(sendNow ? CreditReminderStatus.SENT : CreditReminderStatus.QUEUED);
        reminder.setNote(note);
        reminder.setMessage(compose(supplierName(agreement.getSupplierStoreId()), included, note));
        reminder.setCreatedBy(actorId);
        reminder.setRequestedAt(now);
        reminder.setSentAt(sendNow ? now : null);
        reminder.setIdempotencyKey(actorId + ":" + idempotencyKey);
        reminders.save(reminder);

        for (Row row : included) {
            saveChild(reminder, row.invoice().getId(), today, null);
        }

        auditService.record(actorId, null, sendNow ? "CREDIT_REMINDER_SENT" : "CREDIT_REMINDER_QUEUED",
                "CREDIT_AGREEMENT", agreementId, null, reminder.getStatus().name(),
                "Reminder for " + included.size() + " invoice(s)", "API");
        if (sendNow) {
            publish(reminder, agreement, channels);
        }
        log.info("Credit reminder {} for line {} is {}", reminder.getId(), agreementId, reminder.getStatus());

        return toResponse(reminder, included.stream().map(r -> r.invoice().getId()).sorted().toList(),
                evaluation.skipped());
    }

    // ── Shared with the automatic reminders ──────────────────────────────

    void saveChild(CreditReminder reminder, Long invoiceId, LocalDate day, String autoKey) {
        var child = new CreditReminderInvoice();
        child.setCreditReminderId(reminder.getId());
        child.setCreditInvoiceId(invoiceId);
        child.setKind(reminder.getKind());
        child.setRemindDate(day);
        child.setAutoKey(autoKey);
        reminderInvoices.saveAndFlush(child);
    }

    /** Hands the reminder to the notification outbox: the restaurant's people are told, on the channels it names. */
    void publish(CreditReminder reminder, CreditAgreement agreement, List<String> channels) {
        var payload = new HashMap<String, Object>();
        payload.put("creditAgreementId", agreement.getId());
        payload.put("outletId", agreement.getOutletId());
        payload.put("supplierStoreId", agreement.getSupplierStoreId());
        payload.put("reminderId", reminder.getId());
        payload.put("kind", reminder.getKind().name());
        payload.put("message", reminder.getMessage());
        payload.put("notificationVariant", variantOf(channels));
        outbox.publish(CreditEvents.REMINDER, "CREDIT_AGREEMENT", agreement.getId(), payload, reminder.getCreatedBy());
    }

    CreditReminderDtos.ReminderResponse toResponse(CreditReminder r, List<Long> invoiceIds,
                                                   List<CreditReminderDtos.Skipped> skipped) {
        return new CreditReminderDtos.ReminderResponse(r.getId(), r.getCreditAgreementId(), r.getKind(),
                r.getStatus(), List.of(r.getChannel().split(",")), r.getMessage(), r.getNote(), invoiceIds, skipped,
                r.getRequestedAt(),
                r.getStatus() == CreditReminderStatus.QUEUED ? nextWindowOpening(r.getRequestedAt()) : null,
                r.getSentAt(), r.getCreatedBy());
    }
}
