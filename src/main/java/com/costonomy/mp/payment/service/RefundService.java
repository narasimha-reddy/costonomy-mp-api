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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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
    private final TransactionTemplate txTemplate;

    /**
     * How long a refund may sit in PROCESSING before we assume the process that
     * claimed it died mid-call. Retrying is safe: the provider key is stable per
     * refund, so a second send returns the first refund rather than a new one.
     */
    static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    /** Sends before a refund that keeps failing goes to a person instead (D-101). */
    static final int MAX_ATTEMPTS = 5;

    /** Refunds whose money may still go back, and so is not refundable again. */
    private static final java.util.Set<RefundStatus> IN_FLIGHT = java.util.EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.PROCESSING,
            RefundStatus.FAILED, RefundStatus.NEEDS_REVIEW);

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
            if (!existing.get().getPaymentId().equals(paymentId)) {
                // The key is unique across refunds, so without this a key used on
                // one payment returned that payment's refund to a request about
                // another (D-101).
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
            }
            // Doc 22: a repeated request returns the original rather than creating
            // another. The client's question is "did my refund happen", and it did.
            return existing.get();
        }

        // Locked, so two requests for the same payment are decided one after the
        // other against the same figure, not both against a stale one (D-101).
        var payment = payments.lockById(paymentId)
                .orElseThrow(() -> new com.costonomy.mp.common.error.NotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Only a captured payment can be refunded.");
        }

        // What is still promised back counts too. Only completed refunds reached
        // refunded_amount, so two requests with different keys could each ask for
        // the whole capture, and the second then failed at the provider for ever.
        BigDecimal inFlight = refunds.sumByPaymentIdAndStatusIn(paymentId, IN_FLIGHT);
        BigDecimal refundable = payment.refundableAmount().subtract(inFlight);
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
        log.info("Refund {} REQUESTED for {} ({}) against payment {}",
                refund.getId(), amount.toPlainString(), reason, paymentId);

        return refund;
    }

    /**
     * Refund what is left of a payment whose order was cancelled after the money
     * was taken (D-103). System-initiated, keyed on the order, so a second
     * cancellation event cannot refund twice.
     *
     * @return the refund, or null if nothing is left to refund
     */
    @Transactional
    public Refund refundCancelled(Payment payment, String note) {
        String key = "cancel-order-" + payment.getSupplierOrderId();
        var existing = refunds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        var locked = payments.lockById(payment.getId()).orElseThrow();
        BigDecimal remaining = locked.refundableAmount()
                .subtract(refunds.sumByPaymentIdAndStatusIn(locked.getId(), IN_FLIGHT));
        if (remaining.signum() <= 0) {
            return null;
        }
        var refund = new Refund();
        refund.setPaymentId(locked.getId());
        refund.setSupplierOrderId(locked.getSupplierOrderId());
        refund.setAmount(remaining);
        refund.setReason(RefundReason.CANCELLATION);
        refund.setNote(note);
        refund.setStatus(RefundStatus.REQUESTED);
        refund.setIdempotencyKey(key);
        refunds.saveAndFlush(refund);

        auditService.record(null, null, "REFUND_REQUESTED", "REFUND", refund.getId(),
                null, RefundStatus.REQUESTED.name(), "Order cancelled after capture: " + note, "SYSTEM");
        log.info("Refund {} REQUESTED for {} (order cancelled after capture) against payment {}",
                refund.getId(), remaining.toPlainString(), locked.getId());
        return refund;
    }

    /**
     * Send a requested refund to the provider. Called by the refund job.
     *
     * <p>Separate from {@link #request} so the record commits first: a provider
     * call inside the request transaction would mean a timeout leaves no trace of
     * a refund that may well have happened.
     *
     * <p><b>Three steps, and the middle one holds no database connection</b>
     * (D-099): claim the refund as PROCESSING and commit; call the provider; write
     * the outcome in a short transaction on a fresh read. A process that dies
     * between the first and last step leaves the refund in PROCESSING, which the
     * job picks up again after {@link #STUCK_AFTER} — before this, the claim and
     * the call shared one transaction, and moving the call out without this would
     * have left such a refund stuck for good.
     */
    public void process(Long refundId) {
        Refund claimed = txTemplate.execute(status -> {
            var refund = refunds.findById(refundId).orElse(null);
            if (refund == null || !claimable(refund)) {
                return null;
            }
            log.info("Refund {} {} → PROCESSING for {} (attempt {})", refund.getId(), refund.getStatus(),
                    refund.getAmount().toPlainString(), refund.getAttempts() + 1);
            refund.setStatus(RefundStatus.PROCESSING);
            // Every claim changes the row, even a re-claim of one already
            // PROCESSING: the version moves, so a second job run claiming the same
            // refund fails its save instead of also sending it.
            refund.setAttempts(refund.getAttempts() + 1);
            return refunds.saveAndFlush(refund);
        });
        if (claimed == null) {
            return;
        }

        var payment = paymentService.load(claimed.getPaymentId());

        PaymentProvider.ProviderRefund result = null;
        PaymentProviderException failure = null;
        try {
            result = provider.refund(payment.getProviderPaymentId(), claimed.getAmount(),
                    // Stable per refund, so a retry reaches the same operation at
                    // the provider rather than issuing a second one. Prefixed to
                    // clear Razorpay's ten-character minimum: "refund-7" is
                    // rejected, and a rejected key is no key at all.
                    "mandi-refund-" + claimed.getId());
        } catch (PaymentProviderException ex) {
            failure = ex;
        }

        final var outcome = result;
        final var error = failure;
        txTemplate.executeWithoutResult(status -> {
            var refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return;
            }
            if (error != null) {
                if (error.isRetryable() && refund.getAttempts() < MAX_ATTEMPTS) {
                    // FAILED rather than abandoned, because doc 03 §7 allows a retry
                    // from here — and the same refund row is retried with the same
                    // key, so a retry cannot become a second refund.
                    markFailed(refund, error.providerCode(), error.getMessage());
                } else {
                    needsReview(refund, error.providerCode(), error.getMessage());
                }
                return;
            }
            if (outcome.status() == PaymentProvider.ProviderRefundStatus.FAILED) {
                // The provider looked at it and said no. Sending it again says the
                // same thing; a person has to decide (D-101).
                needsReview(refund, outcome.failureCode(), outcome.failureReason());
                return;
            }
            refund.setProviderRefundId(outcome.providerRefundId());
            if (outcome.status() == PaymentProvider.ProviderRefundStatus.PENDING) {
                // Accepted, not finished. It used to be marked COMPLETED here, so a
                // refund the provider later failed was never retried while our
                // books said the money had gone back (D-101).
                refunds.save(refund);
                log.info("Refund {} accepted by the provider as {}; waiting for it to finish",
                        refund.getId(), outcome.providerRefundId());
                return;
            }
            complete(refund);
        });
    }

    /**
     * Ask the provider about a refund it accepted but had not finished. Called by
     * the refund job for PROCESSING refunds that already have a provider id.
     */
    public void settlePending(Long refundId) {
        var pending = refunds.findById(refundId).orElse(null);
        if (pending == null || pending.getStatus() != RefundStatus.PROCESSING
                || pending.getProviderRefundId() == null) {
            return;
        }
        PaymentProvider.ProviderRefund answer;
        try {
            answer = provider.fetchRefund(pending.getProviderRefundId());
        } catch (PaymentProviderException ex) {
            log.debug("Could not ask about refund {}: {}", refundId, ex.getMessage());
            return;
        }
        txTemplate.executeWithoutResult(status -> {
            var refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return;
            }
            switch (answer.status()) {
                case COMPLETED -> complete(refund);
                case FAILED -> needsReview(refund, answer.failureCode(),
                        answer.failureReason() == null ? "The provider failed the refund" : answer.failureReason());
                // Still pending: touch the row so the next check waits its turn.
                case PENDING -> {
                    refund.setFailureReason(null);
                    refunds.save(refund);
                }
            }
        });
    }

    /** The money went back. Written inside the caller's transaction. */
    private void complete(Refund refund) {
        log.info("Refund {} PROCESSING → COMPLETED (provider refund {})",
                refund.getId(), refund.getProviderRefundId());
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedAt(Instant.now());
        refunds.save(refund);

        // Locked: a webhook writing the payment meanwhile would otherwise roll this
        // back on the version check, after the money had already moved.
        applyToPayment(payments.lockById(refund.getPaymentId()).orElseThrow(), refund);

        outbox.publish("RefundCompleted", "REFUND", refund.getId(),
                Map.of("paymentId", refund.getPaymentId(),
                        "supplierOrderId", refund.getSupplierOrderId(),
                        "amount", refund.getAmount().toPlainString()),
                refund.getRequestedBy());
    }

    /** Waiting to be sent, failed and worth retrying, or claimed by a process that died. */
    private static boolean claimable(Refund refund) {
        return switch (refund.getStatus()) {
            case REQUESTED, FAILED -> true;
            // Stuck only if the provider never answered. One that did (a pending
            // refund) is asked about by settlePending, never sent again.
            case PROCESSING -> refund.getProviderRefundId() == null
                    && refund.getUpdatedAt() != null
                    && refund.getUpdatedAt().isBefore(Instant.now().minus(STUCK_AFTER));
            default -> false;
        };
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

    private void needsReview(Refund refund, String code, String reason) {
        refund.setStatus(RefundStatus.NEEDS_REVIEW);
        refund.setFailureCode(code);
        refund.setFailureReason(reason);
        refunds.save(refund);
        // Error, not warn: money promised back is not going back, and a person has
        // to act. This line is what an alert should match.
        log.error("Refund {} → NEEDS_REVIEW after {} attempt(s): {} {}",
                refund.getId(), refund.getAttempts(), code, reason);
    }

    private void markFailed(Refund refund, String code, String reason) {
        refund.setStatus(RefundStatus.FAILED);
        refund.setFailureCode(code);
        refund.setFailureReason(reason);
        refunds.save(refund);
        log.warn("Refund {} → FAILED: {} {}", refund.getId(), code, reason);
    }
}
