package com.costonomy.mp.common.outbox;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts an outbox drain right after a transaction that wrote an event commits, so an event does not wait for the
 * next poll (D-191). The poll stays as the safety net: this only makes the common case fast.
 *
 * <p>Bounded: one daemon thread, and at most one drain waiting behind the one running (a burst of commits is one
 * drain, which takes the whole batch). The drain itself is {@link OutboxPublisher#drainAfterCommit()}, which holds
 * the same ShedLock lock as the poll, so it cannot overlap a poll or another instance. It never runs on the
 * committing request's thread, and a failure here is only logged: the event is still PENDING for the poll.
 */
@Component
@Slf4j
public class OutboxCommitTrigger {

    private final OutboxPublisher publisher;
    private final boolean enabled;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "outbox-after-commit");
        t.setDaemon(true);
        return t;
    });

    public OutboxCommitTrigger(OutboxPublisher publisher,
                               @Value("${costonomy.mp.outbox.drain-on-commit:true}") boolean enabled) {
        this.publisher = publisher;
        this.enabled = enabled;
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
        try {
            executor.execute(() -> {
                // Cleared before the drain, so a commit that lands during it queues one more.
                queued.set(false);
                try {
                    publisher.drainAfterCommit();
                } catch (RuntimeException ex) {
                    log.warn("Outbox drain after commit failed; the poll will retry", ex);
                }
            });
        } catch (RuntimeException ex) {
            queued.set(false);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
