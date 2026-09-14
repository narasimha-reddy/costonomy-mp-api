package com.costonomy.mp.identity.security;

import com.costonomy.mp.identity.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Establishes the caller's identity from the {@code Authorization: Bearer} header.
 *
 * <p>Identity only — it grants no authorities. A request that reaches a service
 * is merely known to come from a specific user; whether that user may act on a
 * particular outlet, store or order is decided by the service against live state
 * (doc 03 §16).
 *
 * <p>Note what this filter does <b>not</b> do: it never rejects. A missing or
 * invalid token simply leaves the context unauthenticated, and Spring Security's
 * authorisation rules decide what that means for the endpoint. Rejecting here
 * would break the public endpoints (OTP request, webhooks) that must work without
 * a token.
 *
 * <p>There is also no "authentication disabled" mode, unlike {@code costonomy-api}.
 * See {@code SecurityConfig} for why.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        jwtService.parse(header.substring(PREFIX.length()))
                .ifPresent(claims -> {
                    Long userId = Long.valueOf(claims.getSubject());
                    var actor = new AuthenticatedActor(userId, claims.get("phone", String.class));

                    // No authorities: a bare authenticated identity. Method-level
                    // @PreAuthorize is deliberately not used for business
                    // permissions, because they are scope-dependent.
                    var authentication = new UsernamePasswordAuthenticationToken(
                            actor, null, List.of());
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });

        chain.doFilter(request, response);
    }
}
