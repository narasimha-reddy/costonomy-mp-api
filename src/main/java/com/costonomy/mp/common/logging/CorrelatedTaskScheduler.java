package com.costonomy.mp.common.logging;

import com.costonomy.mp.common.web.RequestContext;
import org.slf4j.MDC;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * The scheduler every {@code @Scheduled} job runs on, giving each run its own
 * correlation id (D-100).
 *
 * <p>An HTTP request gets one from {@code RequestIdFilter}; a job run got
 * nothing, so every line a sweep logged — and every audit row and outbox event it
 * wrote — carried {@code "-"}. A payment settled by the reconciliation sweep left
 * an audit trail nobody could tie to the run that produced it.
 *
 * <p>Each run now gets {@code job-<method>-<8 hex>}, set in both places that read
 * it: the MDC (logs) and {@link RequestContext} (audit, outbox). They are separate
 * thread-locals, and setting only one would make the logs and the audit trail
 * disagree about which run did what.
 *
 * <p>A subclass rather than a task decorator because
 * {@code ThreadPoolTaskScheduler.setTaskDecorator} arrived in Spring 6.2; this is
 * 6.1. Every scheduling entry point wraps its runnable, so a periodic job gets a
 * fresh id on every execution, not one for its lifetime.
 */
public class CorrelatedTaskScheduler extends ThreadPoolTaskScheduler {

    static final String MDC_KEY = "requestId";

    /** Wrap a job so each execution runs under its own correlation id. */
    static Runnable correlated(Runnable job) {
        String name = job instanceof ScheduledMethodRunnable method
                ? method.getMethod().getName() : "task";
        return () -> {
            String id = "job-" + name + "-" + UUID.randomUUID().toString().substring(0, 8);
            RequestContext.setRequestId(id);
            MDC.put(MDC_KEY, id);
            try {
                job.run();
            } finally {
                // Pooled threads: an id left behind would label the next job's lines.
                MDC.remove(MDC_KEY);
                RequestContext.clear();
            }
        };
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return super.schedule(correlated(task), trigger);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
        return super.schedule(correlated(task), startTime);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
        return super.scheduleAtFixedRate(correlated(task), startTime, period);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return super.scheduleAtFixedRate(correlated(task), period);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
        return super.scheduleWithFixedDelay(correlated(task), startTime, delay);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return super.scheduleWithFixedDelay(correlated(task), delay);
    }
}
