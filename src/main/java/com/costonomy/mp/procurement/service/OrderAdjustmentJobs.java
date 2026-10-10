package com.costonomy.mp.procurement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies doorstep refunds that were waiting for a card capture (D-129).
 *
 * <p>Deliberately not {@code @Transactional}: each adjustment runs in its own transaction through the service,
 * so one that fails does not roll back the others. A row still waiting after an hour means a capture that is
 * failing or was refused, which needs a person; it is logged at error once an hour per order.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderAdjustmentJobs {

    private static final int BATCH = 100;
    private static final Duration STUCK_AFTER = Duration.ofHours(1);

    private final OrderAdjustmentService adjustments;
    private final Map<Long, Instant> lastAlert = new HashMap<>();

    @Scheduled(fixedDelayString = "${costonomy.mp.order-adjustments.apply-interval:PT15S}")
    @SchedulerLock(name = "order-adjustment-apply", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void applyPendingCaptures() {
        for (Long id : adjustments.pendingIds(BATCH)) {
            try {
                boolean applied = adjustments.applyPending(id);
                if (applied) {
                    lastAlert.remove(id);
                }
            } catch (RuntimeException ex) {
                log.error("Order adjustment {} could not be applied: {}", id, ex.getMessage());
            }
        }
        // Stuck rows: the same ones come back every run until applied, so alert at most once an hour each.
        Instant now = Instant.now();
        for (Long id : adjustments.pendingIds(BATCH)) {
            Instant last = lastAlert.get(id);
            if (last == null) {
                lastAlert.put(id, now);
            } else if (Duration.between(last, now).compareTo(STUCK_AFTER) >= 0) {
                log.error("Order adjustment {} has been waiting for a card capture for over an hour: the capture "
                        + "is failing or was refused, and a person has to decide what happens to the refund", id);
                lastAlert.put(id, now);
            }
        }
    }
}
