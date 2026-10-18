package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled job to process durable delivery recovery tasks (cancellations and refunds).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryRecoveryTaskJob {

    private final DeliveryRecoveryService recoveryService;

    @Value("${costonomy.mp.delivery.recovery-tasks.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${costonomy.mp.delivery.recovery-tasks.interval:PT30S}")
    @SchedulerLock(name = "delivery-recovery-tasks", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void run() {
        if (!enabled) {
            return;
        }
        int processed = recoveryService.processDueTasks();
        if (processed > 0) {
            log.info("Processed {} durable delivery recovery tasks", processed);
        }
    }
}
