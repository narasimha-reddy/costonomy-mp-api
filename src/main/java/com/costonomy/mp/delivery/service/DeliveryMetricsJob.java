package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled job that runs every 2 hours to aggregate latency and cost metrics per provider.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryMetricsJob {

    private final DeliveryMetricsAggregationService aggregationService;

    /**
     * Aggregates the previous 2-hour window every 2 hours.
     */
    @Scheduled(cron = "0 0 0/2 * * *", zone = "UTC")
    @SchedulerLock(name = "delivery-metrics-aggregation",
                   lockAtMostFor = "PT30M",
                   lockAtLeastFor = "PT1M")
    public void aggregatePreviousWindow() {
        // Floor to current 2-hour boundary, then take previous 2-hour block
        long currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS).getEpochSecond() / 3600;
        long windowStartEpochHour = (currentHour / 2) * 2 - 2;
        Instant windowStart = Instant.ofEpochSecond(windowStartEpochHour * 3600);

        log.info("Starting scheduled delivery provider metrics aggregation for window starting at {}", windowStart);
        try {
            int written = aggregationService.aggregateWindow(windowStart);
            log.info("Scheduled delivery provider metrics completed: {} provider records written for window {}",
                    written, windowStart);
        } catch (Exception ex) {
            log.error("Scheduled delivery provider metrics aggregation failed for window {}", windowStart, ex);
        }
    }
}
