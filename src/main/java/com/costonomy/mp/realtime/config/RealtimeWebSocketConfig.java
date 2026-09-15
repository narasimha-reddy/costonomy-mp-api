package com.costonomy.mp.realtime.config;

import com.costonomy.mp.realtime.web.RealtimeHandshakeInterceptor;
import com.costonomy.mp.realtime.web.RealtimeWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Publishes the socket endpoint.
 *
 * <p>No {@code setAllowedOrigins("*")}. The clients are native apps, which send no
 * Origin header and are unaffected; a wildcard would additionally let any web page
 * on the internet open an authenticated socket if a ticket ever leaked into a
 * browser context.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class RealtimeWebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final RealtimeHandshakeInterceptor interceptor;

    @Value("${costonomy.mp.realtime.socket-path:/api/v1/realtime/socket}")
    private String socketPath;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, socketPath).addInterceptors(interceptor);
    }
}
