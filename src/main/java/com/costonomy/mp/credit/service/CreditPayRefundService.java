package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.credit.domain.CreditPayRefundStatus;
import com.costonomy.mp.credit.domain.CreditPaymentRefund;
import com.costonomy.mp.credit.repository.CreditPaymentRefundRepository;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sends the refunds the settlement wrote (D-196): money captured for a credit payment that was not applied, back to the
 * card or UPI it came from. Modelled on the wallet top-up refund (D-107): a provider call with no transaction open and
 * a key derived from the refund row, so a retry after a timeout reaches the same refund at Razorpay, never a second.
 *
 * <p>The receipt prefix {@code mandi-creditpay-refund-} is deliberately not {@code mandi-refund-}, so the order refund
 * matching and audit ignore it. Not gated by the online-pay flag.
 */
@Service
@Slf4j
public class CreditPayRefundService {

    /** Failed sends before it is left for a person (D-101's shape). */
    static final int MAX_ATTEMPTS = 5;

    /** How often a refund still to send or confirm is tried again. */
    static final Duration RECHECK = Duration.ofMinutes(1);

    static final String RECEIPT_PREFIX = "mandi-creditpay-refund-";

    private final CreditPaymentRefundRepository refunds;
    private final PaymentProvider provider;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public CreditPayRefundService(CreditPaymentRefundRepository refunds, PaymentProvider provider,
                                  AuditService audit, PlatformTransactionManager transactionManager) {
        this.refunds = refunds;
        this.provider = provider;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Send (or ask after) one refund. No transaction is open around the provider call.
     *
     * @return COMPLETED once Razorpay says it is processed; PENDING otherwise (accepted, or failed and to be retried)
     */
    public CreditPayRefundStatus send(Long refundId) {
        var refund = refunds.findById(refundId).orElse(null);
        if (refund == null) {
            return CreditPayRefundStatus.PENDING;
        }
        if (refund.getStatus() == CreditPayRefundStatus.COMPLETED) {
            return CreditPayRefundStatus.COMPLETED;
        }

        try (var trace = TraceScope.of("credit_payment_attempt", refund.getAttemptId(), "credit_payment_refund",
                refundId, "rzp_payment", refund.getRazorpayPaymentId())) {
            String receipt = RECEIPT_PREFIX + refundId;
            PaymentProvider.ProviderRefund sent;
            try {
                sent = refund.getProviderRefundId() != null
                        ? provider.fetchRefund(refund.getProviderRefundId())
                        : provider.refund(refund.getRazorpayPaymentId(), refund.getAmount(), receipt,
                                new PaymentProvider.RefundOptions(receipt, Map.of(
                                        "mandi_credit_payment_attempt_id", String.valueOf(refund.getAttemptId()),
                                        "mandi_credit_payment_refund_id", String.valueOf(refundId)), null));
            } catch (PaymentProviderException ex) {
                notDone(refund, null, "Razorpay: " + ex.getMessage());
                return CreditPayRefundStatus.PENDING;
            }

            switch (sent.status()) {
                case COMPLETED -> {
                    boolean done = Boolean.TRUE.equals(tx.execute(status -> {
                        refunds.noteAttempt(refundId, sent.providerRefundId(), 0, Instant.now(), null);
                        if (refunds.markCompleted(refundId, Instant.now()) != 1) {
                            return false;
                        }
                        audit.record(null, null, "CREDIT_PAY_REFUNDED", "CREDIT_PAYMENT_ATTEMPT",
                                refund.getAttemptId(), CreditPayRefundStatus.PENDING.name(),
                                CreditPayRefundStatus.COMPLETED.name(),
                                refund.getAmount().toPlainString() + " to source via "
                                        + refund.getRazorpayPaymentId(), "SYSTEM");
                        return true;
                    }));
                    if (done) {
                        log.info("Credit payment refund {} PENDING → COMPLETED: {} returned as refund {}",
                                refundId, refund.getAmount().toPlainString(), sent.providerRefundId());
                    }
                    return CreditPayRefundStatus.COMPLETED;
                }
                case PENDING -> {
                    // Accepted, not finished: remembered and asked about next time, never sent again, not counted.
                    tx.executeWithoutResult(status ->
                            refunds.noteAttempt(refundId, sent.providerRefundId(), 0, Instant.now(), null));
                    log.info("Credit payment refund {} accepted by Razorpay as {}; waiting for it to finish",
                            refundId, sent.providerRefundId());
                    return CreditPayRefundStatus.PENDING;
                }
                default -> {
                    notDone(refund, sent.providerRefundId(), "Razorpay refused the refund: " + sent.failureCode());
                    return CreditPayRefundStatus.PENDING;
                }
            }
        }
    }

    private void notDone(CreditPaymentRefund refund, String providerRefundId, String why) {
        tx.executeWithoutResult(status ->
                refunds.noteAttempt(refund.getId(), providerRefundId, 1, Instant.now(), truncate(why)));
        int done = refund.getAttempts() + 1;
        if (done >= MAX_ATTEMPTS) {
            // Error, for an alert to match: money is captured, not applied and not going back.
            log.error("Credit payment refund {} of {} failed {} times ({}); NEEDS A PERSON — captured, not applied, "
                            + "not returned",
                    refund.getId(), refund.getAmount().toPlainString(), done, why);
        } else {
            log.warn("Credit payment refund {} attempt {} failed: {}", refund.getId(), done, why);
        }
    }

    /** The refunds due to be sent or confirmed, oldest first. */
    public List<CreditPaymentRefund> due(int batch) {
        return refunds.findDue(MAX_ATTEMPTS, Instant.now().minus(RECHECK), PageRequest.of(0, batch));
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }
}
