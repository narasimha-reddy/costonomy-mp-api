package com.costonomy.mp.common.outbox;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Drains the outbox. Doc 02 §10, doc 08 §3.
 *
 * <p>Currently republishes as an in-process Spring event, which is the right
 * shape for a modular monolith: handlers in other modules subscribe without the
 * producer knowing about them. If a message broker is introduced later, only
 * {@link #dispatch} changes — the transactional guarantee is already in the table.
 *
 * <p>{@code @SchedulerLock} so a multi-instance deployment publishes each event
 * once. Without it, every instance would drain the same batch, and at-least-once
 * would quietly become at-least-N-times. The lock is the first line; the second is the row claim
 * ({@code FOR UPDATE SKIP LOCKED}) inside each event's own transaction (D-194).
 */
@Component
@Slf4j
public class OutboxPublisher {

    static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    /** One transaction per event (D-194): a failing handler rolls back and retries only its own event. */
    private final TransactionTemplate perEvent;

    public OutboxPublisher(OutboxRepository repository, ApplicationEventPublisher eventPublisher,
                           PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.perEvent = new TransactionTemplate(transactionManager);
        this.perEvent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // No @Transactional on the drain itself: the ShedLock wraps the whole drain (SchedulingConfig orders it outside
    // every transaction) and each event has its own transaction inside it.
    @Scheduled(fixedDelayString = "${costonomy.mp.outbox.poll-interval:PT2S}")
    @SchedulerLock(name = "outbox-publisher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public void drain() {
        drainUnlocked();
    }

    /**
     * The same drain, started right after a commit that wrote an event (D-191). Same lock name as the poll, so it
     * never runs beside a poll or another instance's drain; if the lock is taken it simply does nothing and the
     * next poll picks the event up. No minimum hold, so a poll that just finished does not delay it.
     *
     * @return how many events this drain took, or {@code null} when the lock was taken and nothing ran (ShedLock
     *         skips the call). The caller uses it to try again shortly instead of leaving the event to the poll,
     *         and to go on when the batch was full (a backlog). Must stay {@code Integer}: with a primitive
     *         {@code int} ShedLock throws on every skipped call and the trigger would silently fall back to the poll.
     */
    @SchedulerLock(name = "outbox-publisher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
    public Integer drainAfterCommit() {
        return drainUnlocked();
    }

    /**
     * The drain without the lock. TESTS ONLY: public because tests in other packages run two drains at once to
     * prove the row claim holds when the lock does not (expired, lost, or another instance with a skewed clock),
     * and a package-private method on this proxied bean would reach the proxy's empty fields. Production code goes
     * through {@link #drain()} or {@link #drainAfterCommit()}.
     */
    public int drainUnlocked() {
        var ids = repository.findDispatchableIds(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        for (Long id : ids) {
            process(id);
        }
        return ids.size();
    }

    private void process(Long id) {
        try {
            perEvent.executeWithoutResult(status -> {
                var event = repository.lockDispatchable(id, Instant.now()).orElse(null);
                if (event == null) {
                    return; // another drain has it, or it is no longer pending and due
                }
                dispatch(event);
                event.setStatus(OutboxEvent.Status.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                repository.save(event);
            });
        } catch (Throwable ex) {
            // Throwable, not Exception: an Error from a handler (StackOverflowError, NoClassDefFoundError) must be
            // counted against its own event, not abort the drain and starve the rest of the batch.
            // The event's transaction is rolled back (including a handler's rollback-only mark that its listener
            // swallowed). Count the attempt in a transaction of its own so the rest of the batch is unaffected.
            try {
                perEvent.executeWithoutResult(status ->
                        repository.lockDispatchable(id, Instant.now()).ifPresent(e -> {
                            recordFailure(e, ex);
                            repository.save(e);
                        }));
            } catch (Throwable recordEx) {
                log.error("Could not record the outbox failure for event row {}; it stays pending", id, recordEx);
            }
            if (ex instanceof OutOfMemoryError oom) {
                throw oom; // recorded; a StackOverflowError is only one handler's problem, an OOM is the JVM's
            }
        }
    }

    private void dispatch(OutboxEvent event) {
        eventPublisher.publishEvent(new DomainEventEnvelope(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getPayloadVersion(),
                event.getPayload(),
                event.getActorId(),
                event.getCorrelationId(),
                event.getOccurredAt()));
    }

    private void recordFailure(OutboxEvent event, Throwable ex) {
        int attempts = event.getAttemptCount() + 1;
        event.setAttemptCount(attempts);
        event.setLastError(truncate(describe(ex)));

        if (attempts >= MAX_ATTEMPTS) {
            // Terminal and visible. An unpublished SupplierOrderAccepted means a
            // restaurant was never notified; that needs an alert, not a silent
            // row. Operations dashboards read countByStatus(FAILED) (doc 08 §11).
            event.setStatus(OutboxEvent.Status.FAILED);
            log.error("Outbox event FAILED permanently after {} attempts and will not be retried or replayed: "
                            + "id={} type={}",
                    attempts, event.getEventId(), event.getEventType(), ex);
            return;
        }

        // Exponential backoff, capped. A downstream that is down for a minute
        // should not be retried a hundred times in that minute.
        long delaySeconds = Math.min(300L, (long) Math.pow(2, attempts));
        event.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(delaySeconds)));
        log.warn("Outbox event failed, retrying in {}s: id={} type={} attempt={}",
                delaySeconds, event.getEventId(), event.getEventType(), attempts);
    }

    /** The class name plus the root cause's message: a null or generic message (NPE, rollback-only) still says what it was. */
    private static String describe(Throwable ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        String message = root.getMessage();
        String top = ex.getClass().getSimpleName();
        String cause = root == ex ? "" : " (" + root.getClass().getSimpleName() + ")";
        return top + cause + (message == null ? "" : ": " + message);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    /**
     * What subscribers receive.
     *
     * <p>{@code payload} stays JSON rather than a typed object so a consumer
     * deserialises to whatever shape it expects, and a producer changing its
     * payload does not break compilation across every module.
     */
    public record DomainEventEnvelope(
            String eventId,
            String eventType,
            String aggregateType,
            Long aggregateId,
            Integer payloadVersion,
            String payload,
            Long actorId,
            String correlationId,
            Instant occurredAt) {
    }
}
