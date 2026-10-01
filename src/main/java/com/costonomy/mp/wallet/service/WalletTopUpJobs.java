package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.logging.TraceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background wallet top-up work (D-107).
 *
 * <p>Closes the two gaps the client's confirm call leaves. A restaurant that paid
 * and lost its connection never calls confirm, and the money is captured at
 * Razorpay with no credit behind it: {@link #poll} finds it by the Razorpay order
 * and credits it through the same method confirm uses, so the two cannot both
 * credit. A payment that could not be credited because of a wallet limit was
 * returned by a refund that may not have landed yet: {@link #refunds} keeps
 * trying.
 *
 * <p>Both are idempotent and locked, so a second instance, or a run overlapping a
 * confirm, changes nothing twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletTopUpJobs {

    /** Per run, so one backlog cannot hold the scheduler for minutes. */
    static final int BATCH = 100;

    private final WalletTopUpService topUps;

    @Scheduled(fixedDelayString = "${costonomy.mp.wallet.top-up-poll-interval:PT30S}")
    @SchedulerLock(name = "wallet-top-up-poll", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void poll() {
        for (var topUp : topUps.dueForPolling(BATCH)) {
            // The scope wraps the catch too: an error line without the top-up's
            // ids is the one you most need to find (D-100).
            try (var trace = TraceScope.of("wallet_top_up", topUp.getId(), "outlet", topUp.getOutletId(),
                    "rzp_order", topUp.getRazorpayOrderId())) {
                try {
                    topUps.reconcile(topUp.getId());
                } catch (RuntimeException ex) {
                    // One bad row must not stop the batch: a poison row would leave
                    // every paid top-up behind it uncredited.
                    log.error("Could not reconcile wallet top-up {}", topUp.getId(), ex);
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.wallet.top-up-refund-interval:PT30S}")
    @SchedulerLock(name = "wallet-top-up-refund", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void refunds() {
        for (var topUp : topUps.refundsDue(BATCH)) {
            try (var trace = TraceScope.of("wallet_top_up", topUp.getId(), "outlet", topUp.getOutletId(),
                    "rzp_order", topUp.getRazorpayOrderId(), "rzp_payment", topUp.getRazorpayPaymentId())) {
                try {
                    topUps.sendRefund(topUp.getId());
                } catch (RuntimeException ex) {
                    log.error("Could not refund wallet top-up {}", topUp.getId(), ex);
                }
            }
        }
    }
}
