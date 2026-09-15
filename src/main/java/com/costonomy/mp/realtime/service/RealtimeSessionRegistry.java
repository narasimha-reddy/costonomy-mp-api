package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.costonomy.mp.realtime.domain.RealtimeEvent;
import com.costonomy.mp.realtime.web.dto.RealtimeDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sockets this instance is holding, and what each is listening to.
 *
 * <p>In memory on purpose: a WebSocket session is a TCP connection to <em>this</em>
 * process and cannot be shared, so persisting the registry would describe
 * connections another instance cannot use. What crosses instances is the event
 * (see {@link RealtimeBroadcaster}), not the socket.
 *
 * <p><b>Entitlement is checked here, on every delivery, not only at join.</b> The
 * subscription set was authorised at handshake; re-checking membership before each
 * send is what makes a routing mistake upstream a dropped message rather than a
 * disclosure. It costs a set lookup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeSessionRegistry {

    /** sessionId → the live socket. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** sessionId → the channels that session may receive. */
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    private final ObjectMapper json;

    public void register(WebSocketSession session, Set<RealtimeChannel> channels) {
        sessions.put(session.getId(), session);
        subscriptions.put(session.getId(),
                Set.copyOf(channels.stream().map(RealtimeChannel::name).toList()));
        log.debug("Realtime session {} listening on {}", session.getId(), channels.size());
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
        subscriptions.remove(sessionId);
    }

    public Set<String> channelsOf(String sessionId) {
        return subscriptions.getOrDefault(sessionId, Set.of());
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Push an event to every local session entitled to its channel. */
    public void deliver(RealtimeEvent event) {
        var frame = frame(event);
        if (frame == null) {
            return;
        }

        subscriptions.forEach((sessionId, channels) -> {
            if (!channels.contains(event.getChannel())) {
                return;
            }
            var session = sessions.get(sessionId);
            if (session == null || !session.isOpen()) {
                unregister(sessionId);
                return;
            }
            try {
                // Synchronized on the session: Spring's WebSocketSession is not
                // safe for concurrent sends, and two events arriving together
                // would otherwise interleave into one corrupt frame.
                synchronized (session) {
                    session.sendMessage(new TextMessage(frame));
                }
            } catch (IOException | IllegalStateException ex) {
                // A client that went away mid-send. Nothing is lost: the event is
                // in realtime_event and they will catch up by cursor.
                log.debug("Dropping realtime session {}: {}", sessionId, ex.getMessage());
                closeQuietly(session);
                unregister(sessionId);
            }
        });
    }

    public void send(WebSocketSession session, Object message) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json.writeValueAsString(message)));
            }
        } catch (Exception ex) {
            log.debug("Could not write to realtime session {}: {}",
                    session.getId(), ex.getMessage());
        }
    }

    private String frame(RealtimeEvent event) {
        try {
            return json.writeValueAsString(new RealtimeDtos.EventResponse(
                    event.getId(), event.getChannel(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId(),
                    json.readTree(event.getPayload()), event.getOccurredAt()));
        } catch (Exception ex) {
            log.warn("Could not serialise realtime event {}", event.getId(), ex);
            return null;
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
