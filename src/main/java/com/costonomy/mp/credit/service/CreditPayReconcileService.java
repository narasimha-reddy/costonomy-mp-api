package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.credit.domain.CreditPaymentAttempt;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptRepository;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The safety net for a restaurant that paid and never came back (D-196): asks Razorpay about waiting attempts and
 * applies a payment found, through the same settlement verify and the webhook use, so the three cannot both apply.
 *
 * <p>Also where an unpaid attempt ends. It expires after {@code expire-after}, but an expired attempt is still watched
 * for {@code late-poll-for}, because a payment whose webhook was lost is applied, not refunded (D-196, open question
 * 10). Provider trouble never throws: the next sweep asks again. Not gated by the online-pay flag.
 */
@Service
@Slf4j
public class CreditPayReconcileService {

    private final CreditPaymentAttemptRepository attempts;
    private final PaymentProvider provider;
    private final CreditPaySettlement settlement;
    private final TransactionTemplate tx;

    private final Duration pollAfter;
    private final Duration recheck;
    private final Duration expireAfter;
    private final Duration latePollFor;
    private final Duration lateRecheck;
    private final Duration neverOpenedAfter;
    private final Duration stuckAlertAfter;

    public CreditPayReconcileService(
            CreditPaymentAttemptRepository attempts, PaymentProvider provider, CreditPaySettlement settlement,
            PlatformTransactionManager transactionManager,
            @Value("${costonomy.mp.credit.online-pay.poll-after:PT5M}") Duration pollAfter,
            @Value("${costonomy.mp.credit.online-pay.recheck:PT1M}") Duration recheck,
            @Value("${costonomy.mp.credit.online-pay.expire-after:PT30M}") Duration expireAfter,
            @Value("${costonomy.mp.credit.online-pay.late-poll-for:PT24H}") Duration latePollFor,
            @Value("${costonomy.mp.credit.online-pay.late-recheck:PT30M}") Duration lateRecheck,
            @Value("${costonomy.mp.credit.online-pay.never-opened-after:PT5M}") Duration neverOpenedAfter,
            @Value("${costonomy.mp.credit.online-pay.stuck-alert-after:PT15M}") Duration stuckAlertAfter) {
        this.attempts = attempts;
        this.provider = provider;
        this.settlement = settlement;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.pollAfter = pollAfter;
        this.recheck = recheck;
        this.expireAfter = expireAfter;
        this.latePollFor = latePollFor;
        this.lateRecheck = lateRecheck;
        this.neverOpenedAfter = neverOpenedAfter;
        this.stuckAlertAfter = stuckAlertAfter;
    }

    /** An unpaid attempt past its window ends; only a CREATED one can (a paid or refunded one never does). */
    public static boolean shouldExpire(CreditPaymentAttemptStatus status, Duration age, Duration expireAfter) {
        return status == CreditPaymentAttemptStatus.CREATED && age.compareTo(expireAfter) > 0;
    }

    /** The one-time "captured but not applied" alert: only once, and only after the grace period. */
    public static boolean shouldAlertStuck(Instant stuckAlertedAt, Duration age, Duration stuckAlertAfter) {
        return stuckAlertedAt == null && age.compareTo(stuckAlertAfter) > 0;
    }

    /** One attempt. Never throws for provider trouble; a settlement failure is logged, and alerted once if stuck. */
    public void reconcile(Long attemptId) {
        var attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || attempt.getRazorpayOrderId() == null
                || (attempt.getStatus() != CreditPaymentAttemptStatus.CREATED
                && attempt.getStatus() != CreditPaymentAttemptStatus.EXPIRED)) {
            return;
        }
        var now = Instant.now();
        var age = Duration.between(attempt.getCreatedAt(), now);

        try (var trace = TraceScope.of("credit_payment_attempt", attemptId, "outlet", attempt.getOutletId(),
                "rzp_order", attempt.getRazorpayOrderId())) {
            java.util.Optional<PaymentProvider.ProviderPayment> found;
            try {
                found = provider.findPaymentForOrder(attempt.getRazorpayOrderId());
            } catch (PaymentProviderException ex) {
                // Not marked as asked, so it is not pushed to the back of the queue.
                log.warn("Could not ask Razorpay about credit payment attempt {}: {}", attemptId, ex.getMessage());
                return;
            }
            tx.executeWithoutResult(status -> attempts.markChecked(attemptId, Instant.now()));

            if (found.isEmpty()) {
                expireIfDue(attempt, age);
                return;
            }

            var payment = found.get();
            CreditPaySettlement.Outcome outcome = null;
            try {
                outcome = settlement.settle(attemptId, payment, "POLLER");
                log.info("Credit payment attempt {} polled: payment {} → {}",
                        attemptId, payment.providerPaymentId(), outcome);
            } catch (RuntimeException ex) {
                log.error("Could not apply credit payment {} for attempt {}", payment.providerPaymentId(),
                        attemptId, ex);
            }

            boolean captured = payment.status() == PaymentProvider.ProviderPaymentStatus.CAPTURED;
            if (captured && (outcome == null || outcome == CreditPaySettlement.Outcome.REJECTED)) {
                alertIfStuck(attempt, payment, age);
            } else if (outcome == CreditPaySettlement.Outcome.DECLINED
                    || outcome == CreditPaySettlement.Outcome.PROCESSING) {
                // Found but not paid (declined, or never captured): the same end as no payment at all. A capture that
                // turns up later is still applied, because EXPIRED attempts are watched for the late window.
                expireIfDue(attempt, age);
            }
        }
    }

    private void expireIfDue(CreditPaymentAttempt attempt, Duration age) {
        if (shouldExpire(attempt.getStatus(), age, expireAfter)
                && tx.execute(status -> attempts.endFromCreated(attempt.getId(),
                CreditPaymentAttemptStatus.EXPIRED, "Not paid within " + minutes(expireAfter) + " minutes",
                Instant.now())) == 1) {
            log.info("Credit payment attempt {} EXPIRED: no payment for order {} after {} minutes",
                    attempt.getId(), attempt.getRazorpayOrderId(), minutes(expireAfter));
        }
    }

    private void alertIfStuck(CreditPaymentAttempt attempt, PaymentProvider.ProviderPayment payment, Duration age) {
        if (shouldAlertStuck(attempt.getStuckAlertedAt(), age, stuckAlertAfter)
                && tx.execute(status -> attempts.markStuckAlerted(attempt.getId(), Instant.now())) == 1) {
            log.error("CREDIT_PAY_STUCK attempt {} payment {} captured but not applied: needs a person",
                    attempt.getId(), payment.providerPaymentId());
        }
    }

    /** The attempts due to be asked about, oldest first. */
    public List<CreditPaymentAttempt> dueForPolling(int batch) {
        var now = Instant.now();
        return attempts.findDueForPolling(now.minus(pollAfter), now.minus(recheck), now.minus(latePollFor),
                now.minus(lateRecheck), PageRequest.of(0, batch));
    }

    /** CREATED rows that never got a Razorpay order cannot be paid: they end FAILED. */
    public void closeNeverOpened(int batch) {
        var rows = attempts.findNeverOpened(Instant.now().minus(neverOpenedAfter), PageRequest.of(0, batch));
        for (var attempt : rows) {
            try (var trace = TraceScope.of("credit_payment_attempt", attempt.getId(), "outlet",
                    attempt.getOutletId())) {
                if (tx.execute(status -> attempts.endFromCreated(attempt.getId(), CreditPaymentAttemptStatus.FAILED,
                        "Never reached Razorpay", Instant.now())) == 1) {
                    log.warn("Credit payment attempt {} FAILED: it never reached Razorpay, so nothing was payable",
                            attempt.getId());
                }
            } catch (RuntimeException ex) {
                log.error("Could not close never-opened credit payment attempt {}", attempt.getId(), ex);
            }
        }
    }

    private static long minutes(Duration d) {
        return d.toMinutes();
    }
}
