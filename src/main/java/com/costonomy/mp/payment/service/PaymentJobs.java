package com.costonomy.mp.payment.service;

import com.costonomy.mp.payment.domain.PaymentStatus;
import com.costonomy.mp.payment.domain.RefundStatus;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.repository.PaymentRepository;
import com.costonomy.mp.payment.repository.PaymentTransactionRepository;
import com.costonomy.mp.payment.repository.RefundRepository;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Background payment work. Doc 38, doc 21, doc 46.
 *
 * <p>Four jobs, each closing a gap that a synchronous flow cannot:
 *
 * <ul>
 *   <li><b>Capture</b> — completes captures marked during an acceptance, which
 *       deliberately did not call the provider inside that transaction.</li>
 *   <li><b>Reconciliation</b> — asks the provider about payments that stalled.
 *       This is doc 46's lost-callback recovery: the customer paid, the client
 *       vanished, no webhook arrived, and only asking reveals it.</li>
 *   <li><b>Refunds</b> — sends requested refunds and retries failed ones.</li>
 *   <li><b>Cancellations</b> — sends a cancelled order's debited money back (D-109).</li>
 * </ul>
 *
 * <p>All three are idempotent and locked, so a second instance cannot double-take
 * or double-return money.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentJobs {

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final RefundRepository refunds;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final OrderReleaseService orderRelease;
    private final PaymentProvider provider;
    private final CancellationService cancellations;
    private final PaymentHoldPolicy holdPolicy;
    private final WithdrawalReversalService reversals;

    /**
     * How long an unpaid intent is still worth asking about. A day comfortably
     * covers a lost callback; past it, nobody is mid-checkout.
     */
    private static final Duration INTENT_LOOKUP_WINDOW = Duration.ofDays(1);

    /**
     * How long a payment may sit with no provider checkout before it is ended. A failed create is retried by the app
     * within seconds, so this only catches a customer who walked away (D-136).
     */
    private static final Duration CHECKOUT_NEVER_OPENED_AFTER = Duration.ofMinutes(30);

    /**
     * Per run. A run takes the oldest first and the next run takes the rest, so a
     * backlog drains in order without one run holding the scheduler for minutes.
     */
    static final int CAPTURE_BATCH = 100;
    static final int RECONCILE_BATCH = 200;
    static final int CANCEL_BATCH = 50;

    /** A cancelled order's money still not on its way back after this long is an error for someone to see (D-109). */
    static final Duration CANCEL_ALERT_AFTER = Duration.ofMinutes(15);

    /**
     * How long one run of the cancellation job may take before it stops and leaves the rest
     * to the next run (F8). Below the job's lock ({@code lockAtMostFor}, five minutes) with room
     * for the payment in hand, which costs at most three provider calls of fifteen seconds: a
     * run that outlives its lock lets a second instance start the same batch. Safe to repeat
     * (a capture is found already taken, the refund has a unique key), but it doubles the calls
     * to a provider that may already be struggling.
     */
    static final Duration CANCEL_BUDGET = Duration.ofMinutes(3);

    /** The most a run looks at among payments waiting for a person. */
    static final int REVIEW_BATCH = 200;

    /** How often a payment stopped for a person is asked about again (F2): it can still end on its own. */
    static final Duration REVIEW_RECHECK_EVERY = Duration.ofHours(3);

    /** How often a payment stopped for a person is written to the error log while it stays there (F2). */
    static final Duration REVIEW_ALERT_EVERY = Duration.ofHours(24);

    /** How often one cancellation that has not started back is repeated in the error log (F7). */
    static final Duration CANCEL_ALERT_EVERY = Duration.ofHours(1);

    /** How often "the provider is not accepting our calls" is repeated in the log while it lasts (N4, N5). */
    static final Duration BACK_OFF_ALERT_EVERY = Duration.ofMinutes(15);

    /** How long a refund may stay FAILED before it is an error for someone to see (N4). */
    static final Duration REFUND_FAILED_ALERT_AFTER = Duration.ofHours(1);

    /** How often one refund stuck FAILED is repeated in the error log (N4). */
    static final Duration REFUND_FAILED_ALERT_EVERY = Duration.ofHours(1);

    /** How often a payment held for dispatch is checked with the provider (D-103). */
    static final Duration HELD_CHECK_EVERY = Duration.ofHours(6);

    /** Per run; a field so a test can shrink it and see what happens past a full batch. */
    int reconcileBatch = RECONCILE_BATCH;

    /** A field so a test can spend it at once and see the run stop. */
    Duration cancelBudget = CANCEL_BUDGET;

    /** When each alert last went to the error log; shared with the services that alert (N5). */
    private final AlertThrottle alerts;

    /** True, and remembered, if this alert has not been written within {@code every}. */
    private boolean alertDue(String key, Instant now, Duration every) {
        return alerts.due(key, now, every);
    }

    /**
     * Whether an unpaid intent is due to be asked about again.
     *
     * <p>The gap grows with the intent's age — a fifth of it, between two minutes
     * and thirty. A lost callback shows up in the first few checks; an abandoned
     * checkout then costs about sixty lookups over its day instead of 1,440, which
     * at any volume is the difference between a safety net and a rate limit (D-099).
     */
    static boolean dueForIntentLookup(Instant createdAt, Instant lastAsked, Instant now) {
        long ageSeconds = Duration.between(createdAt, now).getSeconds();
        long gap = Math.max(120, Math.min(1800, ageSeconds / 5));
        return lastAsked == null || lastAsked.isBefore(now.minusSeconds(gap));
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.payments.capture-interval:PT10S}")
    @SchedulerLock(name = "payment-capture", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void capturePending() {
        var pending = payments.findPendingCaptures(PageRequest.of(0, CAPTURE_BATCH));
        var stillPending = new java.util.HashSet<Long>();
        for (var payment : pending) {
            // The scope wraps the catch too: an error line without the payment's
            // ids is the one you most need to find (D-100).
            try (var trace = PaymentTrace.of(payment)) {
                try {
                    var outcome = paymentService.performCapture(payment.getId());
                    if (outcome != PaymentService.CaptureOutcome.DONE) {
                        // Rate limited, or our keys refused: every other capture would meet the
                        // same answer and add to the load. The payment stays CAPTURE_PENDING and
                        // the next run, ten seconds on, tries again. Once per interval, as for
                        // the other jobs.
                        alertCaptureNearLimit(payment);
                        if (outcome == PaymentService.CaptureOutcome.CREDENTIALS_REFUSED) {
                            if (alertDue("credentials-capture", Instant.now(), BACK_OFF_ALERT_EVERY)) {
                                log.error("Razorpay refused our credentials capturing payment {}; captures "
                                        + "resume when the keys are fixed, and an authorisation that "
                                        + "lapses first is money the supplier never receives",
                                        payment.getId());
                            }
                        } else if (alertDue("capture-backoff", Instant.now(), BACK_OFF_ALERT_EVERY)) {
                            log.warn("Capture run stopped after payment {}: Razorpay is limiting our "
                                    + "calls; the next run tries again", payment.getId());
                        }
                        return;
                    }
                    if (paymentStillPending(payment.getId())) {
                        stillPending.add(payment.getId());
                        alertCaptureNearLimit(payment);
                    }
                } catch (RuntimeException ex) {
                    // One stuck payment must not block the rest — otherwise a single
                    // poison row would leave every later capture unprocessed, and an
                    // uncaptured authorisation eventually lapses into money the
                    // supplier never receives.
                    log.error("Could not capture payment {}", payment.getId(), ex);
                }
            }
        }
        // One that was captured (or ended) starts afresh if it ever returns.
        alerts.forgetIf(key -> (key.startsWith("capture-limit-") || key.startsWith("capture-failing-"))
                && !stillPending.contains(Long.parseLong(key.substring(key.lastIndexOf('-') + 1))));
    }

    private boolean paymentStillPending(Long paymentId) {
        return payments.findById(paymentId)
                .map(payment -> payment.getStatus() == PaymentStatus.CAPTURE_PENDING).orElse(false);
    }

    /** How long a capture may keep failing before it is an error for someone to see: it is expected within seconds of "ready". */
    static final Duration CAPTURE_FAILING_ALERT_AFTER = Duration.ofHours(1);

    /**
     * Error, for an alert to match: a capture that keeps failing. Two conditions, each once an
     * hour per payment, so an alert never waits for the last hours of the hold:
     * <ul>
     *   <li>it has been failing for more than an hour, timed from its first failed attempt (the
     *       capture is expected within seconds of "ready", the refund side has the same rule for
     *       REQUESTED); a 429 or a 5xx that lasts would otherwise be a WARN until the hold is
     *       nearly gone;</li>
     *   <li>the hold is nearly used up. The authorisation lapses at the provider's limit, the goods
     *       are already with the buyer, and the supplier is then never paid: a person has to act
     *       before that. Past the point where a supplier would be refused at "ready"
     *       ({@link PaymentHoldPolicy#usableFor}).</li>
     * </ul>
     * Called only for a payment that is still CAPTURE_PENDING after its attempt, so a capture that
     * goes through the first time never writes either.
     */
    private void alertCaptureNearLimit(com.costonomy.mp.payment.domain.Payment payment) {
        Instant now = Instant.now();
        var firstFailure = transactions.findFirstByPaymentIdAndTransactionTypeAndStatusOrderByCreatedAtAsc(
                payment.getId(), "CAPTURE", "FAILED");
        if (firstFailure.isPresent() && firstFailure.get().getCreatedAt() != null
                && firstFailure.get().getCreatedAt().isBefore(now.minus(CAPTURE_FAILING_ALERT_AFTER))
                && alertDue("capture-failing-" + payment.getId(), now, CANCEL_ALERT_EVERY)) {
            log.error("Payment {} has been CAPTURE_PENDING and failing to capture since {}: the supplier is "
                    + "not paid until it goes through; a person must look",
                    payment.getId(), firstFailure.get().getCreatedAt());
        }
        if (payment.getAuthorizedAt() == null) {
            return;
        }
        if (payment.getAuthorizedAt().isBefore(now.minus(holdPolicy.usableFor(payment)))
                && alertDue("capture-limit-" + payment.getId(), now, CANCEL_ALERT_EVERY)) {
            log.error("Payment {} is still CAPTURE_PENDING and its hold, authorised at {}, lapses after "
                    + "{} hours: the capture keeps failing and the supplier will not be paid",
                    payment.getId(), payment.getAuthorizedAt(), holdPolicy.holdLimit(payment).toHours());
        }
    }

    /**
     * Doc 46: "client timeout after successful payment → server state recovery".
     *
     * <p>The customer completed checkout and the client died before telling us. No
     * webhook, no confirm call, and an order sitting in DRAFT that the supplier
     * will never see. Asking the provider is the only way to find out, and it is
     * why this job exists rather than trusting callbacks.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.payments.reconcile-interval:PT60S}")
    @SchedulerLock(name = "payment-reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void reconcileStale() {
        var staleBefore = Instant.now().minus(Duration.ofMinutes(2));
        var stale = payments.findStale(staleBefore, PageRequest.of(0, reconcileBatch));

        for (var payment : stale) {
            try (var trace = PaymentTrace.of(payment)) {
                if (payment.getProviderPaymentId() == null && payment.getProviderOrderId() == null) {
                    // No checkout was ever opened: the create call that made this payment failed to reach the provider
                    // (D-136) and nobody retried. There is nothing to ask the provider about, so past a short grace it
                    // ends, and its order, which the supplier never saw, is abandoned.
                    if (payment.getStatus() == PaymentStatus.CREATED
                            && payment.getCreatedAt().isBefore(Instant.now().minus(CHECKOUT_NEVER_OPENED_AFTER))
                            && paymentService.expireIntent(payment.getId())) {
                        orderRelease.abandonUnfunded(payment.getSupplierOrderId(),
                                "Payment was never set up");
                    }
                    continue;
                }
                try {
                    PaymentProvider.ProviderPayment providerPayment;
                    if (payment.getStatus() == PaymentStatus.AUTHORIZED
                            && payment.getReconciledAt() != null
                            && payment.getReconciledAt().isAfter(Instant.now().minus(HELD_CHECK_EVERY))) {
                        // Held on purpose until the order is ready (D-103), for up to
                        // days. Asked about every few hours to catch a lapse, not
                        // every few minutes for the whole hold.
                        continue;
                    }
                    if (payment.getProviderPaymentId() != null) {
                        providerPayment = provider.fetchPayment(payment.getProviderPaymentId());
                    } else {
                        // Only the intent. Either the customer never paid, or they did
                        // and neither the client's confirm nor the webhook reached us
                        // — so ask by the intent. Bounded in age, or every abandoned
                        // checkout would cost a provider call a minute for ever.
                        Instant now = Instant.now();
                        boolean pastWindow = payment.getCreatedAt().isBefore(now.minus(INTENT_LOOKUP_WINDOW));
                        if (!pastWindow
                                && !dueForIntentLookup(payment.getCreatedAt(), payment.getReconciledAt(), now)) {
                            continue;
                        }
                        var found = provider.findPaymentForOrder(payment.getProviderOrderId());
                        if (found.isEmpty()) {
                            if (pastWindow) {
                                // Asked one last time and still nothing: end it, so it
                                // leaves this batch for good (D-101). It used to stay
                                // CREATED, untouched, at the front of every batch.
                                if (paymentService.expireIntent(payment.getId())) {
                                    orderRelease.abandonUnfunded(payment.getSupplierOrderId(),
                                            "Payment never completed");
                                }
                            } else {
                                payments.markAsked(payment.getId(), now);
                            }
                            continue;
                        }
                        providerPayment = found.get();
                    }
                    // Whatever the answer does to the payment, it was asked — so it
                    // moves to the back of the queue even if nothing is applied (a
                    // mismatch, an attempt we ignore), rather than being asked again
                    // on every run ahead of everything else.
                    payments.markAsked(payment.getId(), Instant.now());

                    var updated = paymentService.applyProviderState(
                            payment, providerPayment, "RECONCILE");

                    if (updated.getStatus() == PaymentStatus.AUTHORIZED && updated.getAuthorizedAt() != null
                            && updated.getAuthorizedAt().isBefore(Instant.now().minus(holdPolicy.warnAfter(updated)))) {
                        // Error, for an alert to match: the hold lapses at the provider's
                        // limit (costonomy.mp.razorpay.manual-expiry-minutes) and the order
                        // is still not ready. Past PaymentHoldPolicy's margin the supplier
                        // will be refused at "ready"; somebody has to act before that, with
                        // the restaurant and the supplier.
                        log.error("Payment {} has been held since {} and its order is not dispatched; "
                                + "the hold lapses after {} hours", updated.getId(), updated.getAuthorizedAt(),
                                holdPolicy.holdLimit(updated).toHours());
                    }
                    // The same rule as the confirm call and the webhook (PaymentFollowUp).
                    PaymentFollowUp.apply(orderRelease, updated);

                } catch (PaymentProviderException ex) {
                    // Unreachable providers are normal. The next sweep asks again.
                    log.debug("Could not reconcile payment {}: {}", payment.getId(), ex.getMessage());
                } catch (RuntimeException ex) {
                    log.error("Could not reconcile payment {}", payment.getId(), ex);
                }
            }
        }
    }

    /**
     * Sends a cancelled order's debited money back (D-109): captures it, and raises
     * the refund the refund job then sends. Frequent, because a restaurant whose
     * order was cancelled is out of pocket until this runs; each payment is
     * independent, so one the provider keeps refusing cannot hold up the rest.
     *
     * <p>A run has a time budget ({@link #CANCEL_BUDGET}) and stops early when the provider
     * says it is limiting us or refusing our keys: the next run, fifteen seconds later,
     * carries on.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.payments.cancel-interval:PT15S}")
    @SchedulerLock(name = "payment-cancel", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void settleCancellations() {
        Instant started = Instant.now();
        var pending = payments.findPendingCancellations(PageRequest.of(0, CANCEL_BATCH));
        int handled = 0;
        // Whether the provider can still be asked this run. A stop (budget, rate limit, refused
        // keys) turns it off, but never the reminders about payments waiting for a person: an
        // outage is exactly when nobody must be left believing nothing is waiting (N5).
        boolean mayAsk = true;
        var run = cancellations.newRun();
        for (var payment : pending) {
            if (handled > 0 && budgetSpent(started)) {
                // At least one payment every run, so a budget can never mean no progress.
                log.warn("Cancellation run stopped at its time budget after {} of {} payments; "
                        + "the next run carries on", handled, pending.size());
                mayAsk = false;
                break;
            }
            handled++;
            try (var trace = PaymentTrace.of(payment)) {
                try {
                    var outcome = run.settle(payment.getId());
                    alertIfStuck(payment.getId());
                    if (outcome == CancellationService.Outcome.CONFIGURATION_FAULT) {
                        // Already an ERROR (once per interval) that names the keys, mode and base
                        // URL; nothing was sent to review, and the next run asks again.
                        mayAsk = false;
                        break;
                    }
                    if (outcome == CancellationService.Outcome.BACK_OFF) {
                        // The provider is limiting or refusing us: more calls now would meet the
                        // same answer, and would add to the load that caused it. Once per interval:
                        // the next run, fifteen seconds on, will most likely stop here too.
                        if (alertDue("cancel-backoff", Instant.now(), BACK_OFF_ALERT_EVERY)) {
                            log.warn("Cancellation run stopped after payment {}: the provider is not "
                                    + "accepting calls; the next run tries again", payment.getId());
                        }
                        mayAsk = false;
                        break;
                    }
                } catch (RuntimeException ex) {
                    // One stuck payment must not hold up the rest: this is money owed
                    // back to someone, and the next run tries again.
                    log.error("Could not settle the cancellation of payment {}", payment.getId(), ex);
                }
            }
        }
        // A single payment the provider did not know, held back until the next answer was in.
        try {
            run.finish();
        } catch (RuntimeException ex) {
            log.error("Could not stop an unknown payment for a person", ex);
        }
        reviewCancellations(started, mayAsk && !budgetSpent(started));
    }

    private boolean budgetSpent(Instant started) {
        return Duration.between(started, Instant.now()).compareTo(cancelBudget) >= 0;
    }

    /**
     * Payments stopped for a person are not forgotten (F2). Each is written to the error log
     * once a day for as long as it stays there, and asked about again every few hours,
     * because the provider may return the money on its own (an authorisation that expires)
     * and the row must be able to end without anybody touching the database. A person can
     * also clear the flag (the admin endpoint), which puts it back in the normal run.
     *
     * <p>{@code mayAsk} is false on a run that stopped early: the reminders still go out, the
     * calls to the provider do not.
     */
    private void reviewCancellations(Instant started, boolean mayAsk) {
        var reviewed = payments.findReviewedCancellations(PageRequest.of(0, REVIEW_BATCH));
        Instant now = Instant.now();
        var stillThere = new java.util.HashSet<Long>();
        for (var payment : reviewed) {
            stillThere.add(payment.getId());
            try (var trace = PaymentTrace.of(payment)) {
                try {
                    // The stop itself was an error line; the first reminder is when a re-check
                    // has had its chance and the payment is still here, then once a day.
                    if (payment.getReviewRequiredAt().isBefore(now.minus(REVIEW_RECHECK_EVERY))
                            && alertDue("review-" + payment.getId(), now, REVIEW_ALERT_EVERY)) {
                        log.error("Payment {} has been waiting for a person since {}: {}. Its order was "
                                + "cancelled and the money has not been returned",
                                payment.getId(), payment.getReviewRequiredAt(), payment.getReviewReason());
                    }
                    Instant lastLooked = payment.getReconciledAt() != null
                            && payment.getReconciledAt().isAfter(payment.getReviewRequiredAt())
                            ? payment.getReconciledAt() : payment.getReviewRequiredAt();
                    if (mayAsk && lastLooked.isBefore(now.minus(REVIEW_RECHECK_EVERY)) && !budgetSpent(started)) {
                        if (cancellations.recheckReviewed(payment.getId())
                                == CancellationService.Outcome.BACK_OFF) {
                            break;
                        }
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not re-check the cancellation of payment {}", payment.getId(), ex);
                }
            }
        }
        // A payment that left review (cleared, or ended) starts afresh if it ever returns.
        alerts.forgetIf(key -> key.startsWith("review-")
                && !stillThere.contains(Long.parseLong(key.substring("review-".length()))));
    }

    /**
     * Error, for an alert to match: the money of a cancelled order has not started
     * back after {@link #CANCEL_ALERT_AFTER}, or is still here after the provider
     * should have returned it on its own — which means this job is broken. Each at most
     * once per {@link #CANCEL_ALERT_EVERY} per payment (F7): a run every fifteen seconds
     * would otherwise write it 240 times an hour for as long as the payment waits.
     */
    private void alertIfStuck(Long paymentId) {
        var payment = payments.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.CANCEL_PENDING
                || payment.getReviewRequiredAt() != null) {
            alerts.forget("stuck-" + paymentId);
            alerts.forget("limit-" + paymentId);
            alerts.forget("cancel-lookup-" + paymentId);
            return;
        }
        // When it began waiting: the later of the cancel and the money's arrival. A draft
        // cancelled last week that a payer paid this morning has waited minutes, not days.
        Instant since = payment.getCancelRequestedAt() != null ? payment.getCancelRequestedAt() : payment.getCreatedAt();
        if (payment.getAuthorizedAt() != null && payment.getAuthorizedAt().isAfter(since)) {
            since = payment.getAuthorizedAt();
        }
        Instant now = Instant.now();
        if (overdue(since, now) && alertDue("stuck-" + paymentId, now, CANCEL_ALERT_EVERY)) {
            log.error("Payment {} still CANCEL_PENDING after {} min ({} attempts)", paymentId,
                    Duration.between(since, now).toMinutes(), payment.getCancelAttempts());
        }
        if (payment.getAuthorizedAt() != null
                && payment.getAuthorizedAt().isBefore(now.minus(holdPolicy.holdLimit(payment)))
                && alertDue("limit-" + paymentId, now, CANCEL_ALERT_EVERY)) {
            log.error("Payment {} is still CANCEL_PENDING past the provider's hold limit of {} hours; "
                    + "the provider should have returned it", paymentId, holdPolicy.holdLimit(payment).toHours());
        }
    }

    /** Whether a cancellation begun at {@code since} has been waiting long enough to be an error. */
    static boolean overdue(Instant since, Instant now) {
        return since.isBefore(now.minus(CANCEL_ALERT_AFTER));
    }

    /**
     * Error, for an alert to match: a refund that has been FAILED (or still REQUESTED) for more than an hour, once
     * per refund per hour. FAILED is retried every run, so this is the only line that says a
     * refund owed to someone is not getting through (N4). Measured from when the refund was
     * requested, which is stable across resends (each send moves {@code updated_at}); a refund
     * is sent within a minute of being requested, so an old FAILED one has been failing since.
     */
    private void alertFailedRefunds(java.util.List<com.costonomy.mp.payment.domain.Refund> pending) {
        Instant now = Instant.now();
        var failed = new java.util.HashSet<String>();
        for (var refund : pending) {
            // REQUESTED as well as FAILED: a refund is sent within a minute of being requested, so
            // one still REQUESTED after an hour has not been sent because the run keeps stopping
            // before it (another refund is refused, or the provider is limiting us) and it has no
            // error of its own to say so.
            // PROCESSING too, but only one in the list: claimed and never finished, no provider id.
            if (refund.getStatus() != RefundStatus.FAILED && refund.getStatus() != RefundStatus.REQUESTED
                    && refund.getStatus() != RefundStatus.PROCESSING) {
                continue;
            }
            String key = "refund-failed-" + refund.getId();
            failed.add(key);
            if (refund.getCreatedAt() != null
                    && refund.getCreatedAt().isBefore(now.minus(REFUND_FAILED_ALERT_AFTER))
                    && alertDue(key, now, REFUND_FAILED_ALERT_EVERY)) {
                log.error("Refund {} of payment {} has been {} since it was requested at {} ({} attempts, "
                        + "last error {}); money owed to the payer is not getting back", refund.getId(),
                        refund.getPaymentId(), refund.getStatus(), refund.getCreatedAt(), refund.getAttempts(),
                        refund.getFailureCode());
            }
        }
        // One that ended (or moved on) starts afresh if it ever fails again.
        alerts.forgetIf(key -> key.startsWith("refund-failed-") && !failed.contains(key));
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.payments.refund-interval:PT30S}")
    @SchedulerLock(name = "payment-refund", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void processRefunds() {
        var pending = new java.util.ArrayList<>(refunds.findByStatusInOrderByUpdatedAtAscIdAsc(
                java.util.List.of(RefundStatus.REQUESTED, RefundStatus.FAILED)));
        // Claimed and never finished: the process died between claiming and
        // writing the outcome. Safe to resend — the provider key is per refund.
        pending.addAll(refunds.findByStatusAndProviderRefundIdIsNullAndUpdatedAtBefore(
                RefundStatus.PROCESSING, Instant.now().minus(RefundService.STUCK_AFTER)));
        // One list, oldest attempt first: a stuck refund appended after the others would never be
        // reached while one refused refund stops the run ahead of it, and would never rotate.
        pending.sort(java.util.Comparator
                .comparing(com.costonomy.mp.payment.domain.Refund::getUpdatedAt,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparing(com.costonomy.mp.payment.domain.Refund::getId));

        // Before any send, so a provider that is limiting us or refusing our keys cannot
        // silence it: a refund that has been failing for an hour is money owed back that a
        // person must know about, and each send below that fails only writes a WARN (N4).
        alertFailedRefunds(pending);

        // Payments the provider said it does not know, since the last refund it answered about. Two different
        // ones in a row is a configuration fault, not two dead payments: nothing more is sent this run (D-110).
        var unknownPayments = new java.util.LinkedHashSet<Long>();
        boolean backedOff = false;
        for (var refund : pending) {
            try (var trace = PaymentTrace.of(refund)) {
                try {
                    var outcome = refundService.process(refund.getId());
                    if (outcome == RefundService.Outcome.BACK_OFF) {
                        // Rate limited or keys refused: every other refund would meet the same answer
                        // and add to the load. Stop, as the cancellation job does; the next run resends.
                        if (alertDue("refund-backoff", Instant.now(), BACK_OFF_ALERT_EVERY)) {
                            log.warn("Refund run stopped after refund {}: the provider is not accepting "
                                    + "calls; the next run tries again", refund.getId());
                        }
                        backedOff = true;
                        break;
                    }
                    if (outcome == RefundService.Outcome.UNKNOWN_PAYMENT) {
                        unknownPayments.add(refund.getPaymentId());
                        if (unknownPayments.size() >= 2) {
                            if (alertDue("refund-unknown-config", Instant.now(), BACK_OFF_ALERT_EVERY)) {
                                log.error("Configuration fault: the provider does not know payments {} in a row "
                                        + "when sending refunds: check the API keys, mode and base URL. Nothing more "
                                        + "is sent, blocked or put back", unknownPayments);
                            }
                            backedOff = true;
                            break;
                        }
                    } else {
                        unknownPayments.clear();
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not process refund {}", refund.getId(), ex);
                }
            }
        }

        // Accepted by the provider but not finished there: ask, never resend. Not while the provider is
        // limiting our calls or refusing our keys: every one of these would meet the same answer.
        if (!backedOff) {
            for (var refund : refunds.findByStatusAndProviderRefundIdIsNotNullAndUpdatedAtBefore(
                    RefundStatus.PROCESSING, Instant.now().minus(Duration.ofMinutes(2)))) {
                try (var trace = PaymentTrace.of(refund)) {
                    try {
                        refundService.settlePending(refund.getId());
                    } catch (RuntimeException ex) {
                        log.error("Could not settle refund {}", refund.getId(), ex);
                    }
                }
            }
        }

        // Refused for good by the provider (D-110): its refunds are read, and only then is anything decided
        // about the money. Last, so a refusal made by a send or a pending check above is settled in this
        // same run, and every run, so a process that died between the refusal and the credit finishes it.
        settleRejected();
    }

    /** A refund REJECTED for longer than this is an error for someone to see (D-110). */
    static final Duration REJECTED_ALERT_AFTER = Duration.ofMinutes(30);
    static final Duration REJECTED_ALERT_EVERY = Duration.ofHours(1);

    /** Refunds whose ambiguous send may have gone through are read again this often, for this long (D-110). */
    static final Duration AMBIGUOUS_RECHECK_EVERY = Duration.ofHours(1);
    static final Duration AMBIGUOUS_RECHECK_FOR = Duration.ofDays(7);

    /**
     * A reversed refund is watched for a late success on the schedule in {@link WithdrawalReversalService#AUDIT_SCHEDULE},
     * which ends at fourteen days; a refund older than this, with its last check, is left alone (D-110).
     */
    static final Duration REVERSED_AUDIT_FOR = Duration.ofDays(14).plusHours(12);

    /** How many reversed refunds the audit reads per page. A field, so a test can make it small. */
    int auditPageSize = 200;

    /** How many rejected refunds one run reads. A field, so a test can make it small. */
    int settlePageSize = 100;

    /**
     * Read the provider's refunds for each REJECTED refund and act on what they show: adopt ours, put a
     * withdrawal part back, or leave it for a person (D-110). Idempotent, so it is safe every run.
     *
     * <p>Refunds whose payment the provider does not know are held back until it is clear whether that is the
     * payment or our keys. The whole page is read first, and the answer comes from the rest of it: as soon as
     * the provider answers normally about any other refund, the keys are the right ones and what it did not
     * know is a payment that is gone (sent to a person, once). Only if <em>nothing</em> on the page was answered
     * and two different payments were unknown is it a configuration fault, and then nothing is decided. Those
     * refunds are then moved behind the others in the queue, so that a few payments the provider cannot place
     * cannot sit at its head for ever and starve every refund behind them.
     */
    void settleRejected() {
        Instant now = Instant.now();
        var held = new java.util.LinkedHashMap<Long, java.util.List<Long>>();
        boolean keysWork = false;
        for (var refund : refunds.rejectedToSettle(org.springframework.data.domain.PageRequest.of(0, settlePageSize))) {
            try (var trace = PaymentTrace.of(refund)) {
                try {
                    var result = reversals.verifyAndReverse(refund.getId());
                    if (result == WithdrawalReversalService.Result.UNKNOWN_AT_PROVIDER) {
                        if (keysWork) {
                            reversals.reviewUnknown(refund.getId());
                        } else {
                            held.computeIfAbsent(refund.getPaymentId(), k -> new java.util.ArrayList<>()).add(refund.getId());
                        }
                        continue;
                    }
                    if (result != WithdrawalReversalService.Result.WAITING
                            && result != WithdrawalReversalService.Result.NOT_APPLICABLE) {
                        // The provider answered about a payment: the keys are the right ones, and what it does not
                        // know is not known.
                        keysWork = true;
                        held.values().forEach(ids -> ids.forEach(reversals::reviewUnknown));
                        held.clear();
                    }
                } catch (RuntimeException ex) {
                    log.error("Could not verify rejected refund {}", refund.getId(), ex);
                }
            }
        }
        if (held.size() >= 2) {
            if (alertDue("reversal-unknown-config", now, BACK_OFF_ALERT_EVERY)) {
                log.error("Configuration fault: the provider does not know payments {} when reading "
                        + "their refunds, and answered about no other: check the API keys, mode and base URL. "
                        + "Nothing is put back, blocked or sent", held.keySet());
            }
            refunds.markHeld(held.values().stream().flatMap(java.util.List::stream).toList(), now);
        } else {
            // One payment unknown to the provider and nothing else said otherwise: a payment that is gone.
            held.values().forEach(ids -> ids.forEach(reversals::reviewUnknown));
        }
        alertLongRejected(now);
    }

    /**
     * Every refund still REJECTED after {@link #REJECTED_ALERT_AFTER} is an error line for someone to see, whatever
     * it was read as this run, and however far down the queue it is: a refund the run never got to, or one held
     * back, must not be the one nobody hears about (D-110).
     */
    private void alertLongRejected(Instant now) {
        long afterId = 0;
        while (true) {
            var page = refunds.rejectedSince(now.minus(REJECTED_ALERT_AFTER), afterId,
                    org.springframework.data.domain.PageRequest.of(0, 200));
            for (var refund : page) {
                afterId = refund.getId();
                if (alertDue("refund-rejected-" + refund.getId(), now, REJECTED_ALERT_EVERY)) {
                    log.error("Refund {} REJECTED for {} min and not yet verified with the provider; money owed "
                            + "back is waiting", refund.getId(),
                            Duration.between(refund.getUpdatedAt(), now).toMinutes());
                }
            }
            if (page.size() < 200) {
                break;
            }
        }
    }

    /**
     * A refund that went to a person after an ambiguous send is read again, hourly for a week: the
     * refund may well exist at the provider, and finding it is what closes the case (D-110).
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.payments.refund-audit-interval:PT10M}")
    @SchedulerLock(name = "payment-refund-audit", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void reconcileAmbiguousRefunds() {
        Instant now = Instant.now();
        for (var refund : refunds.ambiguousToReconcile(now.minus(AMBIGUOUS_RECHECK_FOR),
                now.minus(AMBIGUOUS_RECHECK_EVERY), org.springframework.data.domain.PageRequest.of(0, 100))) {
            try (var trace = PaymentTrace.of(refund)) {
                try {
                    // Found: adopted. Not found: noted, and checked again in an hour.
                    reversals.run(refund.getId(), null, WithdrawalReversalService.Intent.VERIFY);
                } catch (RuntimeException ex) {
                    log.warn("Could not reconcile refund {} with the provider: {}", refund.getId(), ex.getMessage());
                }
            }
        }
    }

    /**
     * A refund put back in a wallet is looked for at the provider ten minutes, an hour and six hours after, and
     * then daily for fourteen days (D-110). Finding it means the restaurant was credited twice: withdrawals of its
     * outlet are paused until a person resolves it. Runs every minute so the first look is not a whole interval
     * late; which refunds are due is worked out from the schedule and two persisted times, never from memory.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.payments.reversal-audit-interval:PT1M}")
    @SchedulerLock(name = "payment-reversal-audit", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void auditReversedRefunds() {
        Instant now = Instant.now();
        long afterId = 0;
        boolean backedOff = false;
        while (!backedOff) {
            var page = refunds.reversedToAudit(now.minus(REVERSED_AUDIT_FOR), afterId,
                    org.springframework.data.domain.PageRequest.of(0, auditPageSize));
            for (var refund : page) {
                afterId = refund.getId();
                if (!WithdrawalReversalService.auditDue(refund.getReversedAt(), refund.getVerifiedAt(),
                        refund.getVerifiedResult(), now)) {
                    continue;
                }
                try (var trace = PaymentTrace.of(refund)) {
                    try {
                        reversals.auditReversed(refund.getId());
                    } catch (com.costonomy.mp.payment.provider.PaymentProviderException ex) {
                        // Rate limited or keys refused: every other refund due now would meet the same answer, and the
                        // capture job shares the limit. Stopped, as the other jobs are; still due, so the next run asks.
                        if (alertDue("reversal-audit-backoff", now, BACK_OFF_ALERT_EVERY)) {
                            log.warn("Reversal audit stopped at refund {}: the provider is not accepting calls; "
                                    + "the next run tries again", refund.getId());
                        }
                        backedOff = true;
                        break;
                    } catch (RuntimeException ex) {
                        log.error("Could not audit reversed refund {}", refund.getId(), ex);
                    }
                }
            }
            if (page.size() < auditPageSize) {
                break;
            }
        }
        // A double credit that nobody has resolved is a standing condition: a reminder every six hours.
        for (var refund : refunds.lateSuccessOpen(org.springframework.data.domain.PageRequest.of(0, 50))) {
            if (alertDue("late-success-" + refund.getId(), now, Duration.ofHours(6))) {
                log.error("CRITICAL refund {} was put back in a wallet and was also sent by the provider (since {}); "
                        + "withdrawals of the outlet stay paused until a person resolves it", refund.getId(),
                        refund.getLateSuccessAt());
            }
        }
    }
}
