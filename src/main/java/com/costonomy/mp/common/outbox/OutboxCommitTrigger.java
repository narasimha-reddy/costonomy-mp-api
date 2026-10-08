package com.costonomy.mp.common.outbox;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts an outbox drain right after a transaction that wrote an event commits, so an event does not wait for the
 * next poll (D-191). The poll stays as the safety net: this only makes the common case fast.
 *
 * <p>Bounded: one daemon thread, and at most one drain waiting behind the one running (a burst of commits is one
 * drain, which takes the whole batch). The drain itself is {@link OutboxPublisher#drainAfterCommit()}, which holds
 * the same ShedLock lock as the poll, so it cannot overlap a poll or another instance. It never runs on the
 * committing request's thread, and a failure here is only logged: the event is still PENDING for the poll.
 *
 * <p>A request is never dropped because the lock is busy (D-191 addendum): a drain that finds the lock taken
 * (the poll, or a drain on another instance, which has usually already read its batch) is tried again after a
 * short delay, a bounded number of times, and a drain that took a full batch goes on at once, so a backlog does
 * not leave the newest event waiting for the poll.
 */
@Component
@Slf4j
public class OutboxCommitTrigger {

    static final int MAX_BUSY_RETRIES = 20;
    static final long BUSY_RETRY_MILLIS = 100;
    static final long SHUTDOWN_WAIT_MILLIS = 10_000;

    private final OutboxPublisher publisher;
    private final boolean enabled;
    private final long retryMillis;
    private final long shutdownWaitMillis;
    /** True while a drain is queued or waiting to retry and has not yet started its attempt. */
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
        var t = new Thread(r, "outbox-after-commit");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public OutboxCommitTrigger(OutboxPublisher publisher,
                               @Value("${costonomy.mp.outbox.drain-on-commit:true}") boolean enabled) {
        this(publisher, enabled, BUSY_RETRY_MILLIS);
    }

    OutboxCommitTrigger(OutboxPublisher publisher, boolean enabled, long retryMillis) {
        this(publisher, enabled, retryMillis, SHUTDOWN_WAIT_MILLIS);
    }

    OutboxCommitTrigger(OutboxPublisher publisher, boolean enabled, long retryMillis, long shutdownWaitMillis) {
        this.shutdownWaitMillis = shutdownWaitMillis;
        this.publisher = publisher;
        this.enabled = enabled;
        this.retryMillis = retryMillis;
        executor.setRemoveOnCancelPolicy(true);
    }

    /** Call from inside the transaction that writes the event; the drain starts only if that transaction commits. */
    public void requestAfterCommit() {
        if (!enabled || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                request();
            }
        });
    }

    void request() {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        submit(0, 0);
    }

    private void submit(int busyRetries, long delayMillis) {
        try {
            executor.schedule(() -> run(busyRetries), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            queued.set(false);
        }
    }

    private void run(int busyRetries) {
        // Cleared before the drain, so a commit that lands during it queues one more.
        queued.set(false);
        try {
            Integer taken = publisher.drainAfterCommit();
            if (taken == null) {
                // The lock is held by a poll or another instance. Try again shortly rather than wait for the poll.
                if (busyRetries < MAX_BUSY_RETRIES && queued.compareAndSet(false, true)) {
                    submit(busyRetries + 1, retryMillis);
                }
            } else if (taken >= OutboxPublisher.BATCH_SIZE && queued.compareAndSet(false, true)) {
                submit(0, 0);
            }
        } catch (Throwable ex) {
            // Throwable, not RuntimeException: the executor's FutureTask would swallow an Error without a trace.
            log.warn("Outbox drain after commit failed; the poll will retry", ex);
        }
    }

    @PreDestroy
    void stop() {
        // Let a drain that is mid-event finish: interrupting it would roll back the PUBLISHED mark of an event
        // whose handler already did its external work, and the next instance would deliver it again (D-194).
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownWaitMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
