package com.costonomy.mp.realtime.config;

import com.costonomy.mp.realtime.web.RealtimeHandshakeInterceptor;
import com.costonomy.mp.realtime.web.RealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Arrays;

/**
 * Publishes the socket endpoint.
 *
 * <p><b>Never {@code setAllowedOrigins("*")}.</b> A wildcard would let any web page
 * on the internet open an authenticated socket if a ticket ever leaked into a
 * browser context.
 *
 * <p>The default is an empty list, which leaves Spring's same-origin policy in
 * place. Native clients send no {@code Origin} header and are unaffected either
 * way — but a browser does, and a browser is how this app is developed against
 * Expo web. Without an explicit origin the handshake is refused and the client
 * falls back to polling, which looks like nothing is wrong at all.
 *
 * <p>So the list is configuration rather than code: the local profile names the
 * dev server, and a deployment names its own web origin if it has one.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class RealtimeWebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final RealtimeHandshakeInterceptor interceptor;

    @Value("${costonomy.mp.realtime.socket-path:/api/v1/realtime/socket}")
    private String socketPath;

    /** Exact origins allowed to open a socket. Empty means same-origin only. */
    @Value("${costonomy.mp.realtime.allowed-origins:}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration = registry.addHandler(handler, socketPath).addInterceptors(interceptor);

        var origins = Arrays.stream(allowedOrigins)
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);

        if (origins.length > 0) {
            // Exact origins only. `setAllowedOrigins` rejects nothing when handed
            // "*", so the guard above matters more than it looks.
            registration.setAllowedOrigins(origins);
        }
    }
}
