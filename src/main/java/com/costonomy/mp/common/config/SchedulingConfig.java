package com.costonomy.mp.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import com.costonomy.mp.common.logging.CorrelatedTaskScheduler;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

/**
 * Distributed locking for scheduled jobs.
 *
 * <p>Supplier timeout, payment reconciliation, credit overdue evaluation and
 * settlement generation must each run once per interval across the deployment,
 * not once per instance. Two instances expiring the same supplier order
 * concurrently is exactly the race doc 10 §2 requires us not to have.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulingConfig {

    /**
     * Replaces Boot's scheduler with one that gives every job run a correlation
     * id (D-100). Built from Boot's builder, so {@code spring.task.scheduling.*}
     * — the pool size and thread names — still applies.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.configure(new CorrelatedTaskScheduler());
    }

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        // Compare against database time, not each instance's clock.
                        // Instances whose clocks drift apart would otherwise
                        // disagree about whether a lock had expired.
                        .usingDbTime()
                        .build());
    }
}
