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
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditAgreementStatus;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditNoteKind;
import com.costonomy.mp.credit.domain.CreditNoteReason;
import com.costonomy.mp.credit.domain.SuspensionSource;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditNoteDtos;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The supplier gives up on what is still owed (B8, D-179, D-180). Not a payment (nothing is collected, no payout, no
 * commission) and not a favour to the restaurant's wallet: a {@code credit_invoice_note} row of kind WRITE_OFF, a WRITE_OFF
 * ledger row, and the invoice becomes WRITTEN_OFF when nothing is left (a partial one keeps its status).
 *
 * <p>Only {@code CREDIT_WRITE_OFF} (owner and admin, V80) may do it; anyone else gets the uniform 404. The line is
 * suspended by the supplier with reason "Written off" unless {@code keepLineOpen}, so a write-off cannot quietly
 * refresh the credit and hide a default (decision 6). A line the overdue sweep had suspended becomes the supplier's
 * suspension, which a repayment no longer lifts. Waiting claims on an invoice written off in full are superseded.
 *
 * <p>Like the other supplier writes a plain bean around a {@link TransactionTemplate} inside
 * {@code IdempotencyService.execute}. Lock order: the invoices {@code FOR UPDATE} in ascending id order, then the
 * agreement (exposure inside {@code applyCredit}, then the suspension).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditWriteOffService {

    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID, CreditInvoiceStatus.WRITTEN_OFF);
    static final String SUSPENSION_REASON = "Written off";

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceService invoiceService;
    private final CreditAgreementService agreementService;
    private final CreditExposureStore exposure;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final EntityManager entityManager;
    private final TransactionTemplate txTemplate;

    /** One invoice and how much of it is written off. */
    private record Step(CreditInvoice invoice, BigDecimal amount) {
    }

    // ── entry points ─────────────────────────────────────────────────────

    public CreditNoteDtos.WriteOffResponse writeOffInvoice(Long actorId, Long invoiceId, CreditNoteDtos.WriteOffRequest request,
                                                           String idempotencyKey) {
        var seen = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
        accessControl.requireScoped(actorId, Permissions.CREDIT_WRITE_OFF, ScopeType.SUPPLIER_STORE,
                seen.getSupplierStoreId(), "CreditInvoice");
        var input = Input.of(request);
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);
        try (var trace = TraceScope.of("credit-invoice", invoiceId)) {
            return idempotency.execute(actorId, "credit.write-off", idempotencyKey, payload("INVOICE", invoiceId, input),
                    CreditNoteDtos.WriteOffResponse.class,
                    () -> txTemplate.execute(status -> {
                        var invoice = invoices.lockById(invoiceId).orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
                        if (invoice.getStatus().isSettled() || invoice.outstanding().signum() <= 0) {
                            throw nothingOwed();
                        }
                        BigDecimal owed = invoice.outstanding();
                        BigDecimal amount = input.amount() == null ? owed : input.amount();
                        if (amount.compareTo(owed) > 0) {
                            throw exceeds(owed);
                        }
                        return work(actorId, invoice.getCreditAgreementId(), List.of(new Step(invoice, amount)), input, storedKey);
                    }));
        }
    }

    public CreditNoteDtos.WriteOffResponse writeOffLine(Long actorId, Long agreementId, CreditNoteDtos.WriteOffRequest request,
                                                        String idempotencyKey) {
        var seen = agreements.findById(agreementId).orElseThrow(() -> new NotFoundException("CreditAgreement", agreementId));
        accessControl.requireScoped(actorId, Permissions.CREDIT_WRITE_OFF, ScopeType.SUPPLIER_STORE,
                seen.getSupplierStoreId(), "CreditAgreement");
        var input = Input.of(request);
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);
        try (var trace = TraceScope.of("credit-agreement", agreementId)) {
            return idempotency.execute(actorId, "credit.write-off", idempotencyKey, payload("LINE", agreementId, input),
                    CreditNoteDtos.WriteOffResponse.class,
                    () -> txTemplate.execute(status -> {
                        // Every open invoice, ascending id: the one order every taker uses.
                        var locked = invoices.lockOpenOfAgreement(agreementId, SETTLED);
                        BigDecimal owed = locked.stream().map(CreditInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
                        if (locked.isEmpty() || owed.signum() <= 0) {
                            throw nothingOwed();
                        }
                        if (input.amount() != null && input.amount().compareTo(owed) > 0) {
                            throw exceeds(owed);
                        }
                        // Oldest due date first, ties by id: the supplier gives up on the oldest debt first.
                        var ordered = new ArrayList<>(locked);
                        ordered.sort(Comparator.comparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId));
                        var plan = new ArrayList<Step>();
                        BigDecimal remaining = input.amount();
                        for (CreditInvoice invoice : ordered) {
                            if (remaining != null && remaining.signum() == 0) {
                                break;
                            }
                            BigDecimal part = remaining == null ? invoice.outstanding() : invoice.outstanding().min(remaining);
                            plan.add(new Step(invoice, part));
                            if (remaining != null) {
                                remaining = remaining.subtract(part);
                            }
                        }
                        return work(actorId, agreementId, plan, input, storedKey);
                    }));
        }
    }

    /** The request as it is used: reason trimmed, amount to 2 places, flags resolved. */
    private record Input(BigDecimal amount, String reason, String quickReason, boolean keepOpen) {

        static Input of(CreditNoteDtos.WriteOffRequest request) {
            String reason = request.reason().trim();
            if (reason.length() < 3) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give a reason of 3 to 500 characters.");
            }
            return new Input(request.amount() == null ? null : request.amount().setScale(2), reason, request.quickReason(),
                    request.keepOpen());
        }
    }

    /** What identifies "the same request" for the idempotency hash; the amount is scaled to 2 places, as plain text. */
    static Map<String, Object> payload(String kind, Long id, Input input) {
        var map = new LinkedHashMap<String, Object>();
        map.put("kind", kind);
        map.put("id", id);
        map.put("amount", input.amount() == null ? "" : input.amount().toPlainString());
        map.put("reason", input.reason());
        map.put("quickReason", input.quickReason() == null ? "" : input.quickReason());
        map.put("keepLineOpen", input.keepOpen());
        return map;
    }

    // ── the write-off ────────────────────────────────────────────────────

    private CreditNoteDtos.WriteOffResponse work(Long actorId, Long agreementId, List<Step> plan, Input input, String storedKey) {
        CreditNoteReason reasonCode = "GOODWILL".equals(input.quickReason()) ? CreditNoteReason.GOODWILL : CreditNoteReason.OTHER;
        var items = new ArrayList<CreditNoteDtos.WriteOffItem>();
        BigDecimal total = BigDecimal.ZERO;
        for (Step step : plan) {
            var invoice = step.invoice();
            var credit = invoiceService.applyCredit(invoice, step.amount(), CreditNoteKind.WRITE_OFF, reasonCode, input.reason(),
                    null, actorId, storedKey + ":" + invoice.getId(), input.keepOpen());
            auditService.record(actorId, null, "CREDIT_WRITTEN_OFF", "CREDIT_INVOICE", invoice.getId(), null,
                    invoice.getStatus().name(),
                    "%s of %s: %s%s".formatted(credit.getCreditNoteNumber(), step.amount().toPlainString(), input.reason(),
                            input.quickReason() == null ? "" : " (" + input.quickReason() + ")"),
                    "API");
            items.add(new CreditNoteDtos.WriteOffItem(invoice.getId(), invoice.getInvoiceNumber(), credit.getId(),
                    credit.getCreditNoteNumber(), CreditNoteService.money(step.amount()), invoice.getStatus(),
                    CreditNoteService.money(invoice.outstanding())));
            total = total.add(step.amount());
        }

        var agreement = suspendUnlessKeptOpen(actorId, agreementId, input);

        var first = plan.get(0).invoice();
        String named = plan.size() == 1 ? first.getInvoiceNumber()
                : "%s and %d more".formatted(first.getInvoiceNumber(), plan.size() - 1);
        var store = directory.store(agreement.getSupplierStoreId());
        // The supplier's own reason is not in the event: it is theirs, and the restaurant is told neutrally.
        outbox.publish(CreditEvents.WRITTEN_OFF, "CREDIT_INVOICE", first.getId(),
                Map.of("creditAgreementId", agreementId,
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "supplierName", store == null || store.supplierName() == null ? "" : store.supplierName(),
                        "invoiceNumber", named,
                        "amount", CreditNoteService.money(total).toPlainString(),
                        "invoiceCount", plan.size()),
                actorId);
        log.info("Credit write-off of {} by user {} on agreement {} over {} invoice(s)", Rupees.of(total), actorId,
                agreementId, plan.size());

        var dues = invoiceService.duesFor(agreementId);
        var after = agreements.findById(agreementId).orElseThrow();
        return new CreditNoteDtos.WriteOffResponse(CreditNoteService.money(total), items, after.getStatus(),
                after.getStatus() == CreditAgreementStatus.SUSPENDED,
                new CreditDtos.RepaymentAgreementState(CreditNoteService.money(dues.due()), CreditNoteService.money(dues.overdue()),
                        CreditNoteService.money(exposure.read(agreementId).available()), after.getStatus()));
    }

    /**
     * Suspend the line with the supplier as the source and "Written off" as the reason, unless the supplier chose to
     * keep it open. An ACTIVE line is suspended; one the overdue sweep suspended becomes the supplier's, so a later
     * repayment does not lift it; any other state is left alone.
     */
    private CreditAgreement suspendUnlessKeptOpen(Long actorId, Long agreementId, Input input) {
        var agreement = agreements.findById(agreementId).orElseThrow();
        if (input.keepOpen()) {
            // The auto-reinstate hook already refreshed this instance after the exposure moved, and may have changed it;
            // refreshing again would throw that change away unwritten.
            return agreement;
        }
        // No hook ran, so nothing here is unwritten; the exposure moved by SQL and the version with it, so read the row again.
        entityManager.refresh(agreement);
        if (agreement.getStatus() == CreditAgreementStatus.ACTIVE) {
            agreementService.suspendInternal(agreement, SUSPENSION_REASON, actorId);
        } else if (agreement.getStatus() == CreditAgreementStatus.SUSPENDED
                && agreement.getSuspensionSource() == SuspensionSource.SYSTEM) {
            agreement.setSuspensionSource(SuspensionSource.SUPPLIER);
            agreement.setSuspensionReason(SUSPENSION_REASON);
            agreements.save(agreement);
            auditService.record(actorId, null, "CREDIT_SUSPENSION_ADOPTED", "CREDIT_AGREEMENT", agreementId,
                    CreditAgreementStatus.SUSPENDED.name(), CreditAgreementStatus.SUSPENDED.name(),
                    SUSPENSION_REASON, "API");
        }
        return agreement;
    }

    private static BusinessException nothingOwed() {
        return new BusinessException(ErrorCode.CREDIT_WRITE_OFF_NOTHING_OWED);
    }

    private static BusinessException exceeds(BigDecimal owed) {
        return new BusinessException(ErrorCode.CREDIT_NOTE_EXCEEDS_OUTSTANDING,
                "That's more than the ₹%s still owed.".formatted(Rupees.of(owed)),
                Map.of("outstanding", CreditNoteService.money(owed)));
    }
}
