package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.logging.TraceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background work for credit payments (D-196): finding payments whose screen closed, ending the unpaid, and returning
 * money that was captured but not applied.
 *
 * <p><b>Two independent ShedLock names</b>, and neither job ever holds the other's lock or calls Razorpay inside a
 * database transaction. All capture paths (verify, webhook, this poll) meet only in
 * {@link CreditPaySettlement}, which serialises on the attempt row and then takes invoices and agreements in ascending
 * id order; expiry is a conditional update on the same row and the settlement accepts an expired attempt, so either
 * order is safe. The refund job touches only refund rows by conditional updates, and a refund sent twice is one refund
 * at Razorpay because its key is derived from the row id. No ShedLock around webhook handling, ever.
 *
 * <p>Not gated by the online-pay flag: money already in flight still lands, and refunds still go, when it is off.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreditPayJobs {

    /** Per run, so one backlog cannot hold the scheduler for minutes. */
    static final int BATCH = 100;

    private final CreditPayReconcileService reconcile;
    private final CreditPayRefundService refundService;

    @Scheduled(fixedDelayString = "${costonomy.mp.credit.online-pay.poll-interval:PT30S}")
    @SchedulerLock(name = "credit-pay-reconcile", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void poll() {
        reconcile.closeNeverOpened(BATCH);
        for (var attempt : reconcile.dueForPolling(BATCH)) {
            // The scope wraps the catch too: an error line without the attempt's ids is the one you most need (D-100).
            try (var trace = TraceScope.of("credit_payment_attempt", attempt.getId(), "outlet", attempt.getOutletId(),
                    "rzp_order", attempt.getRazorpayOrderId())) {
                try {
                    reconcile.reconcile(attempt.getId());
                } catch (RuntimeException ex) {
                    // One bad row must not stop the batch behind it.
                    log.error("Could not reconcile credit payment attempt {}", attempt.getId(), ex);
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.credit.online-pay.refund-interval:PT30S}")
    @SchedulerLock(name = "credit-pay-refund", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void refunds() {
        for (var refund : refundService.due(BATCH)) {
            try (var trace = TraceScope.of("credit_payment_attempt", refund.getAttemptId(), "credit_payment_refund",
                    refund.getId(), "rzp_payment", refund.getRazorpayPaymentId())) {
                try {
                    refundService.send(refund.getId());
                } catch (RuntimeException ex) {
                    log.error("Could not refund credit payment refund {}", refund.getId(), ex);
                }
            }
        }
    }
}
