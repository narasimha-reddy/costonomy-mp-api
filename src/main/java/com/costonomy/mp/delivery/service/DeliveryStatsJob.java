package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Nightly job that materialises one day's provider reliability snapshot.
 *
 * <p>Runs at 02:00 UTC daily — enough after midnight that all provider webhooks
 * for the previous day have arrived (Pidge's SLA for late callbacks is 1 hour).
 * ShedLock guarantees at-most-once execution across multiple app instances.
 *
 * <p>The job can also be triggered manually via the admin endpoint
 * {@code POST /api/v1/admin/delivery-providers/stats/aggregate?date=YYYY-MM-DD}
 * when a day needs to be re-run after a data backfill.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryStatsJob {

    private final DeliveryStatsAggregationService aggregationService;

    /**
     * Aggregate yesterday's stats at 02:00 UTC every day.
     *
     * <p>The lock name is unique so it does not interfere with
     * {@code DeliveryJobs.pollActiveDeliveries}.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @SchedulerLock(name = "delivery-stats-aggregation",
                   lockAtMostFor = "PT30M",
                   lockAtLeastFor = "PT1M")
    public void aggregateYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Starting nightly delivery provider stats aggregation for {}", yesterday);
        try {
            int written = aggregationService.aggregate(yesterday);
            log.info("Nightly delivery provider stats completed: {} provider rows written for {}",
                    written, yesterday);
        } catch (Exception ex) {
            // Log and swallow — a stats failure must never affect live delivery flow.
            log.error("Nightly delivery provider stats aggregation failed for {}", yesterday, ex);
        }
    }
}
