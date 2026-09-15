package com.costonomy.mp.realtime.web;

import com.costonomy.mp.realtime.service.RealtimeTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Authenticates the WebSocket handshake by ticket. Doc 09 §4.
 *
 * <p>The socket's only authentication point. A ticket is spent here — atomically,
 * so a replay cannot open a second connection — and the user it belonged to is put
 * on the session's attributes for the handler to pick up.
 *
 * <p><b>The ticket is removed from the log line deliberately.</b> It arrives in a
 * query string, which is the one place a credential cannot be kept out of access
 * logs; that is the whole reason it is single-use and lives for thirty seconds
 * rather than being the access token itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

    public static final String USER_ID = "costonomy.userId";

    private final RealtimeTicketService tickets;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {

        String ticket = ticketFrom(request);
        Long userId = tickets.consume(ticket);

        if (userId == null) {
            // Spent, expired or never issued — one answer for all three, so a
            // caller cannot probe which.
            log.debug("Rejected a realtime handshake with an unusable ticket");
            return false;
        }

        attributes.put(USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // Nothing to do. The handler takes over from here.
    }

    private String ticketFrom(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servlet) {
            return servlet.getServletRequest().getParameter("ticket");
        }
        return null;
    }
}
