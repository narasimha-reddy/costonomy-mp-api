package com.costonomy.mp.quickscan.service;

import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.quickscan.repository.QuickScanPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Background QuickScan work. D-106.
 *
 * <p>The safety net for what {@code QuickScanService.payFromWallet}'s
 * synchronous call did not settle: a payout the provider accepted as PENDING,
 * a transient failure worth retrying, or a process that died mid-call. Only
 * runs while the feature is on — with it off, no payment can be in
 * PAYOUT_PENDING in the first place, and there is nothing for the job to do.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuickScanJobs {

    private final QuickScanPaymentRepository payments;
    private final QuickScanService quickScan;

    @Value("${costonomy.mp.quickscan.enabled:false}")
    private boolean enabled;

    /** Per run, so one backlog cannot hold the scheduler for minutes. */
    static final int BATCH = 100;

    @Scheduled(fixedDelayString = "${costonomy.mp.quickscan.payout-interval:PT10S}")
    @SchedulerLock(name = "quickscan-payouts", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void run() {
        if (!enabled) {
            return;
        }

        var now = Instant.now();

        var claimable = payments.findClaimable(
                now.minus(QuickScanService.JOB_CLAIM_DELAY), now.minus(QuickScanService.STUCK_AFTER),
                PageRequest.of(0, BATCH));
        for (var payment : claimable) {
            try (var trace = TraceScope.of("quickscan", payment.getId())) {
                try {
                    quickScan.sendPayout(payment.getId());
                } catch (RuntimeException ex) {
                    // One bad row must not stop the batch — the rest may well be
                    // fine, and a poison row would otherwise leave every payout
                    // behind it unprocessed for ever.
                    log.error("Could not send QuickScan payout {}", payment.getId(), ex);
                }
            }
        }

        var settleable = payments.findSettleable(
                now.minus(QuickScanService.PENDING_CHECK_EVERY), now.minus(QuickScanService.PAID_WATCH_FOR),
                now.minus(QuickScanService.PAID_CHECK_EVERY), PageRequest.of(0, BATCH));
        for (var payment : settleable) {
            try (var trace = TraceScope.of("quickscan", payment.getId())) {
                try {
                    quickScan.settlePending(payment.getId());
                } catch (RuntimeException ex) {
                    log.error("Could not settle QuickScan payment {}", payment.getId(), ex);
                }
            }
        }
    }
}
