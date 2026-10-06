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
import com.costonomy.mp.credit.domain.CreditDueExtension;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditDueExtensionRepository;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.web.dto.CreditLifecycleDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A supplier giving an invoice longer to be paid (D-138).
 *
 * <p>Nothing about the money changes: not the amount, not the exposure, not the ledger. Only the date does, and what
 * follows from it. The rules: later only, strictly after the current due date; at most {@link #MAX_DAYS_BEYOND_ORIGINAL}
 * days beyond the ORIGINAL due date however many times it is extended; never on a settled invoice.
 *
 * <p>The invoice is locked first and the line's suspension checked after it, in the order every credit write uses
 * (invoice, then agreement). Run inside {@code IdempotencyService.execute}, so a retry replays and cannot extend twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditDueExtensionService {

    /** How far past the original due date an invoice may be carried, in total. */
    static final int MAX_DAYS_BEYOND_ORIGINAL = 60;

    private static final DateTimeFormatter READABLE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final CreditInvoiceRepository invoices;
    private final CreditDueExtensionRepository extensions;
    private final CreditAgreementRepository agreements;
    private final CreditInvoiceService invoiceService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    public CreditLifecycleDtos.ExtendDueResponse extend(Long actorId, Long invoiceId,
                                                        CreditLifecycleDtos.ExtendDueRequest request,
                                                        String idempotencyKey) {
        var seen = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
        // Anyone who may record a payment may give the restaurant longer to make it. Another store, a restaurant user
        // and a salesperson are "not found".
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, seen.getSupplierStoreId(),
                "CreditInvoice", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);

        String reason = request.reason().trim();
        var payload = new HashMap<String, Object>();
        payload.put("invoiceId", invoiceId);
        payload.put("newDueDate", request.newDueDate().toString());
        payload.put("reason", reason);

        try (var trace = TraceScope.of("credit-invoice", invoiceId)) {
            return idempotency.execute(actorId, "credit.extend-due", idempotencyKey, payload,
                    CreditLifecycleDtos.ExtendDueResponse.class,
                    () -> txTemplate.execute(status -> doExtend(actorId, invoiceId, request.newDueDate(), reason)));
        }
    }

    private CreditLifecycleDtos.ExtendDueResponse doExtend(Long actorId, Long invoiceId, LocalDate newDue,
                                                           String reason) {
        var invoice = invoices.lockById(invoiceId)
                .orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));

        if (invoice.getStatus().isSettled()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Invoice %s is already %s, so its due date can't be moved."
                            .formatted(invoice.getInvoiceNumber(),
                                    invoice.getStatus() == CreditInvoiceStatus.PAID ? "paid" : "written off"));
        }

        LocalDate oldDue = invoice.getDueDate();
        if (!newDue.isAfter(oldDue)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The new due date must be after the current one, %s.".formatted(oldDue));
        }
        LocalDate original = extensions.findFirstByCreditInvoiceIdOrderByIdAsc(invoiceId)
                .map(CreditDueExtension::getOldDueDate).orElse(oldDue);
        LocalDate latest = original.plusDays(MAX_DAYS_BEYOND_ORIGINAL);
        if (newDue.isAfter(latest)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A due date can be moved at most %d days past the original, %s. The latest you can set is %s."
                            .formatted(MAX_DAYS_BEYOND_ORIGINAL, original, latest),
                    Map.of("originalDueDate", original.toString(), "latestDueDate", latest.toString()));
        }

        // The grace the invoice was issued with travels with the date, so the supplier's patience is not changed by it.
        LocalDate oldOverdueAfter = invoice.getOverdueAfter();
        LocalDate newOverdueAfter = newDue.plusDays(ChronoUnit.DAYS.between(oldDue, oldOverdueAfter));
        invoice.setDueDate(newDue);
        invoice.setOverdueAfter(newOverdueAfter);
        // An overdue invoice that is no longer late (India day) is open again, as the sweep would see it: it is late
        // only once overdue-after is behind today. It stays overdue while it still is.
        if (invoice.getStatus() == CreditInvoiceStatus.OVERDUE && !newOverdueAfter.isBefore(invoiceService.today())) {
            invoice.setStatus(invoice.getPaidAmount().signum() > 0
                    ? CreditInvoiceStatus.PARTIALLY_PAID : CreditInvoiceStatus.ISSUED);
        }
        invoices.save(invoice);

        var extension = new CreditDueExtension();
        extension.setCreditInvoiceId(invoiceId);
        extension.setCreditAgreementId(invoice.getCreditAgreementId());
        extension.setOldDueDate(oldDue);
        extension.setNewDueDate(newDue);
        extension.setOldOverdueAfter(oldOverdueAfter);
        extension.setNewOverdueAfter(newOverdueAfter);
        extension.setReason(reason);
        extension.setExtendedBy(actorId);
        extensions.save(extension);

        auditService.record(actorId, null, "CREDIT_DUE_EXTENDED", "CREDIT_INVOICE", invoiceId,
                oldDue.toString(), newDue.toString(), reason, "API");

        var store = directory.store(invoice.getSupplierStoreId());
        outbox.publish(CreditEvents.DUE_DATE_EXTENDED, "CREDIT_INVOICE", invoiceId,
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "supplierStoreId", invoice.getSupplierStoreId(),
                        "supplierName", store == null || store.supplierName() == null ? "" : store.supplierName(),
                        "invoiceNumber", invoice.getInvoiceNumber(),
                        "oldDueDate", oldDue.toString(),
                        "newDueDate", newDue.toString(),
                        "newDueDateText", READABLE.format(newDue)),
                actorId);

        // What the sweep suspended the line for may be cleared by this, exactly as by a repayment (D-119, D-133).
        invoiceService.reinstateIfOverdueCleared(invoice.getCreditAgreementId());

        var agreement = agreements.findById(invoice.getCreditAgreementId()).orElseThrow();
        log.info("Credit invoice {} due date moved {} to {}", invoiceId, oldDue, newDue);
        return new CreditLifecycleDtos.ExtendDueResponse(
                invoiceService.toInvoiceResponse(invoice),
                new CreditLifecycleDtos.DueExtensionResponse(extension.getId(), oldDue, newDue, reason, actorId,
                        extension.getCreatedAt()),
                agreement.getStatus());
    }
}
