package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.payment.domain.*;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.provider.ProviderFailureKind;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
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
    private final SupplierOrderRepository orders;
    private final CancelRefundSpeed cancelRefundSpeed;

    /**
     * How long a refund may sit in PROCESSING before we assume the process that
     * claimed it died mid-call. Retrying is safe: the provider key is stable per
     * refund, so a second send returns the first refund rather than a new one.
     */
    static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    /** Sends before a refund that keeps failing goes to a person instead (D-101). */
    static final int MAX_ATTEMPTS = 5;

    /** How long a refund the provider reported failed is left before it is asked about again and the failure believed (D-110). */
    static final Duration FAILED_RECHECK_AFTER = Duration.ofHours(1);

    /** How often "Razorpay refused our credentials" is repeated while it lasts (N5). */
    private static final Duration CREDENTIALS_ALERT_EVERY = Duration.ofMinutes(15);

    /** Shared with the jobs, so one outage is one line. Not injected state: it is only a memory of what was logged. */
    private final AlertThrottle alerts;

    /** What the refund job should do next after one refund. */
    public enum Outcome {
        /** Carry on with the next refund. */
        DONE,
        /** Razorpay is limiting our calls or refuses our keys: the rest of this run would meet the same answer. */
        BACK_OFF,
        /**
         * The provider says it does not know this payment. One is a payment that is gone; two different ones in a
         * row are a configuration fault (keys or mode of another account), and the run stops (D-110).
         */
        UNKNOWN_PAYMENT
    }

    /** Refunds whose money may still go back, and so is not refundable again. */
    private static final java.util.Set<RefundStatus> IN_FLIGHT = java.util.EnumSet.of(
            RefundStatus.REQUESTED, RefundStatus.PROCESSING,
            RefundStatus.FAILED, RefundStatus.NEEDS_REVIEW, RefundStatus.REJECTED);

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
        return toWallet(actorId, paymentId, amount, reason, note, idempotencyKey, false);
    }

    /**
     * As {@link #refundToWallet}, except that a payment still being captured is not an error (D-129).
     *
     * <p>The money exists but has not been taken, so there is nothing to give back yet. Returns {@code null}
     * and writes nothing; the caller records the reduction as waiting for the capture and tries again once the
     * payment is captured. A payment in any other state that cannot be refunded still throws, as before.
     *
     * @return the refund, or null while the payment's capture is pending
     */
    @Transactional
    public Refund refundToWalletOnceCaptured(Long actorId, Long paymentId, BigDecimal amount,
                                             RefundReason reason, String note, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Enter an amount greater than zero.");
        }
        return toWallet(actorId, paymentId, amount, reason, note, idempotencyKey, true);
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
                "cancel-order-" + payment.getSupplierOrderId(), false);
    }

    /** @param amount what to refund, or null for everything still refundable */
    private Refund toWallet(Long actorId, Long paymentId, BigDecimal amount,
                            RefundReason reason, String note, String idempotencyKey,
                            boolean deferWhileCapturePending) {
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
            // Decided after both locks, on the state they protect: a capture that finishes between a caller's
            // read and this point is then seen as captured, not deferred.
            if (deferWhileCapturePending && payment.getStatus() == PaymentStatus.CAPTURE_PENDING) {
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
                completedPayload(payment, refund), actorId);
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
    public record Withdrawable(Long paymentId, BigDecimal available, boolean blocked) {
    }

    /**
     * The outlet's payments with wallet money a card can take back, oldest credit
     * first, each marked if the provider will no longer take a refund against it (D-110).
     * Only money that came from a card can go back to one: a top-up or a
     * wallet-paid order's refund has no card behind it.
     *
     * <p>Call with the outlet's wallet locked; the figures are only true while no
     * other withdrawal can run.
     */
    @Transactional(readOnly = true)
    public java.util.List<Withdrawable> withdrawable(Long outletId) {
        return refunds.withdrawableByPayment(outletId).stream()
                .map(row -> new Withdrawable(((Number) row[0]).longValue(), (BigDecimal) row[1],
                        ((Number) row[3]).intValue() != 0))
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
                // A blocked source has no refund left to give: its money is spendable only (D-110).
                .filter(source -> source.paymentId().equals(paymentId) && !source.blocked())
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
    public Outcome process(Long refundId) {
        record Claim(Refund refund, boolean earlierSend, Instant legacySince) {
        }
        Claim claim = txTemplate.execute(status -> {
            var refund = refunds.findById(refundId).orElse(null);
            if (refund == null || !claimable(refund)) {
                return null;
            }
            // Any earlier claim may have reached the provider, whether or not we heard back: the
            // process may have died mid-call, or the answer been lost (D-110). Before such a
            // refund is sent again the provider's own list is read for one of ours.
            boolean earlierSend = refund.getSentAt() != null || refund.getAttempts() > 0;
            // A refund sent before receipts existed carries none; found by amount and time instead.
            Instant legacySince = refund.getSentAt() == null && refund.getAttempts() > 0
                    ? refund.getCreatedAt() : null;
            log.info("Refund {} {} → PROCESSING for {} (attempt {})", refund.getId(), refund.getStatus(),
                    refund.getAmount().toPlainString(), refund.getAttempts() + 1);
            refund.setStatus(RefundStatus.PROCESSING);
            // Every claim changes the row, even a re-claim of one already
            // PROCESSING: the version moves, so a second job run claiming the same
            // refund fails its save instead of also sending it.
            refund.setAttempts(refund.getAttempts() + 1);
            if (refund.getSentAt() == null) {
                refund.setSentAt(Instant.now());
            }
            // Every claim, not only the first: how long ago the provider last had a chance to make this refund
            // is what decides when a person may put it back (an approval or a note must not move it).
            refund.setLastSentAt(Instant.now());
            return new Claim(refunds.saveAndFlush(refund), earlierSend, legacySince);
        });
        if (claim == null) {
            return Outcome.DONE;
        }
        var claimed = claim.refund();

        var payment = paymentService.load(claimed.getPaymentId());

        if (claim.earlierSend()) {
            // Not resent on a hunch: the provider may already hold this refund (a lost answer, a
            // crash after the call), and its idempotency key is only remembered for so long
            // (unverified). Found: adopted, never sent twice. Not found: sent. Cannot be read:
            // not sent, and tried again next run (D-110).
            java.util.List<PaymentProvider.ProviderRefundEntry> listed = java.util.List.of();
            PaymentProviderException unreadable = null;
            try {
                listed = provider.listRefunds(payment.getProviderPaymentId());
            } catch (PaymentProviderException ex) {
                // A 404 too: "the provider does not know this payment" is what an account whose keys are not
                // the ones that made the earlier send would say, and the earlier send may well be at the real
                // one. It proves nothing about that send, so it is not a reason to send again (D-110).
                unreadable = ex;
            }
            if (unreadable != null) {
                // Being unable to read the list says nothing about the refund, whatever the
                // refusal: a rate limit or refused keys wait as they always do, anything else is
                // as uncertain as a lost answer. Never a definite "no" (D-110).
                var lookupFailure = unreadable.isRateLimited() || unreadable.isCredentialsRefused()
                        ? unreadable
                        : new PaymentProviderException("Could not read the provider's refunds: "
                                + unreadable.getMessage(), true, unreadable.providerCode(),
                                ProviderFailureKind.AMBIGUOUS, unreadable.description());
                var outcome = recordFailure(refundId, lookupFailure, "checking for");
                return outcome == Outcome.DONE && unreadable.isNotFound() ? Outcome.UNKNOWN_PAYMENT : outcome;
            }
            var ours = RefundMatching.ours(listed, claimed, claim.legacySince());
            if (ours.isPresent()) {
                adoptOurs(refundId, claimed.getReason() == RefundReason.WALLET_WITHDRAWAL ? payment.getOutletId() : null,
                        ours.get());
                return Outcome.DONE;
            }
        }

        PaymentProvider.ProviderRefund result = null;
        PaymentProviderException failure = null;
        try {
            result = provider.refund(payment.getProviderPaymentId(), claimed.getAmount(),
                    // Stable per refund, so a retry reaches the same operation at
                    // the provider rather than issuing a second one. Prefixed to
                    // clear Razorpay's ten-character minimum: "refund-7" is
                    // rejected, and a rejected key is no key at all.
                    "mandi-refund-" + claimed.getId(),
                    optionsFor(claimed));
        } catch (PaymentProviderException ex) {
            failure = ex;
        }

        if (failure != null) {
            var outcome = recordFailure(refundId, failure, "sending");
            return outcome == Outcome.DONE && failure.kind() == ProviderFailureKind.PAYMENT_UNKNOWN
                    ? Outcome.UNKNOWN_PAYMENT : outcome;
        }
        final var outcome = result;
        txTemplate.executeWithoutResult(status -> {
            var refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return;
            }
            if (outcome.status() == PaymentProvider.ProviderRefundStatus.FAILED) {
                if (outcome.providerRefundId() != null) {
                    // Razorpay made the refund and then said it failed. Whether that is final is not
                    // known (V-7), so it is asked again after an hour before it is believed (D-110).
                    refund.setProviderRefundId(outcome.providerRefundId());
                    refund.setFailureKind(ProviderFailureKind.PROVIDER_FAILED);
                    refund.setFailureCode(outcome.failureCode());
                    refund.setFailureReason(outcome.failureReason());
                    refund.setFailedAt(Instant.now());
                    refunds.save(refund);
                    log.warn("Refund {} was made by the provider as {} and reported failed; asking again in {}",
                            refund.getId(), outcome.providerRefundId(), FAILED_RECHECK_AFTER);
                } else {
                    // The provider looked at it and said no, without making a refund (D-101): a definite
                    // refusal, checked before anything is decided about the money (D-110).
                    reject(refund, ProviderFailureKind.PROVIDER_FAILED, outcome.failureCode(), outcome.failureReason());
                }
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
        return Outcome.DONE;
    }

    /**
     * What a failed call, or a failed look before a resend, means for the refund (D-110). Written
     * in one short transaction on a fresh read.
     *
     * <ul>
     *   <li>Throttled or credentials refused: nothing about the refund; retried every run and not counted.</li>
     *   <li>Ambiguous (timeout, 5xx, unreadable): retried with the same key, and looked up before each
     *       resend; after {@link #MAX_ATTEMPTS} it goes to a person. Never put back automatically.</li>
     *   <li>A definite refusal: REJECTED, and the provider's refunds are read before anything more.</li>
     * </ul>
     */
    private Outcome recordFailure(Long refundId, PaymentProviderException error, String while_) {
        final boolean[] backOff = {false};
        txTemplate.executeWithoutResult(status -> {
            var refund = refunds.findById(refundId).orElseThrow();
            if (refund.getStatus() != RefundStatus.PROCESSING) {
                return;
            }
            var kind = error.kind();
            refund.setFailureKind(kind);
            if (kind == ProviderFailureKind.THROTTLED || kind == ProviderFailureKind.CONFIG) {
                // Razorpay is limiting our calls, or is refusing our keys: nothing is wrong
                // with the refund and it has not been refused. Retried next run with the
                // same key, however many times it takes; sending it to a person would strand
                // money that is owed back over a busy minute at the provider or a key that
                // is being rotated (F2). Nothing moves while it waits, so waiting is safe.
                // And the send does not count toward MAX_ATTEMPTS: it says nothing about the
                // refund, and a count that grows through a rate limit would send the first
                // real 5xx after it to NEEDS_REVIEW, which has no exit but the database (N2).
                refund.setAttempts(Math.max(0, refund.getAttempts() - 1));
                backOff[0] = true;
                // Once per interval, not per refund per run: the same outage, 120 times an hour.
                if (kind == ProviderFailureKind.CONFIG
                        && alerts.due("credentials-refund", Instant.now(), CREDENTIALS_ALERT_EVERY)) {
                    log.error("Razorpay refused our credentials {} refund {}; it will be sent "
                            + "again when the keys are fixed", while_, refund.getId());
                }
                markFailed(refund, error.providerCode(), error.description() != null ? error.description() : error.getMessage());
            } else if (kind == ProviderFailureKind.AMBIGUOUS) {
                if (error.isRetryable() && refund.getAttempts() < MAX_ATTEMPTS) {
                    // FAILED rather than abandoned, because doc 03 §7 allows a retry
                    // from here — and the same refund row is retried with the same
                    // key, so a retry cannot become a second refund.
                    markFailed(refund, error.providerCode(), error.getMessage());
                } else {
                    needsReview(refund, error.providerCode(), error.getMessage());
                }
            } else {
                reject(refund, kind, error.providerCode(),
                        error.description() != null ? error.description() : error.getMessage());
            }
        });
        return backOff[0] ? Outcome.BACK_OFF : Outcome.DONE;
    }

    /**
     * The provider holds a refund of ours that we did not know had been made (D-110): a lost
     * answer, or a process that died after the call. It is what the money is, so it is adopted
     * and never sent again. A refund it already finished is completed; one still pending is
     * followed; one it reports failed goes through the same wait as any failed refund.
     */
    void adoptOurs(Long refundId, Long walletOutletId, PaymentProvider.ProviderRefundEntry ours) {
        txTemplate.executeWithoutResult(status -> {
            // Wallet first for a withdrawal part (walletOutletId, null for any other refund), as everything that
            // decides about one does, before anything else is read: two adoptions of the same provider refund by
            // two of our refunds then run one after the other and the second finds it taken (the unique index on
            // provider_refund_id is the backstop).
            if (walletOutletId != null) {
                wallet.lock(walletOutletId);
            }
            var refund = refunds.lockById(refundId).orElseThrow();
            if (refund.getStatus() == RefundStatus.COMPLETED || refund.getStatus() == RefundStatus.REVERSED) {
                return;
            }
            applyAdoption(refund, ours);
        });
    }

    /**
     * {@link #adoptOurs}, inside the caller's transaction and on a refund it already holds.
     *
     * <p>One provider refund is the money of one refund of ours. When another of our refunds already holds
     * this provider refund id, adopting it would complete two of ours with one payout (two legacy refunds of
     * the same amount, sent before receipts existed, both "matching" one provider refund): the refund goes
     * to a person instead, and nothing is completed.
     *
     * @return true if adopted; false if it was sent to review because the provider refund is already another's
     */
    boolean applyAdoption(Refund refund, PaymentProvider.ProviderRefundEntry ours) {
        if (ours.providerRefundId() != null
                && refunds.existsByProviderRefundIdAndIdNot(ours.providerRefundId(), refund.getId())) {
            log.error("Refund {} matches provider refund {}, which already completes another refund of ours; "
                    + "not adopted, left for a person", refund.getId(), ours.providerRefundId());
            if (refund.getStatus() != RefundStatus.NEEDS_REVIEW) {
                moveTo(refund, RefundStatus.NEEDS_REVIEW);
            }
            refund.setFailureCode("PROVIDER_REFUND_TAKEN");
            refund.setFailureReason("Provider refund " + ours.providerRefundId()
                    + " already completes another refund of ours");
            refunds.save(refund);
            return false;
        }
        log.warn("Refund {} was already made at the provider as {} ({}); adopted, not sent again",
                refund.getId(), ours.providerRefundId(), ours.status());
        refund.setProviderRefundId(ours.providerRefundId());
        refund.setVerifiedAt(Instant.now());
        refund.setVerifiedResult("OURS");
        if (refund.getStatus() != RefundStatus.PROCESSING) {
            moveTo(refund, RefundStatus.PROCESSING);
        }
        switch (ours.status()) {
            case COMPLETED -> complete(refund);
            case PENDING -> {
                refund.setFailureKind(null);
                refunds.save(refund);
            }
            case FAILED -> {
                refund.setFailureKind(ProviderFailureKind.PROVIDER_FAILED);
                if (refund.getFailedAt() == null) {
                    refund.setFailedAt(Instant.now());
                }
                refunds.save(refund);
            }
        }
        return true;
    }

    /**
     * What a refund carries beyond its amount (D-109): a receipt and notes, so it
     * can be found at the provider by our own reference and read on their dashboard
     * without our database, and the speed. The receipt is the idempotency key's
     * twin: {@code mandi-refund-{id}}, well inside Razorpay's forty characters.
     */
    private PaymentProvider.RefundOptions optionsFor(Refund refund) {
        boolean cancellation = refund.getReason() == RefundReason.CANCELLATION
                && refund.getDestination() == RefundDestination.ORIGINAL;
        return new PaymentProvider.RefundOptions(
                "mandi-refund-" + refund.getId(),
                java.util.Map.of(
                        "mandi_refund_id", String.valueOf(refund.getId()),
                        "mandi_payment_id", String.valueOf(refund.getPaymentId()),
                        "purpose", refund.getReason().name().toLowerCase(java.util.Locale.ROOT)),
                cancellation ? cancelRefundSpeed.speed() : "normal");
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
                case FAILED -> {
                    String reason = answer.failureReason() == null
                            ? "The provider failed the refund" : answer.failureReason();
                    if (refund.getFailedAt() == null) {
                        // First time it is seen failed. Whether that is final is not known (V-7),
                        // so it is left, and asked again after an hour (D-110).
                        refund.setFailureKind(ProviderFailureKind.PROVIDER_FAILED);
                        refund.setFailureCode(answer.failureCode());
                        refund.setFailureReason(reason);
                        refund.setFailedAt(Instant.now());
                        refunds.save(refund);
                        log.warn("Refund {} reported failed by the provider; asking again in {}",
                                refund.getId(), FAILED_RECHECK_AFTER);
                    } else if (refund.getFailedAt().isBefore(Instant.now().minus(FAILED_RECHECK_AFTER))) {
                        // Still failed an hour later: believed. Not yet decided about the money: the
                        // provider's refunds are read first, by the reversal (D-110).
                        reject(refund, ProviderFailureKind.PROVIDER_FAILED, answer.failureCode(), reason);
                    }
                }
                // Still pending: touch the row so the next check waits its turn.
                case PENDING -> {
                    refund.setFailureReason(null);
                    refund.setFailureKind(null);
                    refund.setFailedAt(null);
                    refunds.save(refund);
                }
            }
        });
    }

    /** The money went back. Written inside the caller's transaction. */
    void complete(Refund refund) {
        log.info("Refund {} PROCESSING → COMPLETED (provider refund {})",
                refund.getId(), refund.getProviderRefundId());
        refund.setStatus(RefundStatus.COMPLETED);
        refund.setCompletedAt(Instant.now());
        // Whatever it failed at earlier no longer describes it, and a stale kind would keep the
        // withdrawal circuit breaker shut (D-110).
        refund.setFailureKind(null);
        refund.setFailedAt(null);
        refunds.save(refund);

        // A withdrawal was counted as refunded when it reached the wallet; this
        // only moved it on to the card. Counting it again would refund it twice.
        if (refund.getReason() != RefundReason.WALLET_WITHDRAWAL) {
            // Locked: a webhook writing the payment meanwhile would otherwise roll
            // this back on the version check, after the money had already moved.
            applyToPayment(payments.lockById(refund.getPaymentId()).orElseThrow(), refund);
        }

        outbox.publish("RefundCompleted", "REFUND", refund.getId(),
                completedPayload(payments.findById(refund.getPaymentId()).orElseThrow(), refund),
                refund.getRequestedBy());
    }

    /**
     * What the outbox says about a completed refund, and enough for the restaurant
     * to be told: which outlet, which order, where the money went, and which wording
     * fits. Without the outlet the notification had nobody to go to (D-109).
     */
    private java.util.Map<String, Object> completedPayload(Payment payment, Refund refund) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put("paymentId", refund.getPaymentId());
        payload.put("supplierOrderId", refund.getSupplierOrderId());
        payload.put("outletId", payment.getOutletId());
        payload.put("amount", refund.getAmount().toPlainString());
        payload.put("destination", refund.getDestination().name());
        payload.put("reason", refund.getReason().name());
        orders.findById(refund.getSupplierOrderId())
                .ifPresent(order -> payload.put("orderNumber", order.getOrderNumber()));
        // Which notification text fits (F5). A dispute refund to the wallet is already told
        // to the restaurant by the dispute's own "refund approved", and each part of a
        // wallet withdrawal would push a "refunded" linked to whichever old order it was
        // drawn from, so neither sends one of its own: their variants have no rule.
        if (refund.getReason() == RefundReason.WALLET_WITHDRAWAL) {
            payload.put("notificationVariant", "WITHDRAWAL");
        } else if (refund.getReason() == RefundReason.DISPUTE_RESOLVED) {
            payload.put("notificationVariant", "DISPUTE");
        } else if (refund.getDestination() == RefundDestination.WALLET) {
            payload.put("notificationVariant", "WALLET");
        } else if (refund.getReason() == RefundReason.CANCELLATION) {
            payload.put("notificationVariant", "CANCELLATION_TO_SOURCE");
        }
        return payload;
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

    /**
     * The provider refused, definitely (D-110). Not yet a decision about the money: the provider's
     * refunds are read next ({@code WithdrawalReversalService}), because a refusal can follow an
     * earlier send that did go through.
     */
    private void reject(Refund refund, ProviderFailureKind kind, String code, String reason) {
        refund.setStatus(RefundStatus.REJECTED);
        // A new refusal is read afresh: what an earlier read left undecided or sent to a person no longer stands.
        refund.setSettleHeldAt(null);
        refund.setReviewCause(null);
        refund.setReviewRef(null);
        refund.setFailureKind(kind);
        refund.setFailureCode(code);
        refund.setFailureReason(reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500));
        refunds.save(refund);
        log.warn("Refund {} → REJECTED by the provider ({}): {} {}", refund.getId(), kind, code, reason);
    }

    /** A status change through the state machine, refusing what it does not allow. */
    void moveTo(Refund refund, RefundStatus target) {
        if (!refund.getStatus().canTransitionTo(target)) {
            throw new IllegalStateException("Refund " + refund.getId() + " cannot go from "
                    + refund.getStatus() + " to " + target);
        }
        refund.setStatus(target);
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
