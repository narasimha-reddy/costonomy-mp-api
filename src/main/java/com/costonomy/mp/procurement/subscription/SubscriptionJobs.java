package com.costonomy.mp.procurement.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Generates tomorrow's subscription orders. Runs hourly from 18:00 to 23:00 India time so a wallet topped
 * up the same evening is picked up; each run is idempotent through {@code subscription_run} and the
 * database's one-order-per-date key. A date that is still unfunded after the last run is lost, and the
 * next due date is tried as normal.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SubscriptionJobs {

    private final SubscriptionGenerationService generation;

    @Scheduled(cron = "${costonomy.mp.subscriptions.generation-cron:0 0 18-23 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "subscription-generate", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateTomorrow() {
        LocalDate tomorrow = LocalDate.now(SubscriptionService.ZONE).plusDays(1);
        var summary = generation.runFor(tomorrow);
        log.info("Subscription orders for {}: {}", tomorrow, summary);
    }
}
