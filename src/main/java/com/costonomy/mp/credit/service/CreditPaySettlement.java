package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditEvents;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayInvoiceOutcome;
import com.costonomy.mp.credit.domain.CreditPayRefundReason;
import com.costonomy.mp.credit.domain.CreditPaySkipReason;
import com.costonomy.mp.credit.domain.CreditPaymentAttempt;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptInvoice;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import com.costonomy.mp.credit.domain.CreditPaymentRefund;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRefundRepository;
import com.costonomy.mp.payment.provider.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one place a captured credit payment is applied (D-196). Shared by verify (source CONFIRM), the webhook
 * (WEBHOOK) and the poller (POLLER), so the same capture arriving by any path, in any order, concurrently, is applied
 * once. Not transactional itself: it checks the payment outside any transaction and applies it in one short one,
 * like {@code WalletTopUpService.settle}.
 *
 * <p><b>Nothing captured is ever left without a payment or a refund.</b> Each invoice the restaurant chose is paid
 * with the capture if it still owes exactly what it was charged for, and is otherwise skipped, its share going
 * back to the card or UPI it came from as a {@code credit_payment_refund} row (sent by a job). Locks, in order: the
 * attempt row, every selected invoice ascending id, then the agreements ascending id (taken by the money moving
 * through {@link CreditRepaymentApplier}). The unique keys on the payment id, on (attempt, agreement) and on
 * (repayment, invoice) stand behind it.
 */
@Service
@Slf4j
public class CreditPaySettlement {

    /** What {@link #settle} did with the payment it was given. */
    public enum Outcome { APPLIED, ALREADY_APPLIED, PROCESSING, DECLINED, REJECTED, DUPLICATE_REFUNDING }

    private final CreditPaymentAttemptRepository attempts;
    private final CreditPaymentAttemptInvoiceRepository attemptInvoices;
    private final CreditPaymentRefundRepository refunds;
    private final CreditInvoiceRepository invoices;
    private final CreditRepaymentApplier applier;
    private final CreditDirectory directory;
    private final AuditService audit;
    private final OutboxService outbox;
    private final TransactionTemplate tx;

    public CreditPaySettlement(CreditPaymentAttemptRepository attempts,
                               CreditPaymentAttemptInvoiceRepository attemptInvoices,
                               CreditPaymentRefundRepository refunds, CreditInvoiceRepository invoices,
                               CreditRepaymentApplier applier, CreditDirectory directory, AuditService audit,
                               OutboxService outbox, PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.attemptInvoices = attemptInvoices;
        this.refunds = refunds;
        this.invoices = invoices;
        this.applier = applier;
        this.directory = directory;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        // READ COMMITTED, like the wallet top-up: a transaction that waited for the attempt lock must read the
        // invoices as the caller ahead of it left them.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Turn a payment Razorpay reports into invoices paid, a wait, or a refund. No transaction around the checks;
     * the apply is {@link #applyCaptured}, one transaction.
     */
    public Outcome settle(Long attemptId, PaymentProvider.ProviderPayment payment, String source) {
        var attempt = attempts.findById(attemptId).orElseThrow(() -> new NotFoundException("CreditPaymentAttempt", attemptId));
        try (var trace = TraceScope.of("credit_payment_attempt", attemptId, "outlet", attempt.getOutletId(),
                "rzp_order", attempt.getRazorpayOrderId(), "rzp_payment", payment.providerPaymentId())) {
            if (attempt.getRazorpayOrderId() == null
                    || !attempt.getRazorpayOrderId().equals(payment.providerOrderId())) {
                // A real payment, but for some other order. Never applied to this attempt, whoever brought it.
                log.error("Ignoring {} payment {} for credit payment attempt {}: it belongs to order {}, not {}",
                        source, payment.providerPaymentId(), attemptId, payment.providerOrderId(),
                        attempt.getRazorpayOrderId());
                return Outcome.REJECTED;
            }

            switch (payment.status()) {
                case CAPTURED -> { }
                case CREATED, AUTHORIZED -> {
                    log.info("Credit payment attempt {} payment {} is {} at Razorpay; waiting for capture",
                            attemptId, payment.providerPaymentId(), payment.status());
                    return Outcome.PROCESSING;
                }
                case FAILED -> {
                    log.info("Credit payment attempt {} payment attempt {} failed: {}",
                            attemptId, payment.providerPaymentId(), payment.failureCode());
                    return Outcome.DECLINED;
                }
                default -> {
                    log.warn("Credit payment attempt {} payment {} is {} at Razorpay, not captured; nothing applied",
                            attemptId, payment.providerPaymentId(), payment.status());
                    return Outcome.REJECTED;
                }
            }

            // The amount we stored, not the amount anyone claims. Razorpay fixes an order's amount, so this should
            // never differ; if it does, a person, not this code, decides what to do with money that does not match.
            if (payment.capturedAmount() == null || payment.capturedAmount().compareTo(attempt.getTotalCharged()) != 0) {
                log.error("Credit payment attempt {}: {} payment {} captured {} but the attempt is for {}; "
                                + "NOT applied and NOT refunded — needs a person",
                        attemptId, source, payment.providerPaymentId(), payment.capturedAmount(),
                        attempt.getTotalCharged().toPlainString());
                return Outcome.REJECTED;
            }

            if (isApplied(attempt) && payment.providerPaymentId().equals(attempt.getRazorpayPaymentId())) {
                return Outcome.ALREADY_APPLIED;
            }
            return tx.execute(status -> applyCaptured(attemptId, payment, source));
        }
    }

    private static boolean isApplied(CreditPaymentAttempt attempt) {
        return attempt.getStatus() == CreditPaymentAttemptStatus.PAID
                || attempt.getStatus() == CreditPaymentAttemptStatus.REFUNDED;
    }

    /** The one transaction. Attempt row first, then the invoices, then (in the applier) the agreements. */
    private Outcome applyCaptured(Long attemptId, PaymentProvider.ProviderPayment payment, String source) {
        // 1. The attempt, held. Everything below sees what the last committer left.
        var attempt = attempts.lockById(attemptId).orElseThrow(() -> new NotFoundException("CreditPaymentAttempt", attemptId));
        switch (attempt.getStatus()) {
            case PAID, REFUNDED -> {
                if (payment.providerPaymentId().equals(attempt.getRazorpayPaymentId())) {
                    return Outcome.ALREADY_APPLIED;
                }
                // A second captured payment on an attempt that is already applied: all of it goes back.
                if (refunds.findByRazorpayPaymentIdAndReason(payment.providerPaymentId(),
                        CreditPayRefundReason.DUPLICATE_CAPTURE).isEmpty()) {
                    var refund = new CreditPaymentRefund();
                    refund.setAttemptId(attemptId);
                    refund.setRazorpayPaymentId(payment.providerPaymentId());
                    refund.setReason(CreditPayRefundReason.DUPLICATE_CAPTURE);
                    refund.setAmount(payment.capturedAmount());
                    refunds.saveAndFlush(refund);
                    audit.record(null, null, "CREDIT_PAY_DUPLICATE_CAPTURE", "CREDIT_PAYMENT_ATTEMPT", attemptId,
                            attempt.getStatus().name(), attempt.getStatus().name(),
                            payment.capturedAmount().toPlainString() + " via " + payment.providerPaymentId()
                                    + " from " + source + ", already applied via " + attempt.getRazorpayPaymentId(),
                            "SYSTEM");
                    log.warn("Credit payment attempt {} was already applied via {}; second payment {} of {} goes back",
                            attemptId, attempt.getRazorpayPaymentId(), payment.providerPaymentId(),
                            payment.capturedAmount().toPlainString());
                }
                return Outcome.DUPLICATE_REFUNDING;
            }
            case FAILED -> {
                // Cannot be: a FAILED attempt never had an order. Nothing to apply it to.
                return Outcome.REJECTED;
            }
            default -> { }
            // CREATED, or EXPIRED: a payment that turns up late is still applied.
        }

        // 2. This payment is now this attempt's.
        Instant now = Instant.now();
        var oldStatus = attempt.getStatus();
        attempt.setRazorpayPaymentId(payment.providerPaymentId());
        attempt.setPaymentMethod(payment.method());
        attempt.setPaymentDetail(payment.methodDetail());

        // 3. Every selected invoice, ascending id, before any agreement is touched.
        var rows = attemptInvoices.findByAttemptIdOrderByCreditInvoiceIdAsc(attemptId);
        var locked = new HashMap<Long, CreditInvoice>();
        for (CreditInvoice invoice : invoices.lockByOutletAndIds(attempt.getOutletId(),
                rows.stream().map(CreditPaymentAttemptInvoice::getCreditInvoiceId).toList())) {
            locked.put(invoice.getId(), invoice);
        }

        // 4. Pay it, or skip it and say why.
        var toPay = new TreeMap<Long, List<CreditPaymentAttemptInvoice>>();
        var skipped = new ArrayList<CreditPaymentAttemptInvoice>();
        for (var row : rows) {
            var invoice = locked.get(row.getCreditInvoiceId());
            CreditPaySkipReason reason = skipReason(row, invoice);
            if (reason == null) {
                toPay.computeIfAbsent(row.getCreditAgreementId(), k -> new ArrayList<>()).add(row);
            } else {
                row.setOutcome(CreditPayInvoiceOutcome.SKIPPED);
                row.setSkipReason(reason);
                skipped.add(row);
            }
        }

        // 5. Agreements ascending id (the TreeMap), lines oldest due date first, one repayment per supplier line.
        BigDecimal paid = BigDecimal.ZERO;
        int paidCount = 0;
        String methodCode = mapMethod(payment.method());
        for (var group : toPay.entrySet()) {
            Long agreementId = group.getKey();
            var groupRows = group.getValue();
            var lines = groupRows.stream()
                    .map(row -> new CreditRepaymentApplier.Line(locked.get(row.getCreditInvoiceId()), row.getExpectedAmount()))
                    .sorted(Comparator.comparing((CreditRepaymentApplier.Line l) -> l.invoice().getDueDate())
                            .thenComparing(l -> l.invoice().getId()))
                    .toList();
            var applied = applier.apply(attempt.getCreatedBy(), agreementId, attempt.getOutletId(),
                    groupRows.get(0).getSupplierStoreId(), lines,
                    CreditRepaymentApplier.Funding.online(attemptId, agreementId, methodCode,
                            payment.providerPaymentId(), methodLabel(methodCode)), now);
            for (var row : groupRows) {
                row.setOutcome(CreditPayInvoiceOutcome.PAID);
                row.setCreditRepaymentId(applied.repaymentId());
                row.setCreditPaymentId(applied.paymentIdByInvoice().get(row.getCreditInvoiceId()));
                paid = paid.add(row.getExpectedAmount());
                paidCount++;
            }
        }

        // 6. What did not get paid goes back to where it came from. If nothing was paid, all of it, the fee too.
        BigDecimal refundAmount = BigDecimal.ZERO;
        CreditPaymentRefund refund = null;
        if (!skipped.isEmpty()) {
            refundAmount = paidCount == 0
                    ? attempt.getTotalCharged()
                    : skipped.stream().map(CreditPaymentAttemptInvoice::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            refund = new CreditPaymentRefund();
            refund.setAttemptId(attemptId);
            refund.setRazorpayPaymentId(payment.providerPaymentId());
            refund.setReason(CreditPayRefundReason.SKIPPED_INVOICES);
            refund.setAmount(refundAmount);
            refunds.saveAndFlush(refund);
            for (var row : skipped) {
                row.setCreditPaymentRefundId(refund.getId());
            }
        }
        attemptInvoices.saveAll(rows);

        // 7. The attempt is finished.
        var finalStatus = paidCount > 0 ? CreditPaymentAttemptStatus.PAID : CreditPaymentAttemptStatus.REFUNDED;
        attempt.setStatus(finalStatus);
        attempt.setPaidAt(now);
        attempt.setOpenSelectionKey(null);
        attempts.saveAndFlush(attempt);

        // 8. Audit, and the restaurant's notices.
        audit.record(null, null,
                finalStatus == CreditPaymentAttemptStatus.PAID ? "CREDIT_PAY_ATTEMPT_PAID" : "CREDIT_PAY_ATTEMPT_REFUNDED",
                "CREDIT_PAYMENT_ATTEMPT", attemptId, oldStatus.name(), finalStatus.name(),
                attempt.getTotalCharged().toPlainString() + " via " + payment.providerPaymentId() + " from " + source,
                "SYSTEM");
        if (paidCount > 0 && !"CONFIRM".equals(source)) {
            // Applied after it left the screen: the restaurant is told. When the app was there it saw the result.
            var payload = new HashMap<String, Object>();
            payload.put("attemptId", attemptId);
            payload.put("outletId", attempt.getOutletId());
            payload.put("amount", paid.toPlainString());
            payload.put("invoiceCount", paidCount);
            payload.put("invoiceCountText", countText(paidCount));
            outbox.publish(CreditEvents.PAYMENT_COMPLETED, "CREDIT_PAYMENT_ATTEMPT", attemptId, payload, null);
        }
        if (refund != null) {
            var payload = new HashMap<String, Object>();
            payload.put("attemptId", attemptId);
            payload.put("outletId", attempt.getOutletId());
            payload.put("amount", refundAmount.toPlainString());
            payload.put("methodLabel", refundLabel(methodCode));
            payload.put("skippedText", countText(skipped.size()));
            outbox.publish(CreditEvents.PAYMENT_REFUND, "CREDIT_PAYMENT_ATTEMPT", attemptId, payload, null);
        }
        log.info("Credit payment attempt {} applied from {}: {} invoice(s) paid ({}), {} skipped, refund {}",
                attemptId, source, paidCount, Rupees.of(paid), skipped.size(), Rupees.of(refundAmount));
        return Outcome.APPLIED;
    }

    /** Null when the invoice can be paid with this capture; otherwise why it cannot. */
    private CreditPaySkipReason skipReason(CreditPaymentAttemptInvoice row, CreditInvoice invoice) {
        if (invoice == null) {
            return CreditPaySkipReason.AMOUNT_CHANGED;
        }
        if (invoice.getStatus() == CreditInvoiceStatus.PAID) {
            return CreditPaySkipReason.SETTLED;
        }
        if (invoice.getStatus() == CreditInvoiceStatus.WRITTEN_OFF) {
            return CreditPaySkipReason.WRITTEN_OFF;
        }
        var store = directory.store(row.getSupplierStoreId());
        if (store == null || !store.tradeable()) {
            return CreditPaySkipReason.SUPPLIER_UNAVAILABLE;
        }
        if (invoice.outstanding().compareTo(row.getExpectedAmount()) != 0) {
            // A credit note, a part payment, a reversal that raised it, a cancellation note.
            return CreditPaySkipReason.AMOUNT_CHANGED;
        }
        return null;
    }

    /** Razorpay's lower-case method as the credit payment's method code. */
    static String mapMethod(String razorpayMethod) {
        if (razorpayMethod == null) {
            return "OTHER";
        }
        return switch (razorpayMethod.toLowerCase(java.util.Locale.ROOT)) {
            case "upi" -> "UPI";
            case "card" -> "CARD";
            case "netbanking" -> "NETBANKING";
            case "wallet" -> "PROVIDER_WALLET";
            case "emi" -> "EMI";
            default -> "OTHER";
        };
    }

    /** For the supplier's notification: "by UPI", "by card", "by net banking", "by online". */
    private static String methodLabel(String methodCode) {
        return switch (methodCode) {
            case "UPI" -> "UPI";
            case "CARD" -> "card";
            case "NETBANKING" -> "net banking";
            default -> "online";
        };
    }

    /** For the restaurant's refund notice: where the money is going back to. */
    private static String refundLabel(String methodCode) {
        return switch (methodCode) {
            case "UPI" -> "UPI";
            case "CARD" -> "card";
            case "NETBANKING" -> "bank account";
            default -> "payment method";
        };
    }

    private static String countText(int count) {
        return count == 1 ? "1 invoice" : count + " invoices";
    }
}
