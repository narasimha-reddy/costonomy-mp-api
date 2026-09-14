package com.costonomy.mp.common.outbox;

import com.costonomy.mp.common.web.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records domain events for asynchronous publication. Doc 02 §10, doc 08 §3.
 *
 * <p><b>Joins the caller's transaction, always.</b> That is the whole point: the
 * state change and the intent to publish commit together. Publishing inline
 * instead gives two failure modes that are both wrong — publish then roll back
 * (an event for something that never happened), or commit then fail to publish
 * (a supplier acceptance the restaurant is never told about).
 *
 * <p>Never call this outside a transaction that is also writing the state change.
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @param eventType  e.g. {@code SupplierOrderAccepted} (doc 08 §1)
     * @param occurredAt when the business fact happened; pass the transition's
     *                   own timestamp rather than {@code Instant.now()} where the
     *                   two can differ
     */
    @Transactional
    public void publish(
            String eventType,
            String aggregateType,
            Long aggregateId,
            Object payload,
            Long actorId,
            Instant occurredAt) {

        var event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setPayload(serialize(payload));
        event.setActorId(actorId);
        // Ties the event back to the request that caused it, so a trace can be
        // followed from an API call through to the notification it produced
        // (doc 09 §15).
        event.setCorrelationId(RequestContext.requestId());
        event.setOccurredAt(occurredAt == null ? Instant.now() : occurredAt);
        event.setStatus(OutboxEvent.Status.PENDING);

        repository.save(event);
    }

    @Transactional
    public void publish(String eventType, String aggregateType, Long aggregateId, Object payload, Long actorId) {
        publish(eventType, aggregateType, aggregateId, payload, actorId, Instant.now());
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        } catch (Exception ex) {
            // Deliberately fatal: this runs inside the business transaction, so
            // failing here rolls the state change back too. An order that changed
            // state without its event is a silent inconsistency that surfaces
            // later as a missing notification, which is far harder to diagnose
            // than a failed request.
            throw new IllegalStateException(
                    "Could not serialise outbox payload for " + eventType(payload), ex);
        }
    }

    private static String eventType(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}
