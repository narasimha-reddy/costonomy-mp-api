package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.CourierPayable;
import com.costonomy.mp.delivery.domain.CourierPayableState;
import com.costonomy.mp.delivery.domain.CourierPaymentAttempt;
import com.costonomy.mp.delivery.provider.payment.CourierPaymentRail;
import com.costonomy.mp.delivery.provider.payment.CourierPaymentRails;
import com.costonomy.mp.delivery.repository.CourierPayableRepository;
import com.costonomy.mp.delivery.repository.CourierPaymentAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Pays courier payables through their rail (D-204).
 *
 * <p>Three steps, none holding a database lock across the provider call (D-099):
 * <ol>
 *   <li>a claim: one conditional update takes the payable under a lease and counts the try, so two workers cannot both
 *       pay it; then a short transaction writes the attempt row, with the key the rail is given;</li>
 *   <li>the rail call, with no transaction open;</li>
 *   <li>a short transaction that records the outcome, only if the lease is still ours.</li>
 * </ol>
 *
 * <p><b>An unknown outcome is asked about, never repeated.</b> A timeout, an exception, or a lease that ran out leaves the
 * payable {@code AMBIGUOUS}; the rail is then asked what became of that key. Only a rail that says it never saw the key
 * lets a new attempt go out, under a new key. After {@code maxLookups} unanswered questions it is handed to a person.
 * A definite failure is retried with doubling delay up to {@code maxAttempts}, then waits for a person
 * ({@code EXHAUSTED}). Every step is recorded in the payable's history.
 */
@Service
@Slf4j
public class CourierPaymentService {

    public enum Result { NOT_CLAIMED, PAID, PARTIALLY_PAID, FAILED, EXHAUSTED, AMBIGUOUS, LEASE_LOST, NOTHING_OWED }

    private static final String JOB = "JOB";

    private final CourierPayableRepository payables;
    private final CourierPaymentAttemptRepository attempts;
    private final CourierPayableService payableService;
    private final CourierPaymentRails rails;
    private final CourierPayableProperties props;
    private final TransactionTemplate tx;

    public CourierPaymentService(CourierPayableRepository payables, CourierPaymentAttemptRepository attempts,
                                 CourierPayableService payableService, CourierPaymentRails rails,
                                 CourierPayableProperties props, PlatformTransactionManager txManager) {
        this.payables = payables;
        this.attempts = attempts;
        this.payableService = payableService;
        this.rails = rails;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Prepared(Long payableId, Long attemptId, String rail, String key, BigDecimal amount, String currency,
                            String providerCode, String providerDeliveryId) {
    }

    // ── pay ──────────────────────────────────────────────────────────────

    public Result pay(Long payableId) {
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> payables.claim(payableId, token, now.plus(props.getLease()), now));
        if (claimed == null || claimed == 0) {
            return Result.NOT_CLAIMED;
        }

        Prepared prepared = tx.execute(s -> {
            var p = payables.lockById(payableId).orElseThrow();
            if (!token.equals(p.getClaimToken())) {
                return null;
            }
            BigDecimal amount = p.balance();
            if (amount.signum() <= 0) {
                // A credit or a lower cost arrived since: nothing left to send.
                var before = p.getState();
                p.setState(p.getAmountPaid().signum() > 0 ? CourierPayableState.PAID : CourierPayableState.VOID);
                p.setClaimToken(null);
                p.setClaimExpiresAt(null);
                payables.save(p);
                payableService.event(p, "NOTHING_OWED", before, p.getState(), BigDecimal.ZERO, JOB, null,
                        "Nothing was left to pay when the attempt started");
                return new Prepared(payableId, null, null, null, BigDecimal.ZERO, null, null, null);
            }
            // Numbered from the history, not from the retry counter: a person can reset the counter, and a key already
            // given to the rail must never be given again for a different attempt.
            int number = attempts.findFirstByPayableIdOrderByAttemptNumberDesc(payableId)
                    .map(x -> x.getAttemptNumber() + 1).orElse(1);
            var a = new CourierPaymentAttempt();
            a.setPayableId(payableId);
            a.setAttemptNumber(number);
            a.setRail(p.getPaymentRail());
            a.setIdempotencyKey("cp-" + payableId + "-" + number);
            a.setAmount(amount);
            attempts.save(a);
            payableService.event(p, "PAYMENT_STARTED", CourierPayableState.PROCESSING, CourierPayableState.PROCESSING,
                    amount, JOB, a.getIdempotencyKey(), "attempt " + number + " via " + p.getPaymentRail());
            return new Prepared(payableId, a.getId(), p.getPaymentRail(), a.getIdempotencyKey(), amount, p.getCurrency(),
                    p.getProviderCode(), p.getProviderDeliveryId());
        });
        if (prepared == null) {
            return Result.LEASE_LOST;
        }
        if (prepared.attemptId() == null) {
            return Result.NOTHING_OWED;
        }

        // No transaction is open here.
        CourierPaymentRail.PayResult result;
        try {
            result = rails.require(prepared.rail()).pay(new CourierPaymentRail.PayRequest(prepared.payableId(),
                    prepared.providerCode(), prepared.providerDeliveryId(), prepared.amount(), prepared.currency(),
                    prepared.key(), "Courier fee " + prepared.providerDeliveryId()));
        } catch (RuntimeException ex) {
            // We cannot tell whether it went. Treated as unknown, never as a failure that is safe to repeat.
            result = CourierPaymentRail.PayResult.ambiguous(String.valueOf(ex.getMessage()));
        }
        final var outcome = result;
        return tx.execute(s -> record(prepared, token, outcome));
    }

    private Result record(Prepared prep, String token, CourierPaymentRail.PayResult r) {
        var p = payables.lockById(prep.payableId()).orElseThrow();
        var a = attempts.findById(prep.attemptId()).orElseThrow();
        if (!token.equals(p.getClaimToken()) || !CourierPayableState.PROCESSING.equals(p.getState())) {
            // The lease ran out and recovery took over; it will ask the rail what became of this key.
            log.warn("Courier payable {}: outcome {} arrived after the lease was lost", p.getId(), r.outcome());
            return Result.LEASE_LOST;
        }
        a.setEndedAt(Instant.now());
        switch (r.outcome()) {
            case PAID -> {
                a.setStatus(CourierPaymentAttempt.PAID);
                a.setReference(r.reference());
                attempts.save(a);
                return applyPaid(p, a, r.reference(), JOB, "PAYMENT_SUCCEEDED");
            }
            case FAILED -> {
                a.setStatus(CourierPaymentAttempt.FAILED);
                a.setError(trim(r.error()));
                attempts.save(a);
                return applyFailed(p, r.error());
            }
            default -> {
                a.setStatus(CourierPaymentAttempt.AMBIGUOUS);
                a.setError(trim(r.error()));
                attempts.save(a);
                var from = p.getState();
                p.setState(CourierPayableState.AMBIGUOUS);
                p.setLastError(trim(r.error()));
                p.setClaimToken(null);
                p.setClaimExpiresAt(null);
                p.setNextRetryAt(Instant.now().plus(props.getRecheckAfter()));
                payables.save(p);
                payableService.event(p, "PAYMENT_AMBIGUOUS", from, p.getState(), a.getAmount(), JOB, a.getIdempotencyKey(),
                        r.error());
                return Result.AMBIGUOUS;
            }
        }
    }

    private Result applyPaid(CourierPayable p, CourierPaymentAttempt a, String reference, String actor, String eventType) {
        var from = p.getState();
        p.setAmountPaid(p.getAmountPaid().add(a.getAmount()));
        p.setPaymentReference(reference);
        p.setClaimToken(null);
        p.setClaimExpiresAt(null);
        p.setNextRetryAt(null);
        p.setLastError(null);
        boolean full = p.getAmountPaid().compareTo(p.getAmountDue()) >= 0;
        if (full) {
            p.setState(CourierPayableState.PAID);
            p.setPaidAt(Instant.now());
        } else {
            p.setState(CourierPayableState.DUE);
        }
        if (p.getAmountPaid().compareTo(p.getAmountDue()) > 0) {
            payableService.flag(p, "Paid " + p.getAmountPaid().toPlainString() + " but only "
                    + p.getAmountDue().toPlainString() + " is now due");
        }
        payables.save(p);
        payableService.event(p, eventType, from, p.getState(), a.getAmount(), actor, reference, null);
        return full ? Result.PAID : Result.PARTIALLY_PAID;
    }

    private Result applyFailed(CourierPayable p, String error) {
        var from = p.getState();
        p.setLastError(trim(error));
        p.setClaimToken(null);
        p.setClaimExpiresAt(null);
        if (p.getAttemptCount() >= props.getMaxAttempts()) {
            p.setState(CourierPayableState.EXHAUSTED);
            p.setNextRetryAt(null);
            payables.save(p);
            payableService.event(p, "PAYMENT_EXHAUSTED", from, p.getState(), p.getAmountDue(), JOB, null,
                    "Gave up after " + p.getAttemptCount() + " attempts: " + error);
            return Result.EXHAUSTED;
        }
        p.setState(CourierPayableState.FAILED);
        p.setNextRetryAt(Instant.now().plus(backoff(p.getAttemptCount())));
        payables.save(p);
        payableService.event(p, "PAYMENT_FAILED", from, p.getState(), p.getAmountDue(), JOB, null, error);
        return Result.FAILED;
    }

    private Duration backoff(int attemptCount) {
        long factor = 1L << Math.min(Math.max(attemptCount - 1, 0), 20);
        var d = props.getRetryBackoff().multipliedBy(factor);
        return d.compareTo(props.getMaxBackoff()) > 0 ? props.getMaxBackoff() : d;
    }

    // ── recover and reconcile ──────────────────────────────────────────────

    /** A lease that ran out while PROCESSING: the answer was lost, so the payment may have gone. Becomes AMBIGUOUS. */
    public boolean recoverExpiredLease(Long payableId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var p = payables.lockById(payableId).orElseThrow();
            if (!CourierPayableState.PROCESSING.equals(p.getState()) || p.getClaimExpiresAt() == null
                    || p.getClaimExpiresAt().isAfter(Instant.now())) {
                return false;
            }
            attempts.findFirstByPayableIdOrderByAttemptNumberDesc(payableId).ifPresent(a -> {
                if (CourierPaymentAttempt.SENDING.equals(a.getStatus())) {
                    a.setStatus(CourierPaymentAttempt.AMBIGUOUS);
                    a.setEndedAt(Instant.now());
                    a.setError("Lease ran out before an outcome was recorded");
                    attempts.save(a);
                }
            });
            var from = p.getState();
            p.setState(CourierPayableState.AMBIGUOUS);
            p.setClaimToken(null);
            p.setClaimExpiresAt(null);
            p.setNextRetryAt(Instant.now());
            p.setLastError("Lease ran out before an outcome was recorded");
            payables.save(p);
            payableService.event(p, "LEASE_EXPIRED", from, p.getState(), p.getAmountDue(), JOB, null, p.getLastError());
            return true;
        }));
    }

    /** Ask the rail what became of the last attempt. Only a rail that never saw the key lets a new attempt go out. */
    public Result reconcileAmbiguous(Long payableId) {
        record Q(Long attemptId, String rail, String key) {
        }
        Q q = tx.execute(s -> {
            var p = payables.lockById(payableId).orElseThrow();
            if (!CourierPayableState.AMBIGUOUS.equals(p.getState())) {
                return null;
            }
            var a = attempts.findFirstByPayableIdOrderByAttemptNumberDesc(payableId).orElse(null);
            return a == null ? null : new Q(a.getId(), a.getRail(), a.getIdempotencyKey());
        });
        if (q == null) {
            return Result.NOT_CLAIMED;
        }
        CourierPaymentRail.LookupResult found;
        try {
            found = rails.require(q.rail()).lookup(q.key());
        } catch (RuntimeException ex) {
            found = CourierPaymentRail.LookupResult.unknown();
        }
        final var lookup = found;
        return tx.execute(s -> {
            var p = payables.lockById(payableId).orElseThrow();
            if (!CourierPayableState.AMBIGUOUS.equals(p.getState())) {
                return Result.NOT_CLAIMED;
            }
            var a = attempts.findById(q.attemptId()).orElseThrow();
            switch (lookup.found()) {
                case PAID -> {
                    a.setStatus(CourierPaymentAttempt.PAID);
                    a.setReference(lookup.reference());
                    a.setEndedAt(Instant.now());
                    attempts.save(a);
                    return applyPaid(p, a, lookup.reference(), JOB, "PAYMENT_CONFIRMED_BY_LOOKUP");
                }
                case NOT_FOUND -> {
                    a.setStatus(CourierPaymentAttempt.FAILED);
                    a.setError("The rail has no record of this key");
                    a.setEndedAt(Instant.now());
                    attempts.save(a);
                    var from = p.getState();
                    p.setState(CourierPayableState.FAILED);
                    p.setNextRetryAt(Instant.now());
                    payables.save(p);
                    payableService.event(p, "AMBIGUOUS_NOT_FOUND", from, p.getState(), a.getAmount(), JOB, q.key(),
                            "The rail never saw the payment; it may be sent again under a new key");
                    return Result.FAILED;
                }
                default -> {
                    long asked = payableService.countEvents(p.getId(), "AMBIGUOUS_LOOKUP") + 1;
                    payableService.event(p, "AMBIGUOUS_LOOKUP", p.getState(), p.getState(), a.getAmount(), JOB, q.key(),
                            "The rail could not say (question " + asked + ")");
                    if (asked >= props.getMaxLookups()) {
                        payableService.flag(p, "The rail could not say whether payment " + q.key() + " went; check the bank");
                        p.setNextRetryAt(Instant.now().plus(Duration.ofDays(7)));
                    } else {
                        p.setNextRetryAt(Instant.now().plus(props.getRecheckAfter()));
                    }
                    payables.save(p);
                    return Result.AMBIGUOUS;
                }
            }
        });
    }

    private static String trim(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }
}
