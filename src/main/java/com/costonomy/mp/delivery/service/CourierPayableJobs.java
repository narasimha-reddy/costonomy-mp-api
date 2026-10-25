package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The timers of courier payment (D-204). Each pass reads the database, never a timer in memory, so a restart strands
 * nothing. Not transactional: every payable is its own set of short transactions inside {@link CourierPaymentService},
 * with no lock held across the rail, and one failing payable never stops the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourierPayableJobs {

    private final CourierPayableSyncService syncService;
    private final CourierPayableRepositoryAccess access;
    private final CourierPaymentService payments;
    private final CourierPayableProperties props;

    /** Counts of what one pass did, for the tests and the log. */
    public record Pass(int synced, int leasesRecovered, int reconciled, int paymentsTried) {
    }

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.courier-payable.poll:PT1M}",
            initialDelayString = "${costonomy.mp.delivery.courier-payable.initial-delay:PT0S}")
    @SchedulerLock(name = "courier-payable-run", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void run() {
        runOnce();
    }

    /** Counts what the sync cannot see (bookings with no payable) and raises the alert (D-206). */
    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.courier-payable.audit-interval:PT15M}",
            initialDelayString = "${costonomy.mp.delivery.courier-payable.initial-delay:PT0S}")
    @SchedulerLock(name = "courier-payable-audit", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void audit() {
        if (props.isJobsEnabled()) {
            syncService.audit();
        }
    }

    /** One pass; the scheduled method and the tests both call it. */
    public Pass runOnce() {
        if (!props.isJobsEnabled()) {
            return new Pass(0, 0, 0, 0);
        }
        Instant now = Instant.now();
        int synced = syncService.run().visited();
        int leases = 0;
        for (Long id : access.expiredLeases(now, props.getBatch())) {
            try {
                if (payments.recoverExpiredLease(id)) {
                    leases++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not recover the lease on courier payable {}", id, ex);
            }
        }
        int reconciled = 0;
        for (Long id : access.ambiguous(now, props.getBatch())) {
            try {
                payments.reconcileAmbiguous(id);
                reconciled++;
            } catch (RuntimeException ex) {
                log.error("Could not reconcile courier payable {}", id, ex);
            }
        }
        int tried = 0;
        for (Long id : access.readyToPay(now, props.getBatch())) {
            try {
                if (payments.pay(id) != CourierPaymentService.Result.NOT_CLAIMED) {
                    tried++;
                }
            } catch (RuntimeException ex) {
                log.error("Paying courier payable {} failed", id, ex);
            }
        }
        if (synced + leases + reconciled + tried > 0) {
            log.info("Courier payables: {} synced, {} leases recovered, {} reconciled, {} payments tried",
                    synced, leases, reconciled, tried);
        }
        return new Pass(synced, leases, reconciled, tried);
    }
}
