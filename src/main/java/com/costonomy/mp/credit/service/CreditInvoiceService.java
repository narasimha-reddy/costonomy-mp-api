package com.costonomy.mp.credit.service;

import com.costonomy.mp.credit.domain.CreditEvents;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final jakarta.persistence.EntityManager entityManager;
    private final CreditOverdueMarker overdueMarker;
    // Field-injected: Lombok's constructor would drop the @Qualifier, and there must be no unqualified Clock here.
    @Autowired
    @Qualifier("creditClock")
    private Clock clock;

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

        Instant now = clock.instant();
        // India date, like the rest of the app: an invoice issued at 00:30 IST is dated that day.
        LocalDate issuedOn = LocalDate.ofInstant(now, clock.getZone());
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

        outbox.publish(CreditEvents.INVOICE_ISSUED, "CREDIT_INVOICE", invoice.getId(),
                Map.of("creditAgreementId", agreement.getId(),
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "invoiceNumber", invoice.getInvoiceNumber(),
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

        // Locked before it is read for the arithmetic below: a wallet repayment can be settling the same invoice
        // at this moment, and both must see the other's result, not the row as it was a moment ago (D-123).
        var invoice = invoices.lockById(invoiceId)
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

        var payment = applyPayment(invoice, request.amount(), request.method(), request.reference(),
                request.note(), request.paidAt() == null ? Instant.now() : request.paidAt(), actorId,
                idempotencyKey, CreditPaymentSource.SUPPLIER_RECORDED, null);

        auditService.record(actorId, null, "CREDIT_PAYMENT_RECORDED", "CREDIT_INVOICE",
                invoiceId, null, invoice.getStatus().name(),
                request.amount().toPlainString() + " via " + request.method(), "API");

        outbox.publish(CreditEvents.REPAYMENT_RECORDED, "CREDIT_INVOICE", invoiceId,
                Map.of("creditAgreementId", invoice.getCreditAgreementId(),
                        "outletId", invoice.getOutletId(),
                        "supplierStoreId", invoice.getSupplierStoreId(),
                        "supplierName", supplierNameOf(invoice.getSupplierStoreId()),
                        "invoiceNumber", invoice.getInvoiceNumber(),
                        "amount", request.amount().toPlainString(),
                        "status", invoice.getStatus().name()),
                actorId);

        return toResponse(payment);
    }

    /**
     * The one place a payment reduces a debt: the credit_payment row, the invoice's paid amount and status, the
     * exposure ledger, and the auto-reinstate hook, all in the caller's transaction (D-118, D-119, D-123).
     * Both the supplier recording a payment and a restaurant repaying from its wallet come through here, so the
     * status rule and the exposure arithmetic cannot drift between them.
     *
     * <p>The invoice must already be locked ({@code CreditInvoiceRepository.lockById} or one of the
     * agreement locks) and the amount already checked against what is outstanding. Audit and the outbox event
     * stay with the caller, because who is told differs: the restaurant for a supplier-recorded payment, the
     * supplier for a wallet repayment.
     */
    public CreditPayment applyPayment(CreditInvoice invoice, BigDecimal amount, String method, String reference,
                                      String note, Instant paidAt, Long actorId, String idempotencyKey,
                                      CreditPaymentSource source, Long repaymentId) {
        var payment = new CreditPayment();
        payment.setCreditInvoiceId(invoice.getId());
        payment.setCreditAgreementId(invoice.getCreditAgreementId());
        payment.setAmount(amount);
        payment.setMethod(method);
        payment.setReference(reference);
        payment.setNote(note);
        payment.setPaidAt(paidAt);
        payment.setRecordedBy(actorId);
        payment.setIdempotencyKey(idempotencyKey);
        payment.setSource(source);
        payment.setCreditRepaymentId(repaymentId);
        payments.save(payment);

        invoice.setPaidAmount(invoice.getPaidAmount().add(amount));
        boolean settled = invoice.outstanding().signum() == 0;
        if (settled) {
            invoice.setStatus(CreditInvoiceStatus.PAID);
        } else if (invoice.getStatus() != CreditInvoiceStatus.OVERDUE) {
            // A part payment does not cure lateness: an overdue invoice stays overdue until it is paid.
            invoice.setStatus(CreditInvoiceStatus.PARTIALLY_PAID);
        }
        if (settled) {
            invoice.setSettledAt(Instant.now());
        }
        invoices.save(invoice);

        // The debt and the exposure move together. A repayment that reduced one
        // without the other would leave the restaurant's available credit wrong in
        // whichever direction the missing half pointed.
        ledger.repay(invoice.getCreditAgreementId(), invoice.getId(), amount, actorId);
        reinstateIfOverdueCleared(invoice.getCreditAgreementId());
        return payment;
    }

    /**
     * Lift a suspension the overdue sweep imposed, once what is overdue is back within the supplier's tolerance.
     * Call it in the same transaction as any repayment. A suspension by a supplier user is never lifted here.
     */
    public void reinstateIfOverdueCleared(Long agreementId) {
        var agreement = agreements.findById(agreementId).orElse(null);
        if (agreement != null) {
            // The exposure columns and the version move by SQL (CreditExposureStore), so an instance this
            // transaction loaded earlier, for an earlier invoice of the same repayment, is stale: saving it
            // would fail on its version. Read it again.
            entityManager.refresh(agreement);
        }
        if (agreement == null
                || agreement.getStatus() != CreditAgreementStatus.SUSPENDED
                || agreement.getSuspensionSource() != SuspensionSource.SYSTEM
                || !agreement.getStatus().canTransitionTo(CreditAgreementStatus.ACTIVE)) {
            return;
        }
        BigDecimal max = agreement.getMaxOverdueAmount();
        if (max != null && duesFor(agreementId).overdue().compareTo(max) > 0) {
            return;
        }

        agreement.setStatus(CreditAgreementStatus.ACTIVE);
        agreement.setSuspendedAt(null);
        agreement.setSuspensionReason(null);
        agreement.setSuspensionSource(null);
        agreements.save(agreement);

        auditService.record(null, null, "CREDIT_REINSTATED", "CREDIT_AGREEMENT", agreementId,
                CreditAgreementStatus.SUSPENDED.name(), CreditAgreementStatus.ACTIVE.name(),
                "Overdue balance cleared", "SYSTEM");

        outbox.publish(CreditEvents.REINSTATED, "CREDIT_AGREEMENT", agreementId,
                Map.of("creditAgreementId", agreementId,
                        "outletId", agreement.getOutletId(),
                        "supplierStoreId", agreement.getSupplierStoreId(),
                        "supplierName", supplierNameOf(agreement.getSupplierStoreId())),
                null);
    }

    private String supplierNameOf(Long supplierStoreId) {
        var store = directory.store(supplierStoreId);
        return store == null || store.supplierName() == null ? "" : store.supplierName();
    }

    /**
     * Mark invoices whose grace period has run out.
     *
     * <p>Separate from suspension on purpose: an invoice becoming overdue is a
     * fact about a date, while suspending a credit line is a supplier's policy
     * decision about that fact. {@code CreditJobs} applies the second only where
     * the supplier asked for it.
     */
    public int markOverdue() {
        LocalDate today = LocalDate.now(clock);
        var candidates = invoices.findNewlyOverdue(
                List.of(CreditInvoiceStatus.ISSUED, CreditInvoiceStatus.PARTIALLY_PAID), today);

        int marked = 0;
        for (CreditInvoice candidate : candidates) {
            try {
                if (overdueMarker.markOverdue(candidate.getId(), today)) {
                    marked++;
                }
            } catch (RuntimeException ex) {
                // One invoice must not stop the rest; the next sweep tries it again.
                log.warn("Could not mark credit invoice {} overdue; skipped this sweep", candidate.getId(), ex);
            }
        }
        return marked;
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
