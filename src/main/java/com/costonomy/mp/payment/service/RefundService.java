package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Refunds. Doc 01 §15, doc 03 §7, doc 22.
 *
 * <p>A refund returns money that was <b>captured</b>. Returning money that was
 * only held is a release, and lives on {@link PaymentService} — keeping them apart
 * matters because they look different to a customer: a release drops a hold, a
 * refund appears on their statement as a reversal.
 *
 * <p>Doc 22 requires refunds to be idempotent, and that is not a nicety: a
 * duplicate refund is money leaving twice. Two guards — a unique index on the
 * client's key, and a refundable-balance check against what was actually captured.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PaymentProvider provider;
    private final AuditService auditService;
    private final OutboxService outbox;

    /**
     * Request a refund.
     *
     * <p>The record is created and committed before the provider is called, so a
     * retry finds it rather than starting a second refund. The provider call then
     * happens against a refund that already exists — which is what makes the
     * duplicate case safe rather than merely unlikely.
     */
    @Transactional
    public Refund request(Long actorId, Long paymentId, BigDecimal amount,
                          RefundReason reason, String note, String idempotencyKey) {

        var existing = refunds.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            // Doc 22: a repeated request returns the original rather than creating
            // another. The client's question is "did my refund happen", and it did.
            return existing.get();
        }

        var payment = paymentService.load(paymentId);

        if (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Only a captured payment can be refunded.");
        }

        BigDecimal refundable = payment.refundableAmount();
        if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
            // Refunding more than was captured would return money we never took.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The refund can't exceed ₹%s.".formatted(refundable.toPlainString()));
        }

        var refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setSupplierOrderId(payment.getSupplierOrderId());
        refund.setAmount(amount);
        refund.setReason(reason);
        refund.setNote(note);
        refund.setStatus(RefundStatus.REQUESTED);
        refund.setIdempotencyKey(idempotencyKey);
        refund.setRequestedBy(actorId);

        try {
            refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException ex) {
            // Two requests with the same key raced. The unique index decided it.
            return refunds.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
        }

        auditService.record(actorId, null, "REFUND_REQUESTED", "REFUND", refund.getId(),
                null, RefundStatus.REQUESTED.name(),
                reason.name() + " " + amount.toPlainString(), "API");

        return refund;
    }

    /**
     * Send a requested refund to the provider. Called by the refund job.
     *
     * <p>Separate from {@link #request} so the record commits first: a provider
     * call inside the request transaction would mean a timeout leaves no trace of
     * a refund that may well have happened.
     */
    @Transactional
    public void process(Long refundId) {
        var refund = refunds.findById(refundId).orElse(null);
        if (refund == null) {
            return;
        }
        if (refund.getStatus() != RefundStatus.REQUESTED
                && refund.getStatus() != RefundStatus.FAILED) {
            return;
        }

        var payment = paymentService.load(refund.getPaymentId());

        refund.setStatus(RefundStatus.PROCESSING);
        refunds.saveAndFlush(refund);

        try {
            var result = provider.refund(payment.getProviderPaymentId(), refund.getAmount(),
                    // Stable per refund, so a retry reaches the same operation at
                    // the provider rather than issuing a second one.
                    "refund-" + refund.getId());

            if (result.status() == PaymentProvider.ProviderRefundStatus.FAILED) {
                markFailed(refund, result.failureCode(), result.failureReason());
                return;
            }

            refund.setProviderRefundId(result.providerRefundId());
            refund.setStatus(RefundStatus.COMPLETED);
            refund.setCompletedAt(Instant.now());
            refunds.save(refund);

            applyToPayment(payment, refund);

            outbox.publish("RefundCompleted", "REFUND", refund.getId(),
                    Map.of("paymentId", payment.getId(),
                            "supplierOrderId", refund.getSupplierOrderId(),
                            "amount", refund.getAmount().toPlainString()),
                    refund.getRequestedBy());

        } catch (PaymentProviderException ex) {
            // FAILED rather than abandoned, because doc 03 §7 allows a retry from
            // here — and the same refund row is retried, so a retry cannot become a
            // second refund.
            markFailed(refund, ex.providerCode(), ex.getMessage());
        }
    }

    /** Move the money on the payment, and set the status the totals imply. */
    private void applyToPayment(Payment payment, Refund refund) {
        payment.setRefundedAmount(payment.getRefundedAmount().add(refund.getAmount()));

        var target = payment.getRefundedAmount().compareTo(payment.getCapturedAmount()) >= 0
                ? PaymentStatus.FULLY_REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;

        if (payment.getStatus().canTransitionTo(target)) {
            payment.setStatus(target);
        }
        payments.save(payment);

        auditService.record(refund.getRequestedBy(), null, "REFUND_COMPLETED", "PAYMENT",
                payment.getId(), null, target.name(),
                refund.getAmount().toPlainString(), "SYSTEM");
    }

    private void markFailed(Refund refund, String code, String reason) {
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailureCode(code);
        refund.setFailureReason(reason);
        refunds.save(refund);
        log.warn("Refund {} failed: {} {}", refund.getId(), code, reason);
    }
}
