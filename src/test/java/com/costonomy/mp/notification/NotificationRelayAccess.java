package com.costonomy.mp.notification;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.notification.service.NotificationRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands the notification tests a way to publish a domain event.
 *
 * <p>Calls the relay directly rather than going through the outbox drain. The
 * envelope is byte-for-byte what {@code OutboxPublisher} emits, so the relay is
 * exercised exactly as it runs — without waiting out a sweep the test profile
 * deliberately slows to an hour.
 */
@Component
@RequiredArgsConstructor
public class NotificationRelayAccess {

    private final NotificationRelay relay;

    public void publish(OutboxPublisher.DomainEventEnvelope envelope) {
        relay.onDomainEvent(envelope);
    }
}
