package com.costonomy.mp.procurement.service;

import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Expires supplier orders nobody answered. Doc 13, doc 38.
 *
 * <p>Runs every few seconds because the SLA is measured in them: a 60-second
 * window expired two minutes late is a restaurant left waiting on an order the
 * supplier already abandoned.
 *
 * <p>Three properties worth keeping.
 *
 * <p><b>It is not the authority on expiry.</b> {@code SupplierOrderTransitions}
 * refuses an acceptance past the deadline regardless of whether this job has run,
 * so "a supplier cannot accept an expired order" (doc 13) is true at every
 * instant. This job is housekeeping: it moves the state so the restaurant is told
 * and can source elsewhere.
 *
 * <p><b>It expires orders one at a time, each in its own transaction.</b> A batch
 * transaction would mean one supplier accepting mid-sweep rolls back the expiry of
 * every other order in the batch. Per-order also means a lost race affects only
 * that order.
 *
 * <p><b>Losing a race is a normal outcome, not an error.</b> The supplier accepted
 * in the same instant, which is exactly the case doc 10 §2 requires to resolve to
 * one winner. It is logged at debug and skipped.
 *
 * <p>{@code @SchedulerLock} so two instances do not both sweep. Without it they
 * would race each other as well as the suppliers, and while optimistic locking
 * would still leave one winner, the wasted work and log noise would be constant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupplierOrderTimeoutJob {

    private final SupplierOrderRepository orders;
    private final SupplierOrderTransitions transitions;

    @Scheduled(fixedDelayString = "${costonomy.mp.orders.timeout-poll-interval:PT5S}")
    // lockAtLeastFor is zero deliberately. It exists to stop clock skew causing
    // two instances to run in quick succession — but this sweep is idempotent
    // (expiring an already-expired order is a no-op), so a double run costs
    // nothing, while holding the lock for a second after each run means a sweep
    // requested inside that second is silently skipped. For a job whose whole
    // purpose is to act promptly on a 60-second SLA, being skipped is the worse
    // failure.
    @SchedulerLock(name = "supplier-order-timeout", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
    public void expireOverdueOrders() {
        var candidates = orders.findExpiredCandidates(Instant.now());
        if (candidates.isEmpty()) {
            return;
        }

        int expired = 0;
        int lost = 0;

        for (var candidate : candidates) {
            try {
                if (transitions.expire(candidate.getId())) {
                    expired++;
                } else {
                    // Answered between selection and this call. Ordinary.
                    lost++;
                }
            } catch (OptimisticLockingFailureException ex) {
                // The supplier's acceptance committed first. The race resolved to
                // the supplier, which is a correct outcome — doc 10 §2 asks only
                // that exactly one side wins, not which.
                log.debug("Supplier order {} was answered while expiring", candidate.getId());
                lost++;
            } catch (RuntimeException ex) {
                // One bad order must not stop the sweep, or a single poison row
                // would leave every later order pending indefinitely.
                log.error("Could not expire supplier order {}", candidate.getId(), ex);
            }
        }

        if (expired > 0 || lost > 0) {
            log.info("Supplier order timeout sweep: {} expired, {} answered first", expired, lost);
        }
    }
}
