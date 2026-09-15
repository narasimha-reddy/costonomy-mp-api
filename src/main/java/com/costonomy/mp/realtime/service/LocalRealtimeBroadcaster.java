package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Fans out within this JVM. The default, and correct for a single instance.
 *
 * <p>Switch to {@code REDIS} when running more than one — see
 * {@link RealtimeBroadcaster} for why that is not optional there.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "costonomy.mp.realtime.broadcaster", havingValue = "LOCAL",
        matchIfMissing = true)
public class LocalRealtimeBroadcaster implements RealtimeBroadcaster {

    private final RealtimeSessionRegistry sessions;

    @Override
    public void broadcast(RealtimeEvent event) {
        sessions.deliver(event);
    }
}
