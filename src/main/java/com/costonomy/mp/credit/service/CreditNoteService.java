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
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceNote;
import com.costonomy.mp.credit.domain.CreditNoteKind;
import com.costonomy.mp.credit.domain.CreditNoteReason;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditInvoiceNoteRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditNoteDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The supplier's credit note (B7, D-175): an amount taken off one invoice for short supply, quality, price or
 * goodwill. Not a payment: nothing is collected, no payout is made and no commission applies. Capped at what is still
 * owed, refused on a PAID or WRITTEN_OFF invoice (the supplier refunds off-platform, D-177).
 *
 * <p>Like {@link CreditSupplierPaymentService} a plain bean around a {@link TransactionTemplate}, because the work
 * runs inside {@code IdempotencyService.execute} (D-016). The effect is {@link CreditInvoiceService#applyCredit}, the
 * twin of {@code applyPayment}: the invoice is held {@code FOR UPDATE}, then the agreement's exposure moves inside it,
 * the lock order every money path uses. A refusal is a failed idempotency key like any other.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditNoteService {

    static final int MAX_PAGE_SIZE = 100;

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditInvoiceNoteRepository notes;
    private final CreditInvoiceService invoiceService;
    private final CreditAgreementService agreementService;
    private final CreditExposureStore exposure;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    // ── issue ────────────────────────────────────────────────────────────

    public CreditNoteDtos.CreditNoteResponse issue(Long actorId, Long invoiceId, CreditNoteDtos.CreditNoteRequest request,
                                                   String idempotencyKey) {
        var seen = invoices.findById(invoiceId).orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));
        // Another store's invoice, or a role without the permission, is "not found", like every credit endpoint.
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, seen.getSupplierStoreId(),
                "CreditInvoice", Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);

        BigDecimal amount = request.amount().setScale(2);
        CreditNoteReason reason = CreditNoteReason.valueOf(request.reasonCode());
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        Long disputeId = request.disputeId();
        if (disputeId != null) {
            // A dispute that is not about this invoice's order is refused, so a note cannot be tied to someone else's.
            Integer match = jdbc.queryForObject("select count(*) from dispute where id = ? and supplier_order_id = ?",
                    Integer.class, disputeId, seen.getSupplierOrderId());
            if (match == null || match == 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "That dispute isn't about this invoice's order.");
            }
        }
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);

        try (var trace = TraceScope.of("credit-invoice", invoiceId)) {
            return idempotency.execute(actorId, "credit.credit-note", idempotencyKey,
                    payload(invoiceId, amount, reason, note, disputeId), CreditNoteDtos.CreditNoteResponse.class,
                    () -> txTemplate.execute(status -> work(actorId, invoiceId, amount, reason, note, disputeId, storedKey)));
        }
    }

    /**
     * What identifies "the same request" for the idempotency hash. The amount is scaled to 2 places and written as
     * plain text, so 1e3, 1000, 1000.0 and "1000.00" are one request.
     */
    static Map<String, Object> payload(Long invoiceId, BigDecimal amount, CreditNoteReason reason, String note, Long disputeId) {
        var map = new LinkedHashMap<String, Object>();
        map.put("invoiceId", invoiceId);
        map.put("amount", amount.toPlainString());
        map.put("reasonCode", reason.name());
        map.put("note", note == null ? "" : note);
        map.put("disputeId", disputeId == null ? "" : disputeId.toString());
        return map;
    }

    private CreditNoteDtos.CreditNoteResponse work(Long actorId, Long invoiceId, BigDecimal amount, CreditNoteReason reason,
                                                   String text, Long disputeId, String storedKey) {
        // The invoice is held before it is read for the arithmetic: a payment, a claim confirm or a write-off may be
        // settling it at this moment, and each must see the other's result (D-153).
        var invoice = invoices.lockById(invoiceId).orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));

        var already = notes.findByIdempotencyKey(storedKey).orElse(null);
        if (already != null) {
            return respond(already, invoice);
        }
        if (invoice.getStatus().isSettled()) {
            throw new BusinessException(ErrorCode.CREDIT_NOTE_INVOICE_SETTLED,
                    "Invoice %s is already %s, so a credit note can't be issued on it. Refund the restaurant directly."
                            .formatted(invoice.getInvoiceNumber(),
                                    invoice.getStatus() == com.costonomy.mp.credit.domain.CreditInvoiceStatus.PAID
                                            ? "paid" : "written off"),
                    Map.of("status", invoice.getStatus().name()));
        }
        BigDecimal outstanding = invoice.outstanding();
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException(ErrorCode.CREDIT_NOTE_EXCEEDS_OUTSTANDING,
                    "That's more than the ₹%s still owed on this invoice.".formatted(Rupees.of(outstanding)),
                    Map.of("outstanding", money(outstanding)));
        }

        var credit = invoiceService.applyCredit(invoice, amount, CreditNoteKind.MANUAL, reason, text, disputeId, actorId,
                storedKey, true);

        auditService.record(actorId, null, "CREDIT_NOTE_ISSUED", "CREDIT_NOTE", credit.getId(), null,
                invoice.getStatus().name(),
                "%s %s on %s: %s".formatted(credit.getCreditNoteNumber(), amount.toPlainString(), invoice.getInvoiceNumber(), reason),
                "API");
        publishIssued(credit, invoice, actorId);
        log.info("Credit note {} of {} issued by user {} on invoice {}", credit.getCreditNoteNumber(), Rupees.of(amount),
                actorId, invoice.getId());
        return respond(credit, invoice);
    }

    /** The event that tells the restaurant, for a note from the supplier and for the one a cancelled order brings. */
    void publishIssued(CreditInvoiceNote credit, CreditInvoice invoice, Long actorId) {
        var store = directory.store(invoice.getSupplierStoreId());
        outbox.publish(CreditEvents.CREDIT_NOTE_ISSUED, "CREDIT_INVOICE", invoice.getId(),
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "supplierStoreId", invoice.getSupplierStoreId(),
                        "supplierName", store == null || store.supplierName() == null ? "" : store.supplierName(),
                        "invoiceNumber", invoice.getInvoiceNumber(),
                        "creditNoteId", credit.getId(),
                        "creditNoteNumber", credit.getCreditNoteNumber(),
                        "amount", money(credit.getAmount()).toPlainString(),
                        "reasonCode", credit.getReasonCode().name(),
                        "kind", credit.getKind().name()),
                actorId);
    }

    private CreditNoteDtos.CreditNoteResponse respond(CreditInvoiceNote credit, CreditInvoice invoice) {
        // Read after the writes, from the row: the exposure moved by SQL, and the line may have been reinstated.
        var dues = invoiceService.duesFor(credit.getCreditAgreementId());
        var after = agreements.findById(credit.getCreditAgreementId()).orElseThrow();
        return new CreditNoteDtos.CreditNoteResponse(credit.getId(), credit.getCreditNoteNumber(), invoice.getId(),
                invoice.getInvoiceNumber(), credit.getCreditAgreementId(), money(credit.getAmount()),
                credit.getReasonCode(), credit.getKind(), credit.getNote(), credit.getDisputeId(), credit.getCreatedBy(),
                credit.getCreatedAt(), stateOf(invoice),
                new CreditDtos.RepaymentAgreementState(money(dues.due()), money(dues.overdue()),
                        money(exposure.read(credit.getCreditAgreementId()).available()), after.getStatus()));
    }

    static CreditNoteDtos.InvoiceState stateOf(CreditInvoice invoice) {
        return new CreditNoteDtos.InvoiceState(invoice.getStatus(), money(invoice.getAmount()), money(invoice.getPaidAmount()),
                money(invoice.getCreditedAmount()), money(invoice.outstanding()));
    }

    static CreditNoteDtos.CreditNoteSummary summaryOf(CreditInvoiceNote credit, String invoiceNumber) {
        return new CreditNoteDtos.CreditNoteSummary(credit.getId(), credit.getCreditNoteNumber(), credit.getCreditInvoiceId(),
                invoiceNumber, credit.getCreditAgreementId(), money(credit.getAmount()), credit.getReasonCode(),
                credit.getKind(), credit.getNote(), credit.getDisputeId(), credit.getCreatedBy(), credit.getCreatedAt());
    }

    // ── list ─────────────────────────────────────────────────────────────

    /** An agreement's credit notes and write-offs, newest first. Either side may read them; anyone else gets a 404. */
    @Transactional(readOnly = true)
    public CreditDtos.PageOf<CreditNoteDtos.CreditNoteSummary> forAgreement(Long actorId, Long agreementId, int page, int size) {
        agreementService.loadForEitherSide(actorId, agreementId);
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Use a page from 0 and a size of at least 1.");
        }
        size = Math.min(size, MAX_PAGE_SIZE);
        var result = notes.findByCreditAgreementIdOrderByIdDesc(agreementId, PageRequest.of(page, size));
        var numbers = invoices.findAllById(result.getContent().stream().map(CreditInvoiceNote::getCreditInvoiceId).distinct().toList())
                .stream().collect(Collectors.toMap(CreditInvoice::getId, CreditInvoice::getInvoiceNumber, (a, b) -> a, HashMap::new));
        List<CreditNoteDtos.CreditNoteSummary> items = result.getContent().stream()
                .map(n -> summaryOf(n, numbers.get(n.getCreditInvoiceId()))).toList();
        return new CreditDtos.PageOf<>(items, page, size, result.getTotalElements(), result.hasNext());
    }

    /** A figure for the app: two places, never rounded up above what it is. */
    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN);
    }
}
