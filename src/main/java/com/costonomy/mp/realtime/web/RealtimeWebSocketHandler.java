package com.costonomy.mp.realtime.web;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.costonomy.mp.realtime.service.RealtimeEntitlements;
import com.costonomy.mp.realtime.service.RealtimeQueryService;
import com.costonomy.mp.realtime.service.RealtimeSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;

/**
 * The socket. Doc 06 §9.
 *
 * <p>Deliberately a plain text protocol rather than STOMP: the client is React
 * Native, the messages are one-directional pushes of a single shape, and a
 * sub-protocol would add a broker's worth of machinery to deliver JSON one way.
 *
 * <p>The server speaks first, with a {@code ready} frame naming the channels the
 * session is on and the cursor it should resume from. That matters: without it a
 * client cannot tell "connected and quiet" from "connected to nothing", and doc 05
 * §16 requires it to refresh authoritative state on connect — it needs to know
 * from where.
 *
 * <p>The client may send {@code ping}; it may not ask to join a channel.
 * <b>Subscriptions are derived from grants, never requested</b>, which removes the
 * entire class of bug where a client asks for someone else's channel and the check
 * has a hole in it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private final RealtimeSessionRegistry sessions;
    private final RealtimeEntitlements entitlements;
    private final RealtimeQueryService queries;
    private final ObjectMapper json;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get(RealtimeHandshakeInterceptor.USER_ID);
        if (userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Re-derived here, not taken from the ticket. Doc 46: a revoked grant takes
        // effect on the next request, and this is that request.
        Set<RealtimeChannel> channels = entitlements.channelsFor(userId);
        if (channels.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        sessions.register(session, channels);

        sessions.send(session, Map.of(
                "type", "ready",
                "channels", channels.stream().map(RealtimeChannel::name).toList(),
                // Where to resume. The client refetches authoritative state and
                // then plays forward from here, so nothing between its last poll
                // and this connection is lost.
                "cursor", queries.currentCursor(userId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String body = message.getPayload();
        if (body == null || body.isBlank()) {
            return;
        }

        try {
            var node = json.readTree(body);
            String type = node.path("type").asText("");
            if ("ping".equals(type)) {
                // Keeps intermediaries from closing an idle connection, and gives
                // the client a liveness signal that does not depend on traffic.
                sessions.send(session, Map.of("type", "pong"));
            }
            // Anything else is ignored rather than answered. The socket is
            // one-directional by design; a command channel here would be a second
            // API surface with its own authorization to get wrong.
        } catch (Exception ex) {
            log.debug("Ignoring unreadable realtime message from {}", session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.unregister(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Realtime transport error on {}: {}",
                session.getId(), exception.getMessage());
        sessions.unregister(session.getId());
    }
}
