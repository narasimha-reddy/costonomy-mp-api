package com.costonomy.mp.common.config;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.web.RequestContext;
import com.costonomy.security.web.CostonomySecurityConfigurer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * HTTP security.
 *
 * <p>Deliberately different from {@code costonomy-api}'s equivalent in two ways,
 * both of which matter:
 *
 * <ol>
 *   <li>There is <b>no {@code api.auth.enabled=false} switch</b>. In
 *       costonomy-api that flag makes the JWT filter inject an anonymous
 *       principal holding every permission. A marketplace moves money and
 *       extends credit between two parties who are not each other's employees;
 *       a configuration property that turns all of that off is a property that
 *       will eventually be set in the wrong environment. Local development
 *       authenticates for real against mock OTP, which is barely more effort and
 *       means the path exercised locally is the path that ships.</li>
 *   <li>Authorization is <b>never</b> just "is authenticated". Doc 03 §16
 *       requires resource scope: a valid permission does not grant access to
 *       another restaurant's outlet or another supplier's store. That check
 *       cannot be expressed in a URL matcher, so it lives in the services, and
 *       the rules here only establish who the caller is.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CostonomySecurityConfigurer costonomy) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // Sign-in is costonomy-common-api's (mp-mobile: phone + code). The starter verifies its tokens,
                // resolves MARKETPLACE access and receives common's webhooks; it is stateless with CSRF off.
                .with(costonomy, c -> { })
                .authorizeHttpRequests(auth -> auth
                        // Provider callbacks authenticate by signature, not by a
                        // bearer token — the provider has no user session. The
                        // handler verifies the signature before doing anything
                        // else (doc 09 §5); an unverified body is never trusted.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
                        // The realtime handshake authenticates by single-use
                        // ticket, not by a bearer token: a browser cannot set
                        // headers on a WebSocket, and a token in a query string
                        // ends up in access logs. RealtimeHandshakeInterceptor
                        // spends the ticket before the connection is accepted, so
                        // this path is unauthenticated only as far as Spring
                        // Security is concerned — see doc 09 §4.
                        .requestMatchers("/api/v1/realtime/socket").permitAll()
                        // Locally-stored uploads, standing in for a public bucket.
                        // The key carries a UUID, so the URL is the capability —
                        // the same property the bucket it replaces relies on. The
                        // bean, and so the controller, does not exist when storage
                        // is S3.
                        .requestMatchers(HttpMethod.GET, "/files/**").permitAll()
                        // A bill page from local storage: reachable only with a signed token that
                        // expires (D-113); the route checks the signature itself.
                        .requestMatchers(HttpMethod.GET, "/invoice-files/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));

        return http.build();
    }

    /**
     * 401s in the same envelope as everything else.
     *
     * <p>Spring Security rejects before the dispatcher runs, so
     * {@code GlobalExceptionHandler} never sees these. Without this the mobile
     * client would get Spring's default HTML error page for an expired token and
     * its central error mapping (§23A.51) would have nothing to parse.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, ex) -> writeError(response, ErrorCode.UNAUTHENTICATED);
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> writeError(response, ErrorCode.FORBIDDEN);
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        var error = new ApiResponse.ApiError(code.name(), code.defaultMessage());
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(error, RequestContext.requestId()));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var configuration = new CorsConfiguration();
        // The clients are native mobile apps, which do not send an Origin and are
        // not subject to CORS. This exists for the Swagger UI and the future
        // operations website; it is permissive for now and should be narrowed to
        // known origins before that website ships.
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
