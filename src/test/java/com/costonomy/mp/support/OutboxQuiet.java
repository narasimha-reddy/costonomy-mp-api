package com.costonomy.mp.support;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Waits out a context's start-up outbox drain, which holds the lock and would race a test that drains by hand. */
public final class OutboxQuiet {

    private OutboxQuiet() {
    }

    public static void awaitLockFree(LockProvider lockProvider) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            var lock = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                    Duration.ofSeconds(30), Duration.ZERO));
            if (lock.isPresent()) {
                lock.get().unlock();
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("a start-up drain is still holding the outbox lock");
            }
            Thread.sleep(50);
        }
    }
}
