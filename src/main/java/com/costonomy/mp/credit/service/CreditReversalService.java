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
import com.costonomy.mp.credit.domain.CreditClaimStatus;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayment;
import com.costonomy.mp.credit.domain.CreditPaymentReversal;
import com.costonomy.mp.credit.domain.CreditPaymentSource;
import com.costonomy.mp.credit.domain.CreditRepaymentSource;
import com.costonomy.mp.credit.domain.CreditRepaymentStatus;
import com.costonomy.mp.credit.domain.CreditReversalRules;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentClaimRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRepository;
import com.costonomy.mp.credit.repository.CreditPaymentReversalRepository;
import com.costonomy.mp.credit.repository.CreditRepaymentRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The supplier undoes a payment it recorded (B6, D-140): a reversal, never a delete. A typo (₹50,000 for ₹5,000) or a
 * bounced cheque puts the debt back, with a reason, in a window; money the restaurant paid through Mandi is never the
 * supplier's to take back.
 *
 * <p>Like {@link CreditSupplierPaymentService} this is a plain bean around a {@link TransactionTemplate}, because the
 * work runs inside {@code IdempotencyService.execute} (D-016). The effect is {@code applyPayment} run backwards, in
 * one transaction: the claim (if the payment confirmed one), then the invoices in ascending id order, then the
 * agreement's exposure (inside {@link CreditLedger#reverse}), the lock order every money path uses. Each invoice's
 * paid amount goes down by its allocation and its status comes back from {@link CreditReversalRules#statusAfter}; the
 * line's utilised figure goes up by the same amount in a statement that refuses to pass the approved limit, and a
 * PAYMENT_REVERSED ledger row is written for each invoice, so the statement's opening + movements = closing holds.
 *
 * <p>The payments stay as they were. A {@code credit_payment_reversal} row beside each says it was taken back; every
 * sum of money collected and every duplicate-reference check leaves those out.
 *
 * <p>A receipt is reversed as one: a receipt that put money on three invoices comes back off all three or none.
 * A payment that belongs to a receipt cannot be reversed alone.
 *
 * <p>A refusal is a failed idempotency key like any other (the key cannot be used again), so the app sends a fresh key
 * for each attempt, for instance once the limit has been raised after CREDIT_REVERSAL_NO_HEADROOM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditReversalService {

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"));

    private final CreditAgreementRepository agreements;
    private final CreditInvoiceRepository invoices;
    private final CreditRepaymentRepository repayments;
    private final CreditPaymentRepository payments;
    private final CreditPaymentReversalRepository reversals;
    private final CreditPaymentClaimRepository claims;
    private final CreditLedger ledger;
    private final CreditExposureStore exposure;
    private final CreditInvoiceService invoiceService;
    private final CreditDirectory directory;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService auditService;
    private final OutboxService outbox;
    private final TransactionTemplate txTemplate;

    // ── entry points ─────────────────────────────────────────────────────

    /** Undo a whole receipt (D-134): every payment it wrote comes back off its invoice. */
    public CreditDtos.ReversalResponse reverseReceipt(Long actorId, Long receiptId, String reason, String idempotencyKey) {
        var receipt = repayments.findById(receiptId).orElseThrow(() -> new NotFoundException("CreditRepayment", receiptId));
        authorize(actorId, receipt.getSupplierStoreId(), "CreditRepayment", receiptId);
        String why = reason.trim();
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);
        try (var trace = TraceScope.of("credit-repayment", receiptId)) {
            return idempotency.execute(actorId, "credit.receipt-reversal", idempotencyKey,
                    payload("RECEIPT", receiptId, why), CreditDtos.ReversalResponse.class,
                    () -> txTemplate.execute(status -> {
                        if (receipt.getSource() != CreditRepaymentSource.SUPPLIER_RECORDED) {
                            throw paidThroughMandi();
                        }
                        var rows = payments.findByCreditRepaymentIdOrderByIdAsc(receiptId);
                        return work(actorId, receipt.getCreditAgreementId(), rows, receiptId, why, storedKey);
                    }));
        }
    }

    /** Undo one payment recorded alone (one invoice at a time, or a confirmed claim). */
    public CreditDtos.ReversalResponse reversePayment(Long actorId, Long paymentId, String reason, String idempotencyKey) {
        var payment = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("CreditPayment", paymentId));
        var agreement = agreements.findById(payment.getCreditAgreementId())
                .orElseThrow(() -> new NotFoundException("CreditPayment", paymentId));
        authorize(actorId, agreement.getSupplierStoreId(), "CreditPayment", paymentId);
        String why = reason.trim();
        String storedKey = CreditInvoiceService.supplierKey(actorId, idempotencyKey);
        try (var trace = TraceScope.of("credit-payment", paymentId)) {
            return idempotency.execute(actorId, "credit.payment-reversal", idempotencyKey,
                    payload("PAYMENT", paymentId, why), CreditDtos.ReversalResponse.class,
                    () -> txTemplate.execute(status -> {
                        if (!CreditReversalRules.sourceAllowed(payment.getSource())) {
                            throw paidThroughMandi();
                        }
                        if (payment.getCreditRepaymentId() != null) {
                            throw new BusinessException(ErrorCode.CREDIT_REVERSAL_NOT_ALLOWED,
                                    "This payment is part of a receipt. Undo the whole receipt.",
                                    Map.of("receiptId", payment.getCreditRepaymentId()));
                        }
                        return work(actorId, payment.getCreditAgreementId(), List.of(payment), null, why, storedKey);
                    }));
        }
    }

    static Map<String, Object> payload(String kind, Long id, String reason) {
        var map = new LinkedHashMap<String, Object>();
        map.put("kind", kind);
        map.put("id", id);
        map.put("reason", reason);
        return map;
    }

    // ── the reversal ─────────────────────────────────────────────────────

    private CreditDtos.ReversalResponse work(Long actorId, Long agreementId, List<CreditPayment> rows, Long receiptId,
                                             String reason, String storedKey) {
        var ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(CreditPayment::getCreditInvoiceId));
        for (var p : ordered) {
            if (!CreditReversalRules.sourceAllowed(p.getSource())) {
                throw paidThroughMandi();
            }
        }

        // a. Claim first (the payment of a confirmed claim), then the invoices ascending: the one order every taker uses.
        var claimsOfPayments = new HashMap<Long, com.costonomy.mp.credit.domain.CreditPaymentClaim>();
        for (var p : ordered) {
            if (p.getClaimId() != null) {
                claimsOfPayments.put(p.getId(), claims.lockById(p.getClaimId())
                        .orElseThrow(() -> new NotFoundException("CreditPaymentClaim", p.getClaimId())));
            }
        }
        var locked = new HashMap<Long, CreditInvoice>();
        for (var p : ordered) {
            locked.computeIfAbsent(p.getCreditInvoiceId(), id -> invoices.lockById(id)
                    .orElseThrow(() -> new NotFoundException("CreditInvoice", id)));
        }

        // b. With the invoices held nobody else can be reversing or paying them: what is true now is checked.
        if (!reversals.lockReversedAmong(ordered.stream().map(CreditPayment::getId).toList()).isEmpty()) {
            throw new BusinessException(ErrorCode.CREDIT_ALREADY_REVERSED, "This payment was already undone.");
        }
        for (var invoice : locked.values()) {
            if (invoice.getStatus() == CreditInvoiceStatus.WRITTEN_OFF) {
                throw new BusinessException(ErrorCode.CREDIT_REVERSAL_NOT_ALLOWED,
                        "Invoice %s was written off, so a payment on it can't be undone.".formatted(invoice.getInvoiceNumber()));
            }
        }
        LocalDate today = invoiceService.today();
        var zone = invoiceService.zone();
        LocalDate lastDay = ordered.stream().map(p -> CreditReversalRules.lastDay(p.getCreatedAt(), p.getMethod(), zone))
                .min(Comparator.naturalOrder()).orElseThrow();
        if (!CreditReversalRules.isOpen(lastDay, today)) {
            throw new BusinessException(ErrorCode.CREDIT_REVERSAL_WINDOW_CLOSED,
                    "It's too late to undo this payment: it could be undone until %s. Issue a credit note or record the difference instead."
                            .formatted(lastDay),
                    Map.of("closedOn", lastDay.plusDays(1).toString(), "reversibleUntil", lastDay.toString()));
        }

        // c. Each payment off its invoice, the debt back on the line. The ledger's conditional update refuses what would
        // not fit under the limit; the whole transaction then rolls back, so a receipt is undone entirely or not at all.
        BigDecimal total = ordered.stream().map(CreditPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal applied = BigDecimal.ZERO;
        Instant now = Instant.now();
        var allocations = new ArrayList<CreditDtos.WalletRepaymentAllocation>();
        for (var p : ordered) {
            var invoice = locked.get(p.getCreditInvoiceId());
            BigDecimal paidAfter = invoice.getPaidAmount().subtract(p.getAmount());
            if (paidAfter.signum() < 0) {
                // Cannot happen while every payment goes through applyPayment; refuse rather than write a negative.
                throw new IllegalStateException("Invoice %d paid %s is less than the payment %d of %s being reversed"
                        .formatted(invoice.getId(), invoice.getPaidAmount(), p.getId(), p.getAmount()));
            }
            invoice.setPaidAmount(paidAfter);
            invoice.setStatus(CreditReversalRules.statusAfter(invoice.getOverdueAfter(), paidAfter, today));
            invoice.setSettledAt(null);
            if (invoice.getStatus() == CreditInvoiceStatus.OVERDUE && invoice.getMarkedOverdueAt() == null) {
                invoice.setMarkedOverdueAt(now);
            }
            invoices.save(invoice);

            var reversal = new CreditPaymentReversal();
            reversal.setCreditPaymentId(p.getId());
            reversal.setReceiptId(receiptId);
            reversal.setCreditInvoiceId(invoice.getId());
            reversal.setCreditAgreementId(agreementId);
            reversal.setAmount(p.getAmount());
            reversal.setReason(reason);
            reversal.setReversedBy(actorId);
            reversal.setIdempotencyKey(storedKey + ":" + p.getId());
            reversals.save(reversal);

            if (!ledger.reverse(agreementId, invoice.getId(), p.getAmount(),
                    // Short on purpose: the ledger's description column is not wide enough for a 500-character reason,
                    // which lives on the reversal row and in the audit row.
                    "Payment %d reversed on invoice %s".formatted(p.getId(), invoice.getInvoiceNumber()), actorId)) {
                BigDecimal available = exposure.read(agreementId).available().add(applied).max(BigDecimal.ZERO);
                throw new BusinessException(ErrorCode.CREDIT_REVERSAL_NO_HEADROOM,
                        "Putting back ₹%s would take this line past its limit: ₹%s is available. Raise their limit by ₹%s first."
                                .formatted(Rupees.of(total), Rupees.of(available), Rupees.of(total.subtract(available))),
                        Map.of("needed", money(total), "available", money(available),
                                "shortBy", money(total.subtract(available))));
            }
            applied = applied.add(p.getAmount());
            allocations.add(new CreditDtos.WalletRepaymentAllocation(invoice.getId(), invoice.getInvoiceNumber(),
                    money(p.getAmount()), invoice.getStatus()));
        }

        // d. A claim the payment confirmed goes back to REJECTED: it is no longer money received. A rejected claim
        // counts toward nothing, so the caps (what can still be claimed) are the invoice's debt again.
        for (var claim : claimsOfPayments.values()) {
            if (claim.getStatus() == CreditClaimStatus.CONFIRMED) {
                claim.setStatus(CreditClaimStatus.REJECTED);
                claim.setDecisionNote("Payment reversed by supplier");
                claim.setConfirmedAmount(null);
                claim.setDecidedBy(actorId);
                claim.setDecidedAt(now);
                claims.save(claim);
                auditService.record(actorId, null, "CREDIT_CLAIM_REJECTED", "CREDIT_PAYMENT_CLAIM", claim.getId(),
                        CreditClaimStatus.CONFIRMED.name(), CreditClaimStatus.REJECTED.name(),
                        "Payment reversed by supplier: " + reason, "API");
            }
        }
        if (receiptId != null) {
            var receipt = repayments.findById(receiptId).orElseThrow();
            receipt.setStatus(CreditRepaymentStatus.REVERSED);
            repayments.save(receipt);
        }

        // e. Audit, and one event for the restaurant (not one per invoice).
        var first = locked.get(ordered.get(0).getCreditInvoiceId());
        String named = locked.size() == 1 ? first.getInvoiceNumber()
                : "%s and %d more".formatted(first.getInvoiceNumber(), locked.size() - 1);
        String entityType = receiptId != null ? "CREDIT_REPAYMENT" : "CREDIT_PAYMENT";
        Long entityId = receiptId != null ? receiptId : ordered.get(0).getId();
        auditService.record(actorId, null, "CREDIT_PAYMENT_REVERSED", entityType, entityId,
                "RECORDED", "REVERSED", reason, "API");
        var agreement = agreements.findById(agreementId).orElseThrow();
        var store = directory.store(agreement.getSupplierStoreId());
        LocalDate recordedOn = LocalDate.ofInstant(ordered.get(0).getCreatedAt(), zone);
        var event = new HashMap<String, Object>();
        event.put("creditAgreementId", agreementId);
        event.put("outletId", agreement.getOutletId());
        event.put("supplierStoreId", agreement.getSupplierStoreId());
        event.put("supplierName", store == null || store.supplierName() == null ? "" : store.supplierName());
        event.put("invoiceNumber", named);
        event.put("amount", money(total).toPlainString());
        event.put("recordedOn", recordedOn.toString());
        event.put("recordedOnLabel", recordedOn.format(LABEL));
        event.put("reason", reason);
        if (receiptId != null) {
            event.put("receiptId", receiptId);
        }
        outbox.publish(CreditEvents.PAYMENT_REVERSED, "CREDIT_INVOICE", first.getId(), event, actorId);
        log.info("Credit {} {} of {} reversed by user {} on agreement {} over {} invoice(s)",
                receiptId != null ? "receipt" : "payment", entityId, Rupees.of(total), actorId, agreementId,
                locked.size());

        // Read after the writes, from the row: the exposure moved by SQL, and the line is as it stands now.
        var dues = invoiceService.duesFor(agreementId);
        var after = agreements.findById(agreementId).orElseThrow();
        return new CreditDtos.ReversalResponse(receiptId, receiptId == null ? ordered.get(0).getId() : null,
                money(total), reason, now, allocations,
                new CreditDtos.RepaymentAgreementState(money(dues.due()), money(dues.overdue()),
                        money(exposure.read(agreementId).available()), after.getStatus()));
    }

    // ── shared pieces ────────────────────────────────────────────────────

    private void authorize(Long actorId, Long storeId, String entity, Long id) {
        // Another store's money, or a role without the permission, is "not found", like every credit endpoint.
        accessControl.requireAnyScoped(actorId, ScopeType.SUPPLIER_STORE, storeId, entity,
                Permissions.CREDIT_COLLECT, Permissions.CREDIT_MODIFY);
    }

    private static BusinessException paidThroughMandi() {
        return new BusinessException(ErrorCode.CREDIT_REVERSAL_NOT_ALLOWED,
                "This was paid through Mandi, so it can't be undone here. Contact support.");
    }

    /** A figure for the app: two places, never rounded up above what it is. */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN);
    }
}
