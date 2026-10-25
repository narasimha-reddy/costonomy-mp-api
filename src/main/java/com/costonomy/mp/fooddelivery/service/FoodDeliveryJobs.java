package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The timers of food delivery (D-201). Both read the database, not a timer in memory, so a restart cannot strand a
 * delivery. Not transactional: each delivery is its own transaction inside the booking service, and one failing never
 * stops the others.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodDeliveryJobs {

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryAttemptRepository attempts;
    private final FoodDeliveryBookingService booking;
    private final FoodDeliveryProperties properties;

    /** Asks again for deliveries nobody has taken, and ends those past the retry window. */
    @Scheduled(fixedDelayString = "${costonomy.mp.food-delivery.retry-poll:PT30S}",
            initialDelayString = "${costonomy.mp.food-delivery.jobs-initial-delay:PT0S}")
    @SchedulerLock(name = "food-delivery-retry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void retryUnserved() {
        runRetry();
    }

    /** Hands a booking that never got a rider to the next courier. */
    @Scheduled(fixedDelayString = "${costonomy.mp.food-delivery.sweep-poll:PT30S}",
            initialDelayString = "${costonomy.mp.food-delivery.jobs-initial-delay:PT0S}")
    @SchedulerLock(name = "food-delivery-assignment-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweepAssignments() {
        runSweep(Instant.now());
    }

    /** Recovers deliveries stranded with stale PENDING attempts. */
    @Scheduled(fixedDelayString = "${costonomy.mp.food-delivery.stale-sweep-poll:PT30S}",
            initialDelayString = "${costonomy.mp.food-delivery.jobs-initial-delay:PT0S}")
    @SchedulerLock(name = "food-delivery-stale-attempt-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweepStalePendingAttempts() {
        runStalePendingSweep(Instant.now());
    }

    /** Reconciles stale pending gateway food delivery payments (D-197). */
    @Scheduled(fixedDelayString = "${costonomy.mp.food-delivery.payment-sweep-poll:PT60S}",
            initialDelayString = "${costonomy.mp.food-delivery.jobs-initial-delay:PT0S}")
    @SchedulerLock(name = "food-delivery-payment-sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void sweepPendingGatewayPayments() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        try {
            int recovered = booking.sweepStaleGatewayPayments();
            if (recovered > 0) {
                log.info("Food delivery payment sweep reconciled {} payments", recovered);
            }
        } catch (Exception ex) {
            log.error("Food delivery payment sweep failed: {}", ex.getMessage());
        }
    }

    private static final int BATCH_SIZE = 50;

    /** One retry pass; the scheduled method and the tests both call it. */
    public int runRetry() {
        if (!properties.isJobsEnabled()) {
            return 0;
        }
        int handled = 0;
        for (var d : deliveries.findByStatusInOrderByUpdatedAtAsc(
                List.of(FoodDeliveryStatus.PROVIDER_UNAVAILABLE, FoodDeliveryStatus.QUOTE_FAILED),
                org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE))) {
            try {
                booking.retry(d.getId());
                handled++;
            } catch (RuntimeException ex) {
                log.error("Food delivery {} retry failed", d.getId(), ex);
            }
        }
        return handled;
    }

    /** One sweep at the given instant; returns how many were given to another courier. */
    public int runSweep(Instant now) {
        if (!properties.isJobsEnabled()) {
            return 0;
        }
        int moved = 0;
        for (var d : deliveries.findByStatusAndAssignmentDeadlineBeforeOrderByAssignmentDeadlineAscIdAsc(
            FoodDeliveryStatus.PROVIDER_SELECTED, now.minus(properties.getSweepGrace()),
            org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE))) {
            try {
                if (booking.rebookOnTimeout(d.getId())) {
                    moved++;
                }
            } catch (RuntimeException ex) {
                log.error("Food delivery {} assignment sweep failed", d.getId(), ex);
            }
        }
        return moved;
    }

    /** One stale attempt sweep at the given instant; recovers any attempt left PENDING past pendingStaleAfter. */
    public int runStalePendingSweep(Instant now) {
        if (!properties.isJobsEnabled()) {
            return 0;
        }
        var staleCutoff = now.minus(properties.getPendingStaleAfter());
        var stale = attempts.findByOutcomeAndStartedAtBeforeOrderByStartedAtAscIdAsc("PENDING", staleCutoff,
            org.springframework.data.domain.PageRequest.of(0, BATCH_SIZE));
        int recovered = 0;
        for (var attempt : stale) {
            try {
                booking.recoverStalePendingAttempt(attempt.getId());
                recovered++;
            } catch (RuntimeException ex) {
                log.error("Food delivery stale pending sweep failed for attempt {}", attempt.getId(), ex);
            }
        }
        return recovered;
    }
}
