package com.costonomy.mp.payment.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.ReleaseReason;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentFacts;
import com.costonomy.mp.payment.provider.PaymentProvider.ProviderPaymentStatus;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Sends a cancelled order's debited money back (D-109).
 *
 * <p>The cancel transaction only marks the payment CANCEL_PENDING: it cannot call
 * Razorpay (D-099), and it has no business deciding what a payment is from our own
 * record when Razorpay knows. This does the rest, one payment per call, in the
 * three steps every provider-touching write here takes (D-099):
 *
 * <ol>
 *   <li>a short transaction that locks the payment, confirms it is still waiting,
 *       and counts the attempt;</li>
 *   <li>the provider calls, with no transaction and no connection held;</li>
 *   <li>a short transaction that locks the payment again, checks it is
 *       <em>still</em> waiting, and writes the outcome.</li>
 * </ol>
 *
 * <p><b>Razorpay's answer decides, not our stored method.</b> A card is only a hold
 * (nothing debited: drop it); an authorised payment by anything else is debited and
 * cannot be refunded until it is captured, so it is captured and refunded to the
 * account it came from. The capture is a real charge on a payment the customer
 * agreed to pay for and is then reversed, which is why nothing here is guessed:
 * a payment that is not exactly what it should be is stopped for a person, never
 * captured.
 *
 * <p>Every step is safe to repeat. A capture whose answer was lost is found
 * already captured on the next run and is not sent again; the refund it raises has
 * a unique key, so two runs cannot make two.
 *
 * <p><b>Only Razorpay saying it does not know the payment (404, or its 400 "the id does not exist") stops one for a person.</b>
 * A rate limit, refused credentials, an outage or a malformed request say something
 * about the call, not the payment, and a payment's fate must not turn on a busy minute
 * at the provider or a rotated key: those wait for the next run and change nothing (F2).
 * Even a payment that was stopped is looked at again, slowly ({@link #recheckReviewed}),
 * because the provider may return the money on its own and the row must be able to end.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final CancellationLedger ledger;
    private final AuditService auditService;
    private final TransactionTemplate txTemplate;
    private final AlertThrottle alerts;

    /** How often a refusal that is not about the payment is repeated in the log, per kind and payment (N1, N5). */
    private static final java.time.Duration REFUSAL_LOG_EVERY = java.time.Duration.ofHours(1);

    /** How often "the provider is limiting or refusing us" is repeated while it lasts (N5). */
    private static final java.time.Duration OUTAGE_LOG_EVERY = java.time.Duration.ofMinutes(15);

    /** What a run of the job should do next after one payment. */
    public enum Outcome {
        /** Carry on with the next payment. */
        DONE,
        /**
         * The provider is limiting our calls or refuses our credentials: every further call
         * this run would meet the same answer, so the run stops and the next one tries again.
         */
        BACK_OFF,
        /**
         * The provider did not know the first two payments the run asked about: that is far more
         * likely to be our keys, mode or base URL than two payments that never existed. The run
         * stops with nothing sent to a person (see {@link Run}).
         */
        CONFIGURATION_FAULT
    }

    /**
     * One run of the cancellation job. It exists to tell one payment the provider does not know
     * from a provider that is not the one holding our payments (N1, item 4 of the third review).
     *
     * <p>Razorpay answers "the id does not exist" for a payment created under the other mode's
     * keys, another account, or a base URL that is not Razorpay's. Taken one payment at a time,
     * each answer sends its payment to a person, and a wrong key would put every waiting
     * cancellation (fifty a run) in review with an error each, after which nothing captures them
     * until someone clears each one. So the first payment answered "unknown" is held back: if the
     * very next payment examined is unknown too, the run stops as a configuration fault and
     * neither goes to review; if anything else follows, or the run ends, it goes to review as it
     * always did. Only the first two payments of a run are compared, so a run that has once been
     * answered normally trusts the answers after it.
     *
     * <p>Not thread-safe: one job run uses one.
     */
    public final class Run {
        private int examined;
        private Long heldId;
        private String heldWhy;

        private Run() {
        }

        /** Settle one payment; see {@link CancellationService#settle(Long)}. */
        public Outcome settle(Long paymentId) {
            return CancellationService.this.settle(paymentId, this);
        }

        /** The provider answered about a payment, whatever it said. */
        private void answered() {
            examined++;
        }

        /** The provider answered something other than "unknown": a held payment is no fault of the keys. */
        private void answeredKnown() {
            flushHeld();
        }

        /** Sends the payment held back, if any, to a person: on its own it is just an unknown payment. */
        public void finish() {
            flushHeld();
        }

        private void flushHeld() {
            if (heldId != null) {
                Long id = heldId;
                String why = heldWhy;
                heldId = null;
                heldWhy = null;
                markReview(id, why);
            }
        }

        /** The provider does not know this payment; what the run does with that. */
        private Outcome unknown(Long paymentId, String why) {
            boolean first = examined == 0;
            answered();
            if (first) {
                heldId = paymentId;
                heldWhy = why;
                return Outcome.DONE;
            }
            if (heldId != null && examined == 2 && !heldId.equals(paymentId)) {
                Long savedId = heldId;
                heldId = null;
                heldWhy = null;
                if (alerts.due("cancel-unknown-config", Instant.now(), OUTAGE_LOG_EVERY)) {
                    log.error("Configuration fault: provider does not know 2 payments in a row: check API keys/mode/base "
                            + "URL (payments {} and {}). Nothing was stopped for a person; the next run asks again",
                            savedId, paymentId);
                }
                return Outcome.CONFIGURATION_FAULT;
            }
            flushHeld();
            markReview(paymentId, why);
            return Outcome.DONE;
        }
    }

    /** A run of the job, which holds back a first unknown payment until the second answer is in. */
    public Run newRun() {
        return new Run();
    }

    /** What one run learned from the claim, before any provider call. */
    private record Claimed(Long paymentId, String providerPaymentId, String providerOrderId,
                           BigDecimal authorizedAmount, Long supplierOrderId) {
    }

    /**
     * Settle one CANCEL_PENDING payment, as far as the provider allows this run.
     * Called by the cancellation job; safe to call again and from two threads.
     */
    public Outcome settle(Long paymentId) {
        return settle(paymentId, null);
    }

    private Outcome settle(Long paymentId, Run run) {
        // (a) Claim: still waiting, and count the attempt.
        Claimed claimed = txTemplate.execute(status -> {
            var payment = payments.lockById(paymentId).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.CANCEL_PENDING
                    || payment.getReviewRequiredAt() != null) {
                return null;
            }
            payment.setCancelAttempts(payment.getCancelAttempts() + 1);
            // Saved so updated_at moves: the next run takes the payments waiting
            // longest, and this is proof that a run got this far.
            payments.save(payment);
            return new Claimed(payment.getId(), payment.getProviderPaymentId(),
                    payment.getProviderOrderId(), payment.getAuthorizedAmount(),
                    payment.getSupplierOrderId());
        });
        if (claimed == null) {
            return Outcome.DONE;
        }
        if (claimed.providerPaymentId() == null) {
            // CANCEL_PENDING is only ever reached from a payment that holds money,
            // which has its provider payment id. Without one there is nothing to ask.
            markReview(paymentId, "no provider payment id to look up");
            return Outcome.DONE;
        }

        // (b) Ask. No transaction is open.
        ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(claimed.providerPaymentId());
        } catch (PaymentProviderException ex) {
            return lookupFailed(paymentId, ex, run);
        }
        if (run != null) {
            run.answered();
            run.answeredKnown();
        }
        if (!matches(claimed, facts)) {
            // Never capture a payment that is not exactly the one this order made.
            markReview(paymentId, "provider payment " + facts.providerPaymentId()
                    + " does not match the order's payment");
            return Outcome.DONE;
        }

        // (c) Decide on the provider's answer.
        if (facts.status() == ProviderPaymentStatus.REFUNDED) {
            if (facts.captured()) {
                markReview(paymentId, "refunded at the provider outside Mandi");
            } else {
                providerReturnedIt(paymentId, facts);
            }
            return Outcome.DONE;
        }
        if (facts.amountRefunded().signum() > 0) {
            markReview(paymentId, "refunded at the provider outside Mandi");
            return Outcome.DONE;
        }

        switch (facts.status()) {
            case AUTHORIZED -> {
                if ("card".equals(facts.method())) {
                    dropCardHold(paymentId, facts);
                } else {
                    return captureThenRefund(paymentId, claimed, facts);
                }
            }
            case CAPTURED -> capturedNow(paymentId, facts, facts.fee());
            case RELEASED -> providerReturnedIt(paymentId, facts);
            default -> markReview(paymentId, "provider says " + facts.status()
                    + " for a payment we hold as debited");
        }
        return Outcome.DONE;
    }

    /** Whether the provider's payment is exactly the one this order made: its intent and its amount. */
    private static boolean matches(Claimed claimed, ProviderPaymentFacts facts) {
        return java.util.Objects.equals(claimed.providerOrderId(), facts.providerOrderId())
                && facts.amount().compareTo(claimed.authorizedAmount()) == 0;
    }

    /**
     * The lookup failed. Only "Razorpay does not know that payment" says something about the
     * payment and stops it for a person: HTTP 404, or the HTTP 400 "The id provided does not
     * exist" that Razorpay actually answers on GET /v1/payments/{id} (the provider maps that
     * one, and only that one, to NOT_FOUND; N1). Everything else says something about the call
     * and changes nothing: the payment stays CANCEL_PENDING for the next run, and the alert on
     * a payment that has waited too long still fires (F2).
     */
    private Outcome lookupFailed(Long paymentId, PaymentProviderException ex, Run run) {
        if (ex.isNotFound()) {
            String why = "provider does not know the payment (" + ex.getMessage() + ")";
            if (run != null) {
                return run.unknown(paymentId, why);
            }
            markReview(paymentId, why);
            return Outcome.DONE;
        }
        // Not an answer about the payment, so nothing was learnt about our keys: a payment held
        // back stays held. If the next answer is another unknown it is a configuration fault; if
        // a known one, it goes to a person; and if nothing follows, finish() sends it, as ever.
        Instant now = Instant.now();
        if (ex.isRateLimited()) {
            if (alerts.due("cancel-ratelimit", now, OUTAGE_LOG_EVERY)) {
                log.warn("Razorpay is rate limiting the lookup of payment {}; the cancellation job waits "
                        + "for its next run: {}", paymentId, ex.getMessage());
            }
            return Outcome.BACK_OFF;
        }
        if (ex.isCredentialsRefused()) {
            // Every call will fail until the keys are fixed, and nothing about any payment is
            // known. Loud, and once per interval: a run every fifteen seconds would write it 240
            // times an hour. This is the line that says money is not being returned.
            if (alerts.due("credentials-cancel", now, OUTAGE_LOG_EVERY)) {
                log.error("Razorpay refused our credentials on the lookup of payment {} ({}); cancelled "
                        + "orders cannot be returned until the keys are fixed", paymentId, ex.providerCode());
            }
            return Outcome.BACK_OFF;
        }
        // A 400 that is not "unknown id" would repeat every run for as long as it lasts, for
        // every payment it applies to: once an hour per payment, like the other standing alerts.
        if (alerts.due("cancel-lookup-" + paymentId, now, REFUSAL_LOG_EVERY)) {
            log.warn("Cancellation of payment {} waiting on provider: {}", paymentId, ex.getMessage());
        }
        return Outcome.DONE;
    }

    /**
     * A payment stopped for a person, looked at again (F2). Slow, and never a decision on a
     * guess: it only acts where the provider's answer is <em>final</em> and unambiguous, for
     * exactly this order's payment.
     *
     * <ul>
     *   <li>the provider returned it itself (an authorisation that expired): RELEASED;</li>
     *   <li>it is captured and nobody has refunded any of it (a capture made outside Mandi,
     *       or one whose answer was lost): the refund is raised.</li>
     * </ul>
     *
     * Anything else — still authorised, a mismatch, a partial refund by someone else, a
     * lookup that fails — leaves it exactly as it was, with the person. Cannot capture.
     */
    public Outcome recheckReviewed(Long paymentId) {
        Claimed claimed = txTemplate.execute(status -> {
            var payment = payments.lockById(paymentId).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.CANCEL_PENDING
                    || payment.getReviewRequiredAt() == null || payment.getProviderPaymentId() == null) {
                return null;
            }
            return new Claimed(payment.getId(), payment.getProviderPaymentId(),
                    payment.getProviderOrderId(), payment.getAuthorizedAmount(),
                    payment.getSupplierOrderId());
        });
        if (claimed == null) {
            return Outcome.DONE;
        }
        ProviderPaymentFacts facts;
        try {
            facts = provider.inspect(claimed.providerPaymentId());
        } catch (PaymentProviderException ex) {
            // Noted as asked, so a failing lookup is retried in hours like any other, and
            // says the same things as when the payment is first settled.
            payments.markAsked(paymentId, Instant.now());
            if (ex.isNotFound()) {
                // Already with a person: not stopped a second time, only noted.
                log.warn("Payment {} is still in review: the provider still does not know it", paymentId);
                return Outcome.DONE;
            }
            return lookupFailed(paymentId, ex, null);
        }
        payments.markAsked(paymentId, Instant.now());

        if (!matches(claimed, facts)) {
            return Outcome.DONE;
        }
        boolean returnedByProvider = facts.status() == ProviderPaymentStatus.RELEASED
                || (facts.status() == ProviderPaymentStatus.REFUNDED && !facts.captured());
        if (returnedByProvider) {
            providerReturnedIt(paymentId, facts);
        } else if (facts.status() == ProviderPaymentStatus.CAPTURED && facts.amountRefunded().signum() == 0) {
            capturedNow(paymentId, facts, facts.fee());
        }
        return Outcome.DONE;
    }

    /** A card the bank was only holding: drop it. The method is learned for the record. */
    private void dropCardHold(Long paymentId, ProviderPaymentFacts facts) {
        txTemplate.executeWithoutResult(status -> {
            var payment = stillPending(paymentId);
            if (payment == null) {
                return;
            }
            rememberMethod(payment, facts);
            leaveReview(payment);
            ledger.release(payment, ReleaseReason.CARD_HOLD_DROPPED,
                    "card hold dropped on cancellation", "CANCEL_JOB");
        });
    }

    /** Razorpay returned the authorisation itself: record that it went back. */
    private void providerReturnedIt(Long paymentId, ProviderPaymentFacts facts) {
        txTemplate.executeWithoutResult(status -> {
            var payment = stillPending(paymentId);
            if (payment == null) {
                return;
            }
            rememberMethod(payment, facts);
            leaveReview(payment);
            log.warn("Provider returned payment {} itself", paymentId);
            ledger.release(payment, ReleaseReason.PROVIDER_AUTO_REFUND,
                    "The provider returned the authorisation unused", "CANCEL_JOB");
        });
    }

    /**
     * Debited, not yet captured: capture it, so it can be refunded.
     *
     * <p>The capture has no transaction around it. A refusal that is not a retry
     * leaves the payment CANCEL_PENDING and the next run asks again — Razorpay's own
     * expiry may have returned it meanwhile, which the next inspect will say.
     */
    private Outcome captureThenRefund(Long paymentId, Claimed claimed, ProviderPaymentFacts facts) {
        log.info("Capturing payment {} to return it: order {} was cancelled (method {})",
                paymentId, claimed.supplierOrderId(), facts.method());
        BigDecimal fee = null;
        try {
            provider.capture(claimed.providerPaymentId(), claimed.authorizedAmount(),
                    CancellationLedger.cancelCaptureKey(paymentId));
        } catch (PaymentProviderException ex) {
            if (ex.isRateLimited() || ex.isCredentialsRefused()) {
                // Not about this payment: the run stops, the next one asks again.
                log.warn("Capture to return payment {} refused for a reason that is not the payment's ({}); "
                        + "waiting for the next run", paymentId, ex.getMessage());
                return Outcome.BACK_OFF;
            }
            if (ex.isRetryable()) {
                log.warn("Capture to return payment {} failed, will retry: {}", paymentId, ex.getMessage());
            } else {
                log.warn("Provider refused to capture payment {} to return it: {}; asking again next run",
                        paymentId, ex.getMessage());
            }
            return Outcome.DONE;
        }
        // The fee is only known once the money is taken. Best effort: the refund
        // must not wait on a cost figure.
        try {
            fee = provider.inspect(claimed.providerPaymentId()).fee();
        } catch (PaymentProviderException ex) {
            log.warn("Could not read the fee on payment {}: {}", paymentId, ex.getMessage());
        }
        capturedNow(paymentId, facts, fee);
        return Outcome.DONE;
    }

    /** The money is taken: record it and raise the refund, in one transaction. */
    private void capturedNow(Long paymentId, ProviderPaymentFacts facts, BigDecimal fee) {
        txTemplate.executeWithoutResult(status -> {
            var payment = stillPending(paymentId);
            if (payment == null) {
                return;
            }
            rememberMethod(payment, facts);
            leaveReview(payment);
            ledger.captureForReturn(payment, fee, "CANCEL_JOB");
        });
    }

    /**
     * A payment no run should touch again until a person has looked: stopped, with
     * an alert. Never captured, never released, so the money is not moved on a guess.
     */
    void markReview(Long paymentId, String reason) {
        txTemplate.executeWithoutResult(status -> {
            var payment = payments.lockById(paymentId).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.CANCEL_PENDING) {
                return;
            }
            String text = reason.length() <= 200 ? reason : reason.substring(0, 200);
            payment.setReviewRequiredAt(Instant.now());
            payment.setReviewReason(text);
            payments.save(payment);
            auditService.record(null, null, "PAYMENT_REVIEW_REQUIRED", "PAYMENT", payment.getId(),
                    PaymentStatus.CANCEL_PENDING.name(), PaymentStatus.CANCEL_PENDING.name(),
                    text, "CANCEL_JOB");
            // Error: a debited payment for a cancelled order is not being returned,
            // and a person has to act. This line is what an alert should match.
            log.error("Cancellation of payment {} needs a person: {}", paymentId, text);
        });
    }

    /** The payment, locked, if it is still waiting to be settled; null if another run did it. */
    private Payment stillPending(Long paymentId) {
        var payment = payments.lockById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CANCEL_PENDING) {
            return null;
        }
        return payment;
    }

    /**
     * The payment ends here (released or captured to be returned), so it is no longer
     * waiting for a person, if it ever was: a re-check of a reviewed payment found the
     * provider's answer final. Cleared with the rest of the state change so the flag never
     * outlives the state it was about.
     */
    private static void leaveReview(Payment payment) {
        if (payment.getReviewRequiredAt() != null) {
            log.warn("Payment {} left review: the provider's answer is final ({})", payment.getId(),
                    payment.getReviewReason());
            payment.setReviewRequiredAt(null);
            payment.setReviewReason(null);
        }
    }

    private static void rememberMethod(Payment payment, ProviderPaymentFacts facts) {
        if (facts.method() != null) {
            payment.setProviderMethod(facts.method());
            payment.setProviderMethodDetail(facts.methodDetail());
        }
    }
}
