package com.costonomy.mp.common.outbox;

import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.CountingStatementInspector;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-194: the relay's invariant is "the ShedLock wraps the drain; rows are claimed with SKIP LOCKED; one transaction
 * per event". Each test pins one of the three, against the real MySQL.
 */
@Import(OutboxHardeningIT.Handlers.class)
class OutboxHardeningIT extends AbstractIntegrationTest {

    /** Test handlers: count deliveries per event id, fail or hold on request. */
    public static class Handlers {
        static final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
        static final Map<String, Boolean> throwing = new ConcurrentHashMap<>();
        static final Map<String, Boolean> rollbackOnlyAndSwallow = new ConcurrentHashMap<>();
        static final Map<String, CountDownLatch> holdUntil = new ConcurrentHashMap<>();
        static final Map<String, CountDownLatch> holding = new ConcurrentHashMap<>();
        static volatile long sleepMillis;
        static volatile List<Boolean> lockHeldAtBeforeCommit;
        static volatile LockProvider lockProvider;
        static volatile String watchedType;

        @EventListener
        @Transactional
        public void on(OutboxPublisher.DomainEventEnvelope e) throws Exception {
            String type = e.eventType();
            if (!type.startsWith("Hard")) {
                return;
            }
            deliveries.computeIfAbsent(e.eventId(), k -> new AtomicInteger()).incrementAndGet();
            if (sleepMillis > 0) {
                Thread.sleep(sleepMillis);
            }
            var hold = holdUntil.get(type);
            if (hold != null) {
                holding.get(type).countDown();
                hold.await(20, TimeUnit.SECONDS);
            }
            if (Boolean.TRUE.equals(throwing.get(type))) {
                throw new IllegalStateException("handler failed for " + type);
            }
            if (Boolean.TRUE.equals(rollbackOnlyAndSwallow.get(type))) {
                // What DeliveryDispatchListener does: the callee's transaction marks the shared one rollback-only,
                // the listener swallows the exception, and the drain's commit then fails.
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return;
            }
            if (type.equals(watchedType)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        // Another holder of the same lock name can only be refused while the drain holds it.
                        var again = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                                Duration.ofSeconds(30), Duration.ZERO));
                        again.ifPresent(l -> l.unlock());
                        lockHeldAtBeforeCommit.add(again.isEmpty());
                    }
                });
            }
        }
    }

    @Autowired private OutboxService outbox;
    @Autowired private OutboxPublisher publisher;
    @Autowired private OutboxRepository repository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LockProvider lockProvider;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void quietOutbox() throws Exception {
        jdbc.update("update outbox_event set status = 'PUBLISHED', published_at = now(3) where status = 'PENDING'");
        Handlers.deliveries.clear();
        Handlers.throwing.clear();
        Handlers.rollbackOnlyAndSwallow.clear();
        Handlers.holdUntil.clear();
        Handlers.holding.clear();
        Handlers.sleepMillis = 0;
        Handlers.watchedType = null;
        Handlers.lockProvider = lockProvider;
        // This context's start-up drain may still hold the lock: wait until it is free.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            var lock = lockProvider.lock(new LockConfiguration(Instant.now(), "outbox-publisher",
                    Duration.ofSeconds(30), Duration.ZERO));
            if (lock.isPresent()) {
                lock.get().unlock();
                break;
            }
            assertThat(System.nanoTime()).describedAs("a start-up drain is still holding the outbox lock")
                    .isLessThan(deadline);
            Thread.sleep(50);
        }
        jdbc.update("update outbox_event set status = 'PUBLISHED', published_at = now(3) where status = 'PENDING'");
    }

    private void publish(String type) {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                outbox.publish(type, "TEST", 1L, Map.of("k", "v"), null));
    }

    private Map<String, Object> row(String type) {
        return jdbc.queryForMap("select status, attempt_count, next_attempt_at, last_error from outbox_event "
                + "where event_type = ?", type);
    }

    // ---- S1: the lock wraps every transaction ------------------------------------------------------------------

    @Test
    void theLockAdvisorIsOrderedOutsideTheTransactionAdvisorOnEveryLockedJob() {
        int checked = 0;
        for (String name : context.getBeanNamesForType(Object.class)) {
            Object bean;
            try {
                bean = context.getBean(name);
            } catch (Exception ex) {
                continue;
            }
            if (!(bean instanceof Advised advised)) {
                continue;
            }
            int lockAt = -1;
            int txAt = -1;
            Advisor[] advisors = advised.getAdvisors();
            for (int i = 0; i < advisors.length; i++) {
                if (advisors[i].getClass().getName().contains("ScheduledLockAdvisor")) {
                    lockAt = i;
                    assertThat(((Ordered) advisors[i]).getOrder()).describedAs("lock advisor order on " + name)
                            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                } else if (advisors[i] instanceof BeanFactoryTransactionAttributeSourceAdvisor) {
                    txAt = i;
                }
            }
            if (lockAt >= 0 && txAt >= 0) {
                checked++;
                assertThat(lockAt).describedAs("the lock advisor must come before the transaction advisor on "
                        + name).isLessThan(txAt);
            }
        }
        assertThat(checked).describedAs("beans with both a lock and a transaction advisor").isPositive();
    }

    @Test
    void theLockIsStillHeldWhenAnEventTransactionIsAboutToCommit() {
        String type = "HardLockHeld" + UUID.randomUUID();
        Handlers.watchedType = type;
        Handlers.lockHeldAtBeforeCommit = new ArrayList<>();
        publish(type);

        Integer taken = publisher.drainAfterCommit();

        assertThat(taken).isEqualTo(1);
        assertThat(Handlers.lockHeldAtBeforeCommit).describedAs("lock busy at beforeCommit").containsExactly(true);
        assertThat(row(type).get("status")).isEqualTo("PUBLISHED");
    }

    // ---- S2: rows are claimed with SKIP LOCKED -----------------------------------------------------------------

    @Test
    void theClaimQueryIsForUpdateSkipLocked() {
        publish("HardSql" + UUID.randomUUID());
        Long id = jdbc.queryForObject("select max(id) from outbox_event", Long.class);
        CountingStatementInspector.reset();

        new TransactionTemplate(txManager).executeWithoutResult(s ->
                repository.lockDispatchable(id, Instant.now()));

        assertThat(CountingStatementInspector.statements()).anyMatch(q -> q.toLowerCase().contains("for update skip locked"));
    }

    @Test
    void twoDrainsRunningAtOnceNeverDeliverTheSameEventTwice() throws Exception {
        String prefix = "HardConc" + UUID.randomUUID();
        for (int i = 0; i < 24; i++) {
            publish(prefix);
        }
        Handlers.sleepMillis = 15;
        var pool = Executors.newFixedThreadPool(2);
        try {
            var go = new CountDownLatch(1);
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    // The lock is bypassed on purpose: this is the lock-expired or lock-lost case.
                    return publisher.drainUnlocked();
                }));
            }
            go.countDown();
            for (var f : runs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        var ids = jdbc.queryForList("select event_id from outbox_event where event_type = ?", String.class, prefix);
        assertThat(ids).hasSize(24);
        for (String id : ids) {
            assertThat(Handlers.deliveries.get(id)).describedAs("deliveries of " + id).isNotNull();
            assertThat(Handlers.deliveries.get(id).get()).describedAs("deliveries of " + id).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and status = 'PUBLISHED'",
                Integer.class, prefix)).isEqualTo(24);
    }

    @Test
    void aSecondDrainSkipsARowThatAnotherDrainIsHoldingInsteadOfWaitingForIt() throws Exception {
        String type = "HardHeld" + UUID.randomUUID();
        Handlers.holdUntil.put(type, new CountDownLatch(1));
        Handlers.holding.put(type, new CountDownLatch(1));
        publish(type);

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> publisher.drainUnlocked());
            assertThat(Handlers.holding.get(type).await(10, TimeUnit.SECONDS)).isTrue();
            // The first drain is inside the handler with the row locked. The second must not queue behind it.
            Future<Integer> second = pool.submit(() -> publisher.drainUnlocked());
            second.get(5, TimeUnit.SECONDS);
            assertThat(Handlers.deliveries.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(1);
            Handlers.holdUntil.get(type).countDown();
            first.get(20, TimeUnit.SECONDS);
        } finally {
            Handlers.holdUntil.get(type).countDown();
            pool.shutdownNow();
        }
        assertThat(row(type).get("status")).isEqualTo("PUBLISHED");
    }

    // ---- S4: one transaction per event -------------------------------------------------------------------------

    @Test
    void aHandlerThatThrowsFailsOnlyItsOwnEventAndTheRestOfTheBatchIsPublished() {
        String ok1 = "HardOkA" + UUID.randomUUID();
        String bad = "HardBad" + UUID.randomUUID();
        String ok2 = "HardOkC" + UUID.randomUUID();
        Handlers.throwing.put(bad, true);
        publish(ok1);
        publish(bad);
        publish(ok2);

        Integer taken = publisher.drainAfterCommit();

        assertThat(taken).isEqualTo(3);
        assertThat(row(ok1).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(ok2).get("status")).isEqualTo("PUBLISHED");
        var failed = row(bad);
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(failed.get("attempt_count")).isEqualTo(1);
        assertThat(failed.get("last_error").toString()).contains("handler failed");
        Long failedId = jdbc.queryForObject("select id from outbox_event where event_type = ?", Long.class, bad);
        // Read through the entity: the column is UTC and a raw Timestamp would shift by the JVM's zone.
        assertThat(repository.findById(failedId).orElseThrow().getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void aHandlerThatMarksTheSharedTransactionRollbackOnlyAndSwallowsItFailsOnlyItsOwnEvent() {
        String ok1 = "HardOkA" + UUID.randomUUID();
        String bad = "HardPoison" + UUID.randomUUID();
        String ok2 = "HardOkC" + UUID.randomUUID();
        Handlers.rollbackOnlyAndSwallow.put(bad, true);
        publish(ok1);
        publish(bad);
        publish(ok2);

        publisher.drainAfterCommit();

        assertThat(row(ok1).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(ok2).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(bad).get("status")).isEqualTo("PENDING");
        assertThat(row(bad).get("attempt_count")).isEqualTo(1);
    }

    @Test
    void aPoisonEventBecomesFailedAfterTheMaximumAttempts() {
        String bad = "HardBad" + UUID.randomUUID();
        Handlers.throwing.put(bad, true);
        publish(bad);
        jdbc.update("update outbox_event set attempt_count = 9 where event_type = ?", bad);

        publisher.drainAfterCommit();

        assertThat(row(bad).get("status")).isEqualTo("FAILED");
        assertThat(row(bad).get("attempt_count")).isEqualTo(10);
    }
}
