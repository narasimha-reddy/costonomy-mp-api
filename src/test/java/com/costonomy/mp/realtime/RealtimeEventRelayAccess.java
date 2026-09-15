package com.costonomy.mp.realtime;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.realtime.service.RealtimeEventRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands the realtime tests a way to publish a domain event.
 *
 * <p>Calls the relay directly rather than going through {@code ApplicationEventPublisher}
 * and the outbox drain. The envelope is byte-for-byte what {@code OutboxPublisher}
 * emits, so the relay is exercised exactly as it runs — but the test does not have
 * to wait out a scheduled sweep that is deliberately slowed to an hour in the test
 * profile.
 */
@Component
@RequiredArgsConstructor
public class RealtimeEventRelayAccess {

    private final RealtimeEventRelay relay;

    public void publish(OutboxPublisher.DomainEventEnvelope envelope) {
        relay.onDomainEvent(envelope);
    }
}
