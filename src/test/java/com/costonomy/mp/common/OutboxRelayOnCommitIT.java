package com.costonomy.mp.common;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.support.AbstractIntegrationTest;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
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
    void whileAnotherDrainHoldsTheLockTheCommitTriggerDoesNotRun() throws Exception {
        String type = "RelayProbeLocked" + UUID.randomUUID();
        var latch = new CountDownLatch(1);
        probe.seen.put(type, latch);

        // Another instance is draining: it holds the same lock the scheduled drain uses.
        var lock = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                Duration.ofSeconds(30), Duration.ZERO));
        assertThat(lock).isPresent();
        try {
            publishInTransaction(type);
            assertThat(latch.await(700, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            lock.get().unlock();
        }
        // The event is still pending, for the next poll to take.
        assertThat(jdbc.queryForObject("select status from outbox_event where event_type = ?", String.class, type))
                .isEqualTo("PENDING");
    }
}
