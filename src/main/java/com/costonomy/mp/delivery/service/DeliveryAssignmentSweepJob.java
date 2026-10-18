package com.costonomy.mp.delivery.service;

import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.repository.DeliveryProviderAttemptRepository;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finds bookings that never got a driver and hands them to the waterfall (doc 06 §14).
 *
 * <p>{@link DeliveryWaterfallService} schedules a one-shot timer for each booking, in memory. A restart or deploy
 * between the booking and its deadline loses that timer, and nothing else would ever look at the delivery again: it
 * sat in {@code PROVIDER_SELECTED} with no rider and no fallback. This sweep is the durable half. It reads the
 * deadline from the database, so it finds those deliveries whenever the app is next up.
 *
 * <p>It waits a short grace past the deadline, so on a live instance the timer fires first and the sweep finds
 * nothing to do. {@link DeliveryWaterfallService#cascadeUnassigned} re-checks the status and the deadline itself, so
 * a delivery that got a driver, or was already moved on, is left alone.
 *
 * <p>Not transactional, like the waterfall: each cascade runs its own transactions, and one failing delivery never
 * stops the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryAssignmentSweepJob {

    private final DeliveryRepository deliveries;
    private final DeliveryProviderAttemptRepository attempts;
    private final DeliveryBookingService bookingService;
    private final DeliveryWaterfallService waterfall;
    private final DeliveryService deliveryService;

    @Value("${costonomy.mp.delivery.assignment-sweep.enabled:true}")
    private boolean enabled = true;

    @Value("${costonomy.mp.delivery.assignment-sweep.grace:PT30S}")
    private Duration grace = Duration.ofSeconds(30);

    @Value("${costonomy.mp.delivery.assignment-sweep.batch:50}")
    private int batch = 50;

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.assignment-sweep.interval:PT30S}")
    @SchedulerLock(name = "delivery-assignment-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweep() {
        runOnce(Instant.now());
    }

    /** One sweep at the given instant; the scheduled method and the tests both call it. */
    public int runOnce(Instant now) {
        if (!enabled) {
            return 0;
        }
        var overdue = deliveries.findByStatusAndAssignmentDeadlineBefore(
                DeliveryStatus.PROVIDER_SELECTED, now.minus(grace));
        int moved = 0;
        for (var delivery : overdue.stream().limit(batch).toList()) {
            try {
                if (waterfall.cascadeUnassigned(delivery.getId())) {
                    moved++;
                }
            } catch (RuntimeException ex) {
                log.error("Assignment sweep could not cascade delivery {}", delivery.getId(), ex);
            }
        }
        if (!overdue.isEmpty()) {
            log.info("Assignment sweep: {} overdue, {} moved to another courier", overdue.size(), moved);
        }
        return moved + rescueStoodDown(now) + rescueStalePendingAttempts(now);
    }

    /**
     * Deliveries a provider let go of that nobody picked up: the event listener failed or the app stopped between the
     * commit and the reassign. The delivery is still in the stand-down status, with no deadline, and no other job
     * looks at it. The same call as the listener, so it checks everything again under the lock.
     */
    private int rescueStoodDown(Instant now) {
        var cutoff = now.minus(grace);
        int rescued = 0;
        var stuck = deliveries.findByStatusIn(List.of(DeliveryStatus.DRIVER_CANCELLED, DeliveryStatus.PICKUP_FAILED))
                .stream()
                .filter(d -> d.getUpdatedAt() != null && d.getUpdatedAt().isBefore(cutoff))
                .limit(batch)
                .toList();
        for (var delivery : stuck) {
            try {
                if (deliveryService.reassignAfterProviderFailure(delivery.getId(), null, "Reassigned by sweep")) {
                    rescued++;
                }
            } catch (RuntimeException ex) {
                log.error("Assignment sweep could not reassign delivery {}", delivery.getId(), ex);
            }
        }
        if (!stuck.isEmpty()) {
            log.info("Assignment sweep: {} stood down by the provider, {} given another courier", stuck.size(), rescued);
        }
        return rescued;
    }

    /**
     * Stale booking attempts left PENDING: process died or network response lost.
     */
    private int rescueStalePendingAttempts(Instant now) {
        var cutoff = now.minus(Duration.ofMinutes(2));
        var stale = attempts.findByOutcomeAndStartedAtBefore("PENDING", cutoff);
        int rescued = 0;
        for (var attempt : stale) {
            try {
                bookingService.recoverStalePendingAttempt(attempt.getId());
                rescued++;
            } catch (RuntimeException ex) {
                log.error("Assignment sweep could not recover stale pending attempt {}", attempt.getId(), ex);
            }
        }
        if (!stale.isEmpty()) {
            log.info("Assignment sweep: {} stale pending attempts found, {} processed", stale.size(), rescued);
        }
        return rescued;
    }
}
