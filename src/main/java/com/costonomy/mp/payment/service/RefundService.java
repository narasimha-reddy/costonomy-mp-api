package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.text.Rupees;
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
 *
 * <p><b>A refund goes to the wallet (D-104).</b> Nobody calls a provider refund
 * directly any more: money comes back to the outlet's wallet at once, and reaches
 * a card only when the restaurant withdraws it — a provider refund against the
 * payment it came from, sent by the refund job like every provider refund before.
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
    private final RefundWalletPort wallet;

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
     * Refund captured money to the outlet's wallet (D-104).
     *
     * <p>Completed here, in one transaction with the credit: no provider is
     * involved, so there is nothing to wait for and nothing that can half-happen.
     * The payment's refunded amount moves now, because the money is no longer the
     * supplier's — it is owed to the restaurant, as a balance. If they later
     * withdraw it, that is {@link #requestWithdrawal}, and it does not count again.
     *
     * <p>Idempotent on the key, which belongs to its payment: the same key for
     * another payment is refused rather than answered with this one's refund.
     */
    @Transactional
    public Refund refundToWallet(Long actorId, Long paymentId, BigDecimal amount,
                                 RefundReason reason, String note, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount greater than zero.");
        }
        return toWallet(actorId, paymentId, amount, reason, note, idempotencyKey);
    }

    /**
     * Refund what is left of a payment whose order was cancelled after the money
     * was taken (D-103), to the wallet (D-104). System-initiated and keyed on the
     * order, so a second cancellation event cannot refund twice.
     *
     * @return the refund, or null if nothing is left to refund
     */
    @Transactional
    public Refund refundCancelled(Payment payment, String note) {
        return toWallet(null, payment.getId(), null, RefundReason.CANCELLATION, note,
                "cancel-order-" + payment.getSupplierOrderId());
    }

    /** @param amount what to refund, or null for everything still refundable */
    private Refund toWallet(Long actorId, Long paymentId, BigDecimal amount,
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

        var unlocked = payments.findById(paymentId)
                .orElseThrow(() -> new com.costonomy.mp.common.error.NotFoundException("Payment", paymentId));
        // Wallet first, then the payment: the order a withdrawal takes them in
        // (RefundWalletPort). Both locked, so two refunds of one payment are
        // decided one after the other against the same figure (D-101).
        wallet.lock(unlocked.getOutletId());
        var payment = payments.lockById(paymentId).orElseThrow();

        if (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            if (amount == null) {
                return null;
            }
            // Money merely held is released, not refunded.
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Only a captured payment can be refunded.");
        }

        // What is still promised back by an older provider refund counts too.
        BigDecimal refundable = payment.refundableAmount()
                .subtract(refunds.sumByPaymentIdAndStatusIn(paymentId, IN_FLIGHT));
        BigDecimal value = amount == null ? refundable : amount;
        if (amount == null && value.signum() <= 0) {
            return null;
        }
        if (value.compareTo(refundable) > 0) {
            // Refunding more than was captured would give back money we never took.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The refund can't exceed ₹%s.".formatted(Rupees.of(refundable)));
        }

        var refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setSupplierOrderId(payment.getSupplierOrderId());
        refund.setAmount(value);
        refund.setReason(reason);
        refund.setDestination(RefundDestination.WALLET);
        refund.setNote(note);
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedAt(Instant.now());
        refund.setIdempotencyKey(idempotencyKey);
        refund.setRequestedBy(actorId);
        refunds.saveAndFlush(refund);

        applyToPayment(payment, refund);
        payments.flush();
        // Last: the credit is a bulk update that clears the persistence context,
        // and nothing loaded before it may be written after it.
        wallet.creditRefund(payment.getOutletId(), payment.getSupplierOrderId(), refund.getId(),
                value, reason == RefundReason.CANCELLATION ? "Refund: order cancelled" : "Refund");

        auditService.record(actorId, null, "REFUND_TO_WALLET", "REFUND", refund.getId(),
                null, RefundStatus.COMPLETED.name(),
                reason.name() + " " + value.toPlainString(), actorId == null ? "SYSTEM" : "API");
        outbox.publish("RefundCompleted", "REFUND", refund.getId(),
                Map.of("paymentId", paymentId,
                        "supplierOrderId", payment.getSupplierOrderId(),
                        "amount", value.toPlainString(),
                        "destination", RefundDestination.WALLET.name()),
                actorId);
        log.info("Refund {} of {} ({}) against payment {} credited to the wallet of outlet {}",
                refund.getId(), value.toPlainString(), reason, paymentId, payment.getOutletId());
        return refund;
    }

    /**
     * What can still be refunded from this payment: captured, less refunded, less
     * older provider refunds on their way. Zero unless the money was taken.
     */
    @Transactional(readOnly = true)
    public BigDecimal refundableFor(Long paymentId) {
        var payment = payments.findById(paymentId).orElse(null);
        if (payment == null || (payment.getStatus() != PaymentStatus.CAPTURED
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED)) {
            return BigDecimal.ZERO;
        }
        return payment.refundableAmount()
                .subtract(refunds.sumByPaymentIdAndStatusIn(paymentId, IN_FLIGHT))
                .max(BigDecimal.ZERO);
    }

    /** How much of one payment's wallet refunds can still go back to its card. */
    public record Withdrawable(Long paymentId, BigDecimal available) {
    }

    /**
     * The outlet's payments with wallet money a card can take back, oldest credit
     * first. Only money that came from a card can go back to one: a top-up or a
     * wallet-paid order's refund has no card behind it.
     *
     * <p>Call with the outlet's wallet locked; the figures are only true while no
     * other withdrawal can run.
     */
    @Transactional(readOnly = true)
    public java.util.List<Withdrawable> withdrawable(Long outletId) {
        return refunds.withdrawableByPayment(outletId).stream()
                .map(row -> new Withdrawable(((Number) row[0]).longValue(), (BigDecimal) row[1]))
                .toList();
    }

    /**
     * Send part of a wallet back to the card of the payment it came from (D-104).
     *
     * <p>A provider refund like any other — sent by the refund job, retried,
     * asked about while pending, handed to a person when it cannot finish — but it
     * does not add to the payment's refunded amount. That moved when the money was
     * credited to the wallet; this only changes where it ends up.
     *
     * <p>Called by the wallet's withdrawal with the wallet locked, which is what
     * keeps two withdrawals from both spending the same credit. The payment is
     * deliberately not locked: its withdrawable figure only grows while the wallet
     * is held, and taking it here would reverse the lock order.
     */
    @Transactional
    public Refund requestWithdrawal(Long actorId, Long paymentId, BigDecimal amount, String key) {
        if (refunds.findByIdempotencyKey(key).isPresent()) {
            // A key replayed after the idempotency record expired. The withdrawal
            // it named has already been made; making it again is the one thing
            // this must not do.
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE,
                    "This withdrawal has already been made.");
        }
        var payment = paymentService.load(paymentId);
        var available = withdrawable(payment.getOutletId()).stream()
                .filter(source -> source.paymentId().equals(paymentId))
                .map(Withdrawable::available)
                .findFirst().orElse(BigDecimal.ZERO);
        if (amount.signum() <= 0 || amount.compareTo(available) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Only ₹%s from this payment can go back to its card.".formatted(Rupees.of(available)));
        }

        var refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setSupplierOrderId(payment.getSupplierOrderId());
        refund.setAmount(amount);
        refund.setReason(RefundReason.WALLET_WITHDRAWAL);
        refund.setDestination(RefundDestination.ORIGINAL);
        refund.setStatus(RefundStatus.REQUESTED);
        refund.setIdempotencyKey(key);
        refund.setRequestedBy(actorId);
        refunds.saveAndFlush(refund);

        auditService.record(actorId, null, "REFUND_REQUESTED", "REFUND", refund.getId(),
                null, RefundStatus.REQUESTED.name(),
                "WALLET_WITHDRAWAL " + amount.toPlainString(), "API");
        log.info("Refund {} REQUESTED for {} (wallet withdrawal) against payment {}",
                refund.getId(), amount.toPlainString(), paymentId);
        return refund;
    }

    /** The current status of each of these refunds, for a wallet statement. */
    @Transactional(readOnly = true)
    public Map<Long, RefundStatus> statusesOf(java.util.Collection<Long> refundIds) {
        var statuses = new java.util.HashMap<Long, RefundStatus>();
        refunds.findAllById(refundIds).forEach(refund -> statuses.put(refund.getId(), refund.getStatus()));
        return statuses;
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

        // A withdrawal was counted as refunded when it reached the wallet; this
        // only moved it on to the card. Counting it again would refund it twice.
        if (refund.getReason() != RefundReason.WALLET_WITHDRAWAL) {
            // Locked: a webhook writing the payment meanwhile would otherwise roll
            // this back on the version check, after the money had already moved.
            applyToPayment(payments.lockById(refund.getPaymentId()).orElseThrow(), refund);
        }

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
