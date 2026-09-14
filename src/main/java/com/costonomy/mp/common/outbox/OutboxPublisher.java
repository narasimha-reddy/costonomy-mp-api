package com.costonomy.mp.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
 * would quietly become at-least-N-times.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${costonomy.mp.outbox.poll-interval:PT2S}")
    @SchedulerLock(name = "outbox-publisher", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    @Transactional
    public void drain() {
        var batch = repository.findDispatchable(Instant.now(), PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                dispatch(event);
                event.setStatus(OutboxEvent.Status.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
            } catch (Exception ex) {
                recordFailure(event, ex);
            }
        }

        repository.saveAll(batch);
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

    private void recordFailure(OutboxEvent event, Exception ex) {
        int attempts = event.getAttemptCount() + 1;
        event.setAttemptCount(attempts);
        event.setLastError(truncate(ex.getMessage()));

        if (attempts >= MAX_ATTEMPTS) {
            // Terminal and visible. An unpublished SupplierOrderAccepted means a
            // restaurant was never notified; that needs an alert, not a silent
            // row. Operations dashboards read countByStatus(FAILED) (doc 08 §11).
            event.setStatus(OutboxEvent.Status.FAILED);
            log.error("Outbox event failed permanently after {} attempts: id={} type={}",
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
