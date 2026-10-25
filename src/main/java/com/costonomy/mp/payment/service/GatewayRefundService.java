package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.payment.domain.GatewayRefund;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.GatewayRefundRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Refunds of gateway payments that are not order payments (D-204): a food delivery fee, or a supplier's direct payment.
 *
 * <p><b>Recorded before it is asked.</b> {@link #record} writes the request in the caller's transaction, together with the
 * state change that makes the refund owed. Only then is the gateway called, with no transaction open ({@code send}), under
 * a lease, with the request's key as the gateway receipt. If the answer is lost, the gateway's own refund list is read for
 * that receipt ({@link #reconcile}); only a gateway that has no refund under the key lets another be sent.
 *
 * <p><b>Never more than was captured.</b> The sum of every request against one payment, locked while it is added up, may
 * not exceed what the gateway captured; and before each send the gateway is asked what it has already refunded of that
 * payment, so a refund made outside the system counts too. Partial refunds are ordinary: a settled fee returns only its
 * unused part.
 *
 * <p><b>A duplicate request is the same request.</b> The key is unique; asking again for it returns the first.
 */
@Service
@Slf4j
public class GatewayRefundService {

    public enum Result { NOT_CLAIMED, SUCCEEDED, PENDING, FAILED, EXHAUSTED, AMBIGUOUS, LEASE_LOST }

    public record Request(String sourceType, Long sourceId, String purpose, String providerPaymentId,
                          BigDecimal capturedAmount, BigDecimal amount, String reason, String idempotencyKey) {
    }

    private final GatewayRefundRepository refunds;
    private final PaymentProvider provider;
    private final GatewayRefundProperties props;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;

    public GatewayRefundService(GatewayRefundRepository refunds, PaymentProvider provider, GatewayRefundProperties props,
                                ApplicationEventPublisher events, PlatformTransactionManager txManager) {
        this.refunds = refunds;
        this.provider = provider;
        this.props = props;
        this.events = events;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ── record ───────────────────────────────────────────────────────────

    /**
     * Record that a refund is owed. In the caller's transaction, so it commits with whatever made it owed. Idempotent on
     * the key. Does not call the gateway.
     */
    @Transactional
    public GatewayRefund record(Request r) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "A refund must be more than zero.");
        }
        if (r.capturedAmount() == null || r.amount().compareTo(r.capturedAmount()) > 0) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "A refund cannot be more than was captured.");
        }
        var existing = refunds.findByIdempotencyKey(r.idempotencyKey());
        if (existing.isPresent()) {
            var e = existing.get();
            if (!e.getProviderPaymentId().equals(r.providerPaymentId()) || e.getAmount().compareTo(r.amount()) != 0) {
                throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                        "That refund key was already used for a different refund.");
            }
            return e;
        }
        // One conditional UPDATE decides whether it still fits, so two requests cannot both take the last of it.
        refunds.ensureReservation(r.providerPaymentId(), r.capturedAmount());
        if (refunds.reserve(r.providerPaymentId(), r.amount()) == 0) {
            BigDecimal reserved = refunds.reservedFor(r.providerPaymentId());
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Refunding %s more would go past the %s captured on this payment (%s is already refunded or on its way)."
                            .formatted(r.amount().toPlainString(), r.capturedAmount().toPlainString(),
                                    reserved == null ? "0" : reserved.toPlainString()));
        }
        var g = new GatewayRefund();
        g.setSourceType(r.sourceType());
        g.setSourceId(r.sourceId());
        g.setPurpose(r.purpose());
        g.setProviderPaymentId(r.providerPaymentId());
        g.setCapturedAmount(r.capturedAmount());
        g.setAmount(r.amount());
        g.setReason(r.reason() == null ? null : (r.reason().length() > 300 ? r.reason().substring(0, 300) : r.reason()));
        g.setIdempotencyKey(r.idempotencyKey());
        return refunds.saveAndFlush(g);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<GatewayRefund> find(String idempotencyKey) {
        return refunds.findByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public java.util.List<GatewayRefund> forSource(String sourceType, Long sourceId) {
        return refunds.findBySourceTypeAndSourceIdOrderByIdAsc(sourceType, sourceId);
    }

    /** Send once the caller's transaction commits (or now, if there is none). Never throws into the caller. */
    public void sendSoon(Long refundId) {
        Runnable go = () -> {
            try {
                send(refundId);
            } catch (RuntimeException ex) {
                log.error("Could not send gateway refund {} now; the sweep will: {}", refundId, ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    go.run();
                }
            });
        } else {
            go.run();
        }
    }

    // ── send ─────────────────────────────────────────────────────────────

    public Result send(Long refundId) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> refunds.claim(refundId, token, now.plus(props.getLease()), now));
        if (claimed == null || claimed == 0) {
            return Result.NOT_CLAIMED;
        }
        var g = refunds.findById(refundId).orElseThrow();

        // No transaction is open from here until the outcome is recorded.
        try {
            var facts = provider.inspect(g.getProviderPaymentId());
            BigDecimal already = facts.amountRefunded() == null ? BigDecimal.ZERO : facts.amountRefunded();
            if (already.add(g.getAmount()).compareTo(g.getCapturedAmount()) > 0) {
                return tx.execute(s -> finish(refundId, token, Result.EXHAUSTED, null,
                        "The gateway already shows %s refunded of %s captured; refunding %s more would exceed it"
                                .formatted(already.toPlainString(), g.getCapturedAmount().toPlainString(),
                                        g.getAmount().toPlainString())));
            }
        } catch (RuntimeException ex) {
            // Could not check. Nothing was sent, so it is safe to try again later.
            return tx.execute(s -> finish(refundId, token, Result.FAILED, null, "Could not read the payment: " + ex.getMessage()));
        }

        PaymentProvider.ProviderRefund out;
        try {
            out = provider.refund(g.getProviderPaymentId(), g.getAmount(), g.providerKey(),
                    new PaymentProvider.RefundOptions(g.receipt(),
                            Map.of("source", g.getSourceType(), "sourceId", String.valueOf(g.getSourceId()),
                                    "purpose", g.getPurpose()), null));
        } catch (PaymentProviderException ex) {
            // A refusal that is not about the network is definite; anything else we cannot tell.
            Result r = ex.isRetryable() ? Result.AMBIGUOUS : Result.EXHAUSTED;
            return tx.execute(s -> finish(refundId, token, r, null, String.valueOf(ex.getMessage())));
        } catch (RuntimeException ex) {
            return tx.execute(s -> finish(refundId, token, Result.AMBIGUOUS, null, String.valueOf(ex.getMessage())));
        }
        if (out.status() == PaymentProvider.ProviderRefundStatus.FAILED) {
            // The gateway recorded this send as a failed refund, so a retry must be a new refund (new key).
            return tx.execute(s -> finish(refundId, token, Result.FAILED, out.providerRefundId(),
                    "Gateway refused: " + out.failureCode() + " " + out.failureReason(), true));
        }
        if (out.status() == PaymentProvider.ProviderRefundStatus.PENDING) {
            // Accepted, not done. The money has not gone back yet, so nothing is reported as returned: it is
            // reconciled until the gateway says COMPLETED or FAILED (D-206).
            return tx.execute(s -> finish(refundId, token, Result.PENDING, out.providerRefundId(), null, false));
        }
        return tx.execute(s -> finish(refundId, token, Result.SUCCEEDED, out.providerRefundId(), null, false));
    }

    private Result finish(Long refundId, String token, Result result, String providerRefundId, String error) {
        return finish(refundId, token, result, providerRefundId, error, false);
    }

    /**
     * Record an outcome, only if the lease is still ours.
     *
     * @param providerRecordedFailure the gateway holds this send as a failed refund; the next send is a new round
     */
    private Result finish(Long refundId, String token, Result result, String providerRefundId, String error,
                          boolean providerRecordedFailure) {
        var g = refunds.lockById(refundId).orElseThrow();
        if (!token.equals(g.getClaimToken()) || !GatewayRefund.SENDING.equals(g.getStatus())) {
            log.warn("Gateway refund {}: outcome {} arrived after the lease was lost", refundId, result);
            return Result.LEASE_LOST;
        }
        g.setClaimToken(null);
        g.setClaimExpiresAt(null);
        switch (result) {
            case SUCCEEDED -> succeed(g, providerRefundId);
            case PENDING -> markPending(g, providerRefundId);
            case EXHAUSTED -> {
                g.setStatus(GatewayRefund.EXHAUSTED);
                g.setLastError(trim(error));
                g.setNextRetryAt(null);
            }
            case AMBIGUOUS -> {
                g.setStatus(GatewayRefund.AMBIGUOUS);
                g.setLastError(trim(error));
                g.setNextRetryAt(Instant.now());
            }
            default -> {
                g.setLastError(trim(error));
                if (providerRecordedFailure) {
                    g.setSendRound(g.getSendRound() + 1);
                    g.setProviderRefundId(null);
                }
                if (g.getAttemptCount() >= props.getMaxAttempts()) {
                    g.setStatus(GatewayRefund.EXHAUSTED);
                    g.setNextRetryAt(null);
                    result = Result.EXHAUSTED;
                } else {
                    g.setStatus(GatewayRefund.FAILED);
                    g.setNextRetryAt(Instant.now().plus(backoff(g.getAttemptCount())));
                }
            }
        }
        refunds.save(g);
        return result;
    }

    /** The gateway accepted it and has not completed it. Not a success: no outcome is announced until it is. */
    private void markPending(GatewayRefund g, String providerRefundId) {
        g.setStatus(GatewayRefund.PENDING);
        g.setProviderRefundId(providerRefundId);
        g.setLookupCount(0);
        g.setLastError(null);
        g.setNextRetryAt(Instant.now().plus(props.getRecheckAfter()));
    }

    private void succeed(GatewayRefund g, String providerRefundId) {
        g.setStatus(GatewayRefund.SUCCEEDED);
        g.setProviderRefundId(providerRefundId);
        g.setCompletedAt(Instant.now());
        g.setLastError(null);
        g.setNextRetryAt(null);
        events.publishEvent(new GatewayRefundOutcome(g.getId(), g.getSourceType(), g.getSourceId(), g.getPurpose(),
                g.getAmount(), providerRefundId));
    }

    private Duration backoff(int attempts) {
        long factor = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        var d = props.getRetryBackoff().multipliedBy(factor);
        return d.compareTo(props.getMaxBackoff()) > 0 ? props.getMaxBackoff() : d;
    }

    // ── recover and reconcile ──────────────────────────────────────────────

    /** A lease that ran out while SENDING: the call may have gone. Becomes AMBIGUOUS, to be looked up. */
    public boolean recoverExpiredLease(Long refundId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var g = refunds.lockById(refundId).orElseThrow();
            if (!GatewayRefund.SENDING.equals(g.getStatus()) || g.getClaimExpiresAt() == null
                    || g.getClaimExpiresAt().isAfter(Instant.now())) {
                return false;
            }
            g.setStatus(GatewayRefund.AMBIGUOUS);
            g.setClaimToken(null);
            g.setClaimExpiresAt(null);
            g.setNextRetryAt(Instant.now());
            g.setLastError("Lease ran out before an outcome was recorded");
            refunds.save(g);
            return true;
        }));
    }

    /** Read the gateway's own refund list for our receipt. Only a gateway that has none lets another be sent. */
    public Result reconcile(Long refundId) {
        var g0 = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("GatewayRefund", refundId));
        if (!GatewayRefund.AMBIGUOUS.equals(g0.getStatus())) {
            return Result.NOT_CLAIMED;
        }
        java.util.List<PaymentProvider.ProviderRefundEntry> listed;
        try {
            listed = provider.listRefunds(g0.getProviderPaymentId());
        } catch (RuntimeException ex) {
            return tx.execute(s -> stillUnknown(refundId, ex.getMessage()));
        }
        var match = listed.stream().filter(e -> g0.receipt().equals(e.receipt())).findFirst();
        return tx.execute(s -> {
            var g = refunds.lockById(refundId).orElseThrow();
            if (!GatewayRefund.AMBIGUOUS.equals(g.getStatus())) {
                return Result.NOT_CLAIMED;
            }
            if (match.isPresent() && match.get().status() == PaymentProvider.ProviderRefundStatus.COMPLETED) {
                succeed(g, match.get().providerRefundId());
                refunds.save(g);
                return Result.SUCCEEDED;
            }
            if (match.isPresent() && match.get().status() == PaymentProvider.ProviderRefundStatus.PENDING) {
                // It went, and the gateway has not finished it: wait for it, do not send another.
                markPending(g, match.get().providerRefundId());
                refunds.save(g);
                return Result.PENDING;
            }
            if (match.isPresent()) {
                // The gateway holds it as failed: a retry has to be a new refund under a new key.
                failConfirmed(g, match.get().providerRefundId(), "The gateway recorded this refund as failed");
                refunds.save(g);
                return Result.FAILED;
            }
            // Nothing under our receipt: it never went, so it may be sent again under the same key.
            g.setStatus(GatewayRefund.FAILED);
            g.setNextRetryAt(Instant.now());
            g.setLastError("The gateway has no refund under this receipt");
            refunds.save(g);
            return Result.FAILED;
        });
    }

    /** The gateway reported a refund it had accepted as failed: visible, and retryable under a new key. */
    private void failConfirmed(GatewayRefund g, String providerRefundId, String why) {
        g.setSendRound(g.getSendRound() + 1);
        g.setLastError(trim(why + (providerRefundId == null ? "" : " (" + providerRefundId + ")")));
        g.setProviderRefundId(null);
        if (g.getAttemptCount() >= props.getMaxAttempts()) {
            g.setStatus(GatewayRefund.EXHAUSTED);
            g.setNextRetryAt(null);
        } else {
            g.setStatus(GatewayRefund.FAILED);
            g.setNextRetryAt(Instant.now().plus(backoff(g.getAttemptCount())));
        }
    }

    /**
     * Ask the gateway what became of a refund it accepted but had not completed. COMPLETED is the only thing that makes
     * it a success (and the only time an outcome is announced); FAILED makes it visible and retryable under a new key;
     * still PENDING is asked again later, and after enough asks it shows in the finance queue as stuck.
     */
    public Result reconcilePending(Long refundId) {
        var g0 = refunds.findById(refundId).orElseThrow(() -> new NotFoundException("GatewayRefund", refundId));
        if (!GatewayRefund.PENDING.equals(g0.getStatus()) || g0.getProviderRefundId() == null) {
            return Result.NOT_CLAIMED;
        }
        PaymentProvider.ProviderRefund fetched;
        try {
            fetched = provider.fetchRefund(g0.getProviderRefundId());
        } catch (RuntimeException ex) {
            return tx.execute(s -> stillPending(refundId, g0.getProviderRefundId(), "Could not ask the gateway: " + ex.getMessage()));
        }
        return tx.execute(s -> {
            var g = refunds.lockById(refundId).orElseThrow();
            if (!GatewayRefund.PENDING.equals(g.getStatus()) || !g0.getProviderRefundId().equals(g.getProviderRefundId())) {
                return Result.NOT_CLAIMED;
            }
            switch (fetched.status()) {
                case COMPLETED -> {
                    succeed(g, g.getProviderRefundId());
                    refunds.save(g);
                    return Result.SUCCEEDED;
                }
                case FAILED -> {
                    String detail = java.util.stream.Stream.of(fetched.failureCode(), fetched.failureReason())
                            .filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining(" "));
                    failConfirmed(g, g.getProviderRefundId(), "The gateway reported the refund failed"
                            + (detail.isEmpty() ? "" : ": " + detail));
                    refunds.save(g);
                    return Result.FAILED;
                }
                default -> {
                    return stillPending(refundId, g.getProviderRefundId(), null);
                }
            }
        });
    }

    private Result stillPending(Long refundId, String providerRefundId, String note) {
        var g = refunds.lockById(refundId).orElseThrow();
        if (!GatewayRefund.PENDING.equals(g.getStatus()) || !providerRefundId.equals(g.getProviderRefundId())) {
            return Result.NOT_CLAIMED;
        }
        g.setLookupCount(g.getLookupCount() + 1);
        if (note != null) {
            g.setLastError(trim(note));
        }
        g.setNextRetryAt(Instant.now().plus(g.getLookupCount() >= props.getMaxLookups()
                ? Duration.ofDays(1) : props.getRecheckAfter()));
        refunds.save(g);
        return Result.PENDING;
    }

    private Result stillUnknown(Long refundId, String why) {
        var g = refunds.lockById(refundId).orElseThrow();
        if (!GatewayRefund.AMBIGUOUS.equals(g.getStatus())) {
            return Result.NOT_CLAIMED;
        }
        g.setLookupCount(g.getLookupCount() + 1);
        g.setLastError(trim("Could not ask the gateway: " + why));
        g.setNextRetryAt(Instant.now().plus(g.getLookupCount() >= props.getMaxLookups()
                ? Duration.ofDays(7) : props.getRecheckAfter()));
        refunds.save(g);
        return Result.AMBIGUOUS;
    }

    // ── finance reads (bounded) ─────────────────────────────────────────

    /** The refunds finance watches, each list bounded, with how many there really are and what is still unreturned. */
    public record Queues(java.util.List<GatewayRefund> overdue, java.util.List<GatewayRefund> exhausted,
                         java.util.List<GatewayRefund> ambiguous, java.util.List<GatewayRefund> pending,
                         long overdueCount, long exhaustedCount, long ambiguousCount, long pendingCount,
                         BigDecimal unreturned) {
    }

    @Transactional(readOnly = true)
    public Queues queues(int limit) {
        Instant before = Instant.now().minus(props.getOverdueAfter());
        BigDecimal unreturned = refunds.sumUnfinished();
        // A pending refund is a queue item once the gateway has had longer than a refund should take.
        return new Queues(refunds.overdue(before, limit), refunds.inStatus(GatewayRefund.EXHAUSTED, limit),
                refunds.inStatus(GatewayRefund.AMBIGUOUS, limit), refunds.pendingOlderThan(before, limit),
                refunds.countOverdue(before), refunds.countInStatus(GatewayRefund.EXHAUSTED),
                refunds.countInStatus(GatewayRefund.AMBIGUOUS), refunds.countPendingOlderThan(before),
                unreturned == null ? BigDecimal.ZERO : unreturned);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<GatewayRefund> search(String status, String sourceType, int page, int size) {
        return refunds.search(status, sourceType, org.springframework.data.domain.PageRequest.of(Math.max(page, 0), size));
    }

    // ── the gateway tells us (webhook) ─────────────────────────────────────

    /** What a verified refund event did to our records. */
    public enum EventOutcome {
        /** Moved a refund of ours. */
        APPLIED,
        /** Ours, but it says nothing new: a duplicate, an out-of-order event, or a state already reached. */
        NO_CHANGE,
        /** Ours, but a send is in flight; its own answer (or the poll) settles it. */
        DEFERRED,
        /** Not matched to any refund of ours, so nothing was touched. */
        UNMATCHED
    }

    /**
     * Apply a refund event the gateway sent, signature already verified (D-207). The poll stays the fallback; this only
     * makes the answer arrive sooner, and it never does more than the poll would:
     * <ul>
     *   <li><b>Matched, never guessed.</b> A refund is ours only if the payment id matches <em>and</em> either the
     *       provider refund id we stored or the receipt we sent does too, and the amount agrees. A payment id alone matches
     *       nothing: a payment can carry several refunds.</li>
     *   <li><b>Completed is the only success.</b> {@code COMPLETED} succeeds it; {@code PENDING} keeps or makes it pending;
     *       {@code FAILED} makes it visible and retryable under a new key, exactly as the poll does.</li>
     *   <li><b>Idempotent and order-safe.</b> Under the row lock, a state already reached (or a later one) is left alone: a
     *       duplicate, a {@code created} after {@code processed}, or a {@code failed} after a success change nothing. An
     *       event for an earlier send round (one we already treated as failed) is never applied; if it says that send
     *       completed, that is logged as a possible double refund for a person.</li>
     * </ul>
     */
    @Transactional
    public EventOutcome onGatewayEvent(String providerPaymentId, String providerRefundId, String receipt,
                                       BigDecimal amount, PaymentProvider.ProviderRefundStatus status, String detail) {
        if (providerPaymentId == null || providerPaymentId.isBlank() || status == null
                || ((providerRefundId == null || providerRefundId.isBlank()) && (receipt == null || receipt.isBlank()))) {
            return EventOutcome.UNMATCHED;
        }
        GatewayRefund match = null;
        boolean staleRound = false;
        for (var g : refunds.findByProviderPaymentIdOrderByIdAsc(providerPaymentId)) {
            boolean sameId = providerRefundId != null && providerRefundId.equals(g.getProviderRefundId());
            boolean currentReceipt = receipt != null && receipt.equals(g.receipt());
            boolean earlierReceipt = receipt != null && !currentReceipt && isEarlierRoundReceipt(g, receipt);
            if (!sameId && !currentReceipt && !earlierReceipt) {
                continue;
            }
            if (amount != null && g.getAmount().compareTo(amount) != 0) {
                continue;                               // the same ids with another amount is not this refund
            }
            match = g;
            staleRound = earlierReceipt && !sameId;
            break;
        }
        if (match == null) {
            return EventOutcome.UNMATCHED;
        }
        var g = refunds.lockById(match.getId()).orElseThrow();
        if (staleRound) {
            if (status == PaymentProvider.ProviderRefundStatus.COMPLETED) {
                log.error("ALERT: the gateway reports refund {} of an earlier send of gateway refund {} completed; "
                        + "a later send may also have been made. A person should check for a double refund.",
                        providerRefundId, g.getId());
                g.setLastError(trim("The gateway reports an earlier send (" + providerRefundId + ") completed; check for a double refund"));
                refunds.save(g);
                return EventOutcome.APPLIED;
            }
            return EventOutcome.NO_CHANGE;
        }
        if (GatewayRefund.SENDING.equals(g.getStatus())) {
            return EventOutcome.DEFERRED;
        }
        if (GatewayRefund.SUCCEEDED.equals(g.getStatus())) {
            return EventOutcome.NO_CHANGE;              // done: nothing a late or repeated event can add
        }
        switch (status) {
            case COMPLETED -> {
                succeed(g, providerRefundId != null ? providerRefundId : g.getProviderRefundId());
                refunds.save(g);
                return EventOutcome.APPLIED;
            }
            case PENDING -> {
                if (GatewayRefund.PENDING.equals(g.getStatus())) {
                    return EventOutcome.NO_CHANGE;
                }
                markPending(g, providerRefundId != null ? providerRefundId : g.getProviderRefundId());
                refunds.save(g);
                return EventOutcome.APPLIED;
            }
            default -> {
                failConfirmed(g, providerRefundId, "The gateway reported the refund failed"
                        + (detail == null || detail.isBlank() ? "" : ": " + detail));
                refunds.save(g);
                return EventOutcome.APPLIED;
            }
        }
    }

    /** Whether the receipt is one this refund used for a send that has since been retried under a new key. */
    private static boolean isEarlierRoundReceipt(GatewayRefund g, String receipt) {
        for (int round = 0; round < g.getSendRound(); round++) {
            String earlier = round == 0 ? g.getIdempotencyKey() : g.getIdempotencyKey() + "-r" + round;
            if (earlier.equals(receipt)) {
                return true;
            }
        }
        return false;
    }

    // ── operator actions ────────────────────────────────────────────────

    /** A person puts a stuck refund back in the queue to be sent again. */
    @Transactional
    public GatewayRefund retry(Long refundId) {
        var g = refunds.lockById(refundId).orElseThrow(() -> new NotFoundException("GatewayRefund", refundId));
        if (!GatewayRefund.EXHAUSTED.equals(g.getStatus()) && !GatewayRefund.FAILED.equals(g.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "Only a failed or exhausted refund can be retried; this one is " + g.getStatus() + ".");
        }
        g.setStatus(GatewayRefund.REQUESTED);
        g.setAttemptCount(0);
        g.setNextRetryAt(null);
        g.setLastError(null);
        return refunds.save(g);
    }

    /** The refund was made outside the system (the gateway dashboard); record it so the money is accounted for. */
    @Transactional
    public GatewayRefund resolveExternally(Long refundId, String providerRefundId) {
        var g = refunds.lockById(refundId).orElseThrow(() -> new NotFoundException("GatewayRefund", refundId));
        if (GatewayRefund.SUCCEEDED.equals(g.getStatus()) || GatewayRefund.SENDING.equals(g.getStatus())) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "This refund is " + g.getStatus() + ".");
        }
        if (providerRefundId == null || providerRefundId.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Give the gateway's refund id.");
        }
        succeed(g, providerRefundId);
        g.setClaimToken(null);
        g.setClaimExpiresAt(null);
        return refunds.save(g);
    }

    // ── the sweep ────────────────────────────────────────────────────────

    public record Pass(int sent, int leasesRecovered, int reconciled, int pendingChecked) {
    }

    /** One bounded pass over what is waiting; the job and the tests both call it. */
    public Pass sweep() {
        if (!props.isJobsEnabled()) {
            return new Pass(0, 0, 0, 0);
        }
        Instant now = Instant.now();
        int leases = 0;
        for (Long id : refunds.expiredLeases(now, props.getBatch())) {
            try {
                if (recoverExpiredLease(id)) {
                    leases++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not recover the lease on gateway refund {}", id, ex);
            }
        }
        int reconciled = 0;
        for (Long id : refunds.ambiguousToReconcile(now, props.getBatch())) {
            try {
                reconcile(id);
                reconciled++;
            } catch (RuntimeException ex) {
                log.error("Could not reconcile gateway refund {}", id, ex);
            }
        }
        int pendingChecked = 0;
        for (Long id : refunds.pendingToReconcile(now, props.getBatch())) {
            try {
                reconcilePending(id);
                pendingChecked++;
            } catch (RuntimeException ex) {
                log.error("Could not reconcile pending gateway refund {}", id, ex);
            }
        }
        int sent = 0;
        for (Long id : refunds.readyToSend(now, now.minus(props.getSweepGrace()), props.getBatch())) {
            try {
                if (send(id) != Result.NOT_CLAIMED) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not send gateway refund {}", id, ex);
            }
        }
        return new Pass(sent, leases, reconciled, pendingChecked);
    }

    private static String trim(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }
}
