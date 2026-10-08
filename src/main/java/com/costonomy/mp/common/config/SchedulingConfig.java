package com.costonomy.mp.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import com.costonomy.mp.common.logging.CorrelatedTaskScheduler;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
// order: the lock advisor must wrap the transaction advisor, so a job's lock is released only after its
// transaction has committed (D-194). Both default to LOWEST_PRECEDENCE, which left it to bean-registration order.
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M", order = Ordered.HIGHEST_PRECEDENCE)
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

    /**
     * The executor Boot would have made itself (D-101). Boot creates it only when
     * no {@code Executor} bean exists, and the scheduler above is one — so without
     * this, a future {@code @Async} would quietly run on the eight job threads.
     */
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    @Lazy
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
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
