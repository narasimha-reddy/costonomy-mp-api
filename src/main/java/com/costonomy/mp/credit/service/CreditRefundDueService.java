package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceNote;
import com.costonomy.mp.credit.domain.CreditRefundDue;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditInvoiceNoteRepository;
import com.costonomy.mp.credit.repository.CreditRefundDueRepository;
import com.costonomy.mp.credit.web.dto.CreditNoteDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Refunds due to restaurants after a cancelled order (B7, D-177): what a cancelled order's invoice had already been
 * paid. The supplier settles an OFF_PLATFORM one directly and marks it refunded here; a WALLET one is Mandi's to put
 * right and the supplier cannot clear it. Marking is idempotent: a second mark answers the same and changes nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditRefundDueService {

    private final CreditRefundDueRepository refunds;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceNoteRepository notes;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    /** A store's refunds due, newest first, optionally of one status. Another store's user gets a 404. */
    @Transactional(readOnly = true)
    public List<CreditNoteDtos.RefundDueResponse> forStore(Long actorId, Long storeId, String status) {
        if (!accessControl.has(actorId, Permissions.CREDIT_VIEW, ScopeType.SUPPLIER_STORE, storeId)
                && !accessControl.has(actorId, Permissions.CREDIT_REQUEST_VIEW, ScopeType.SUPPLIER_STORE, storeId)) {
            log.warn("Scope violation: user={} SupplierStore={} — reported as not found", actorId, storeId);
            throw new NotFoundException("SupplierStore", storeId);
        }
        CreditRefundDue.Status wanted = null;
        if (status != null && !status.isBlank()) {
            try {
                wanted = CreditRefundDue.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Status is OPEN or REFUNDED.");
            }
        }
        return respond(wanted == null ? refunds.findBySupplierStoreIdOrderByIdDesc(storeId)
                : refunds.findBySupplierStoreIdAndStatusOrderByIdDesc(storeId, wanted));
    }

    @Transactional
    public CreditNoteDtos.RefundDueResponse markRefunded(Long actorId, Long refundId, String note) {
        var seen = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("CreditRefundDue", refundId));
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, seen.getSupplierStoreId(),
                "CreditRefundDue", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);

        var row = refunds.lockById(refundId).orElseThrow(() -> new NotFoundException("CreditRefundDue", refundId));
        if (row.getStatus() == CreditRefundDue.Status.REFUNDED) {
            // A retry after a lost response: already in the state asked for, so answer and change nothing.
            return respond(List.of(row)).get(0);
        }
        if (row.getChannel() == CreditRefundDue.Channel.WALLET) {
            throw new BusinessException(ErrorCode.CREDIT_REFUND_OPS_ONLY,
                    "This ₹%s was paid from the restaurant's Mandi wallet, so Mandi will refund it.".formatted(Rupees.of(row.getAmount())));
        }
        row.setStatus(CreditRefundDue.Status.REFUNDED);
        row.setRefundedAt(Instant.now());
        row.setRefundedBy(actorId);
        if (note != null && !note.isBlank()) {
            row.setNote(note.trim());
        }
        refunds.save(row);
        auditService.record(actorId, null, "CREDIT_REFUND_MARKED", "CREDIT_REFUND_DUE", refundId,
                CreditRefundDue.Status.OPEN.name(), CreditRefundDue.Status.REFUNDED.name(),
                row.getAmount().toPlainString() + (row.getNote() == null ? "" : ": " + row.getNote()), "API");
        return respond(List.of(row)).get(0);
    }

    private List<CreditNoteDtos.RefundDueResponse> respond(List<CreditRefundDue> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var invoiceById = invoices.findAllById(rows.stream().map(CreditRefundDue::getCreditInvoiceId).distinct().toList())
                .stream().collect(Collectors.toMap(CreditInvoice::getId, i -> i));
        var noteIds = rows.stream().map(CreditRefundDue::getCreditNoteId).filter(java.util.Objects::nonNull).distinct().toList();
        var numbers = new HashMap<Long, String>();
        for (CreditInvoiceNote n : notes.findByIdIn(noteIds)) {
            numbers.put(n.getId(), n.getCreditNoteNumber());
        }
        var outlets = directory.outlets(rows.stream().map(CreditRefundDue::getOutletId).distinct().toList());
        return rows.stream().map(r -> {
            var invoice = invoiceById.get(r.getCreditInvoiceId());
            var outlet = outlets.get(r.getOutletId());
            return new CreditNoteDtos.RefundDueResponse(r.getId(), CreditNoteService.money(r.getAmount()), r.getChannel(),
                    r.getStatus(), r.getNote(), r.getCreditInvoiceId(), invoice == null ? null : invoice.getInvoiceNumber(),
                    r.getCreditNoteId(), r.getCreditNoteId() == null ? null : numbers.get(r.getCreditNoteId()),
                    r.getCreditAgreementId(), r.getOutletId(), outlet == null ? null : outlet.outletName(),
                    outlet == null ? null : outlet.restaurantName(), r.getCreatedAt(), r.getRefundedAt());
        }).toList();
    }
}
