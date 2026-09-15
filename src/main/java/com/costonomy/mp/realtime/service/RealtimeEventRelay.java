package com.costonomy.mp.realtime.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.costonomy.mp.realtime.domain.RealtimeEvent;
import com.costonomy.mp.realtime.repository.RealtimeEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Turns domain events into realtime events. Doc 06 §9, doc 08 §3.
 *
 * <p><b>It listens to the outbox rather than being called by services.</b> Every
 * state change already publishes there — that is what the outbox is for — and a
 * {@code broadcast(...)} call sprinkled through procurement, payment, credit and
 * delivery would be a second publishing mechanism to keep in step with the first.
 * It would also be the one people forget: a new event type would simply never
 * reach a phone, with nothing failing to say so.
 *
 * <p>Two consequences worth stating. Realtime inherits the outbox's
 * <b>transactional guarantee</b> — an event exists only if the state change
 * committed, so a client cannot be told about a rolled-back order. And it inherits
 * <b>at-least-once</b> delivery, which is why the projection is deduplicated on
 * {@code (event_id, channel)}.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> This runs inside the outbox
 * drain's transaction, which is publishing a batch of up to a hundred events. Each
 * projection therefore commits separately, through {@link RealtimeEventStore} —
 * otherwise one duplicate would mark the drain's transaction rollback-only and
 * take the whole batch down with it (D-021, and see the store for the detail).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeEventRelay {

    private final RealtimeRouter router;
    private final RealtimeEventStore store;
    private final RealtimeBroadcaster broadcaster;

    @EventListener
    public void onDomainEvent(OutboxPublisher.DomainEventEnvelope envelope) {
        var channels = router.channelsFor(envelope.aggregateType(), envelope.payload());
        if (channels.isEmpty()) {
            // Nobody's business in realtime. Dropped deliberately — see
            // RealtimeRouter for why a guess would be worse than nothing.
            return;
        }

        for (RealtimeChannel channel : channels) {
            var event = new RealtimeEvent();
            event.setEventId(envelope.eventId());
            event.setChannel(channel.name());
            event.setEventType(envelope.eventType());
            event.setAggregateType(envelope.aggregateType());
            event.setAggregateId(envelope.aggregateId());
            event.setPayload(envelope.payload());
            event.setOccurredAt(envelope.occurredAt());

            try {
                event = store.project(event);
            } catch (DataIntegrityViolationException ex) {
                // uk_realtime_event_channel: the outbox re-offered an event it had
                // already delivered. Skipped rather than pushed again, so a
                // redelivery does not show a restaurant the same order twice.
                log.debug("Realtime event {} already projected onto {}",
                        envelope.eventId(), channel.name());
                continue;
            }

            broadcaster.broadcast(event);
        }
    }
}
