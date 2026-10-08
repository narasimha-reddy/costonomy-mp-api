package com.costonomy.mp.common;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-191: an event published in a committed transaction is relayed right after the commit, not on the next 2 s poll.
 * The poll is left at PT1H (the test profile) so only the commit trigger can explain a fast relay.
 *
 * <p>The database is shared by every test class in the run, and the test profile never drains, so earlier classes
 * leave PENDING rows behind. This context's own start-up poll drains that backlog (about 80 ms a row through the
 * real handlers) while holding the outbox lock; a commit drain started then used to find the lock taken, give up,
 * and leave the event to the next poll (the failures at "relayed within 1 s" and at the lock acquire). The trigger
 * now retries a busy lock, and this class starts from a quiet outbox so it measures the trigger, not the backlog.
 */
@TestPropertySource(properties = "costonomy.mp.outbox.drain-on-commit=true")
@Import(OutboxRelayOnCommitIT.Probe.class)
class OutboxRelayOnCommitIT extends AbstractIntegrationTest {

    static class Probe {
        final Map<String, CountDownLatch> seen = new ConcurrentHashMap<>();

        @EventListener
        void on(OutboxPublisher.DomainEventEnvelope e) {
            var latch = seen.get(e.eventType());
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @Autowired private OutboxService outbox;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Probe probe;
    @Autowired private LockProvider lockProvider;

    @BeforeEach
    void quietOutbox() throws Exception {
        // Rows other classes left PENDING are not this test's business; take them out of the way.
        jdbc.update("update outbox_event set status = 'PUBLISHED' where status = 'PENDING'");
        // A drain from this context's start-up may still be running and holding the lock: wait until it is free.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            var lock = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                    Duration.ofSeconds(30), Duration.ZERO));
            if (lock.isPresent()) {
                lock.get().unlock();
                return;
            }
            assertThat(System.nanoTime()).describedAs("a start-up drain is still holding the outbox lock")
                    .isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private void publishInTransaction(String type) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                outbox.publish(type, "TEST", 1L, Map.of("k", "v"), null));
    }

    @Test
    void anEventPublishedInACommittedTransactionIsRelayedWithinOneSecond() throws Exception {
        String type = "RelayProbe" + UUID.randomUUID();
        var latch = new CountDownLatch(1);
        probe.seen.put(type, latch);

        long start = System.nanoTime();
        publishInTransaction(type);

        assertThat(latch.await(1, TimeUnit.SECONDS)).describedAs("relayed within 1 s of commit").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        // Marked published in the same drain (give the commit of the drain itself a moment).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        String status;
        do {
            status = jdbc.queryForObject("select status from outbox_event where event_type = ?", String.class, type);
            if ("PUBLISHED".equals(status)) {
                break;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(status).isEqualTo("PUBLISHED");
    }

    @Test
    void aRolledBackTransactionRelaysNothing() throws Exception {
        String type = "RelayProbeRollback" + UUID.randomUUID();
        var latch = new CountDownLatch(1);
        probe.seen.put(type, latch);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            outbox.publish(type, "TEST", 1L, Map.of("k", "v"), null);
            s.setRollbackOnly();
        });

        assertThat(latch.await(700, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = ?", Integer.class, type))
                .isZero();
    }

    @Test
    void whileAnotherDrainHoldsTheLockTheEventWaitsAndIsRelayedAsSoonAsTheLockIsFree() throws Exception {
        String type = "RelayProbeLocked" + UUID.randomUUID();
        var latch = new CountDownLatch(1);
        probe.seen.put(type, latch);

        // Another instance (or the poll) is draining: it holds the same lock the scheduled drain uses.
        var lock = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                Duration.ofSeconds(30), Duration.ZERO));
        assertThat(lock).isPresent();
        try {
            publishInTransaction(type);
            assertThat(latch.await(700, TimeUnit.MILLISECONDS)).isFalse();
            // Not relayed past the holder, and still pending.
            assertThat(jdbc.queryForObject("select status from outbox_event where event_type = ?", String.class,
                    type)).isEqualTo("PENDING");
        } finally {
            lock.get().unlock();
        }
        // The trigger was not lost: it retries the busy lock, so the event does not wait for the next poll (PT1H here).
        assertThat(latch.await(2, TimeUnit.SECONDS)).describedAs("relayed after the lock was released").isTrue();
    }

    @Test
    void anEventBehindABacklogOfMoreThanOneBatchIsStillRelayedPromptly() throws Exception {
        String type = "RelayProbeBacklog" + UUID.randomUUID();
        var latch = new CountDownLatch(1);
        probe.seen.put(type, latch);

        // 250 older events (nobody listens), then ours, all in one commit: one batch is 100, ours is the 251st.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            for (int i = 0; i < 250; i++) {
                outbox.publish("RelayBacklogFiller", "TEST", 1L, Map.of("i", i), null);
            }
            outbox.publish(type, "TEST", 1L, Map.of("k", "v"), null);
        });

        assertThat(latch.await(3, TimeUnit.SECONDS)).describedAs("relayed behind a backlog, poll is PT1H").isTrue();
    }
}
