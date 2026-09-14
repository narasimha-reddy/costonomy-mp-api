package com.costonomy.mp.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
