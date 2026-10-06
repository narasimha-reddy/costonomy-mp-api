package com.costonomy.mp.common.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Nightly purge of rows that only grow (D-148). Runs at 03:30 India time, when the platform is quietest; the lock keeps
 * two instances from both doing it. No transaction here, by design: see {@link RetentionPurger}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RetentionJobs {

    private final RetentionPurger purger;

    @Value("${costonomy.mp.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${costonomy.mp.retention.cron:0 30 3 * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "retention-purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void purge() {
        if (!enabled) {
            return;
        }
        var deleted = purger.runAll(Instant.now());
        log.info("Retention purge removed {}", deleted);
    }
}
