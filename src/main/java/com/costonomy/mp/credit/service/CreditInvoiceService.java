package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.credit.domain.*;
import com.costonomy.mp.credit.repository.*;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Invoices and repayments. Doc 01 §18's "invoice/dues view", doc 10 §1 scenario 7.
 *
 * <p>An invoice is raised when credit is <b>utilized</b> — the moment a supplier
 * accepts — not when the order is placed. Until then nothing has been supplied and
 * nothing is owed, and an invoice for an order that gets rejected would have to be
 * cancelled again, leaving a trail that looks like a dispute.
 *
 * <p><b>Repayment is recorded, not collected.</b> Doc 01 §18: Mandi does not fund
 * credit, own receivables or perform recovery. The money moves directly between
 * restaurant and supplier; this records the supplier confirming it arrived, and
 * releases the restaurant's utilization by the same amount.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditInvoiceService {

    private final CreditInvoiceRepository invoices;
    private final CreditPaymentRepository payments;
    private final CreditAgreementRepository agreements;
    private final InvoiceNumberGenerator invoiceNumbers;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    private final CreditLedger ledger;

    /** Raise the invoice for a drawn-down order. */
    @Transactional
    public CreditInvoice issueFor(CreditReservation reservation, BigDecimal amount) {
        var existing = invoices.findBySupplierOrderId(reservation.getSupplierOrderId()).orElse(null);
        if (existing != null) {
            // uk_credit_invoice_order also guards this. A second invoice for one
            // order would double what the restaurant owes.
            return existing;
        }

        var agreement = agreements.findById(reservation.getCreditAgreementId()).orElseThrow();
        var order = directory.order(reservation.getSupplierOrderId());

        Instant now = Instant.now();
        LocalDate issuedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate due = issuedOn.plusDays(agreement.getCreditPeriodDays());

        var invoice = new CreditInvoice();
        invoice.setCreditAgreementId(agreement.getId());
        invoice.setSupplierOrderId(reservation.getSupplierOrderId());
        invoice.setOutletId(agreement.getOutletId());
        invoice.setSupplierStoreId(agreement.getSupplierStoreId());
        invoice.setInvoiceNumber(invoiceNumbers.next());
        invoice.setAmount(amount);
        invoice.setIssuedAt(now);
        invoice.setDueDate(due);
        // Grace is added on top of the due date, not folded into it. The due date
        // is what the restaurant agreed to pay by; the grace period is how long
        // the supplier will wait past it before calling it overdue. Collapsing
        // them would lose the supplier's ability to chase a late payment that is
        // not yet a default.
        invoice.setOverdueAfter(due.plusDays(agreement.getGracePeriodDays()));
        invoices.save(invoice);

        auditService.record(null, null, "CREDIT_INVOICE_ISSUED", "CREDIT_INVOICE",
                invoice.getId(), null, invoice.getStatus().name(),
                "Order " + (order == null ? reservation.getSupplierOrderId() : order.orderNumber()),
                "SYSTEM");

        outbox.publish("CreditInvoiceIssued", "CREDIT_INVOICE", invoice.getId(),
                Map.of("creditAgreementId", agreement.getId(),
                        "supplierOrderId", reservation.getSupplierOrderId(),
                        "amount", amount.toPlainString(),
                        "dueDate", due.toString()),
                null);

        return invoice;
    }

    /**
     * Record a repayment.
     *
     * <p>Idempotent on the caller's key, enforced by {@code uk_credit_payment_key}
     * rather than by a preceding lookup — doc 04 §21, and a check-then-insert
     * would race with the retry it exists to catch.
     */
    @Transactional
    public CreditDtos.PaymentResponse recordPayment(Long actorId, Long invoiceId,
                                                    CreditDtos.RecordPaymentRequest request,
                                                    String idempotencyKey) {

        var duplicate = payments.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (duplicate != null) {
            return toResponse(duplicate);
        }

        var invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("CreditInvoice", invoiceId));

        // **The supplier records this, not the restaurant.** The money moved
        // outside Mandi, so the only party who can confirm it arrived is the one
        // it arrived at. Letting the debtor mark their own debt paid would clear
        // the balance and free the credit again on nothing but their say-so.
        accessControl.requireScoped(actorId, Permissions.CREDIT_MODIFY,
                ScopeType.SUPPLIER_STORE, invoice.getSupplierStoreId(), "CreditInvoice");

        if (invoice.getStatus().isSettled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This invoice is already settled.");
        }
        if (request.amount().compareTo(invoice.outstanding()) > 0) {
            // Refused rather than accepted as a credit balance. An overpayment is
            // usually a typo, and turning one into spending power is worse than
            // asking someone to check the number.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That's more than the %s outstanding on this invoice."
                            .formatted(invoice.outstanding().toPlainString()));
        }

        var payment = new CreditPayment();
        payment.setCreditInvoiceId(invoiceId);
        payment.setCreditAgreementId(invoice.getCreditAgreementId());
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        payment.setReference(request.reference());
        payment.setNote(request.note());
        payment.setPaidAt(request.paidAt() == null ? Instant.now() : request.paidAt());
        payment.setRecordedBy(actorId);
        payment.setIdempotencyKey(idempotencyKey);
        payments.save(payment);

        invoice.setPaidAmount(invoice.getPaidAmount().add(request.amount()));
        boolean settled = invoice.outstanding().signum() == 0;
        invoice.setStatus(settled ? CreditInvoiceStatus.PAID : CreditInvoiceStatus.PARTIALLY_PAID);
        if (settled) {
            invoice.setSettledAt(Instant.now());
        }
        invoices.save(invoice);

        // The debt and the exposure move together. A repayment that reduced one
        // without the other would leave the restaurant's available credit wrong in
        // whichever direction the missing half pointed.
        ledger.repay(invoice.getCreditAgreementId(), invoiceId, request.amount(), actorId);

        auditService.record(actorId, null, "CREDIT_PAYMENT_RECORDED", "CREDIT_INVOICE",
                invoiceId, null, invoice.getStatus().name(),
                request.amount().toPlainString() + " via " + request.method(), "API");

        outbox.publish("CreditRepaymentRecorded", "CREDIT_INVOICE", invoiceId,
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "amount", request.amount().toPlainString(),
                        "status", invoice.getStatus().name()),
                actorId);

        return toResponse(payment);
    }

    /**
     * Mark invoices whose grace period has run out.
     *
     * <p>Separate from suspension on purpose: an invoice becoming overdue is a
     * fact about a date, while suspending a credit line is a supplier's policy
     * decision about that fact. {@code CreditJobs} applies the second only where
     * the supplier asked for it.
     */
    @Transactional
    public int markOverdue() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var newlyOverdue = invoices.findNewlyOverdue(
                List.of(CreditInvoiceStatus.ISSUED, CreditInvoiceStatus.PARTIALLY_PAID), today);

        for (CreditInvoice invoice : newlyOverdue) {
            invoice.setStatus(CreditInvoiceStatus.OVERDUE);
            invoice.setMarkedOverdueAt(Instant.now());
            invoices.save(invoice);

            outbox.publish("CreditOverdue", "CREDIT_INVOICE", invoice.getId(),
                    Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                            "outletId", invoice.getOutletId(),
                            "outstanding", invoice.outstanding().toPlainString(),
                            "dueDate", invoice.getDueDate().toString()),
                    null);

            log.info("Invoice {} is overdue — {} outstanding since {}",
                    invoice.getInvoiceNumber(), invoice.outstanding(), invoice.getDueDate());
        }
        return newlyOverdue.size();
    }

    /** What is owed, and what is late, for one agreement. */
    @Transactional(readOnly = true)
    public Dues duesFor(Long agreementId) {
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;

        for (CreditInvoice invoice : invoices.findByCreditAgreementIdOrderByDueDateAsc(agreementId)) {
            if (invoice.getStatus().isSettled()) {
                continue;
            }
            due = due.add(invoice.outstanding());
            if (invoice.getStatus() == CreditInvoiceStatus.OVERDUE) {
                overdue = overdue.add(invoice.outstanding());
            }
        }
        // Overdue is a subset of due, not a separate bucket. Doc 04 §13 lists them
        // separately because they answer different questions — "what do I owe" and
        // "what am I late on" — and a restaurant reading them as disjoint would
        // think they owed the sum of the two.
        return new Dues(due, overdue);
    }

    public record Dues(BigDecimal due, BigDecimal overdue) {
    }

    private CreditDtos.PaymentResponse toResponse(CreditPayment payment) {
        return new CreditDtos.PaymentResponse(
                payment.getId(), payment.getCreditInvoiceId(), payment.getAmount(),
                payment.getMethod(), payment.getReference(), payment.getPaidAt());
    }
}
