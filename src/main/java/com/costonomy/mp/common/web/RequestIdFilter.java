package com.costonomy.mp.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request a correlation ID and puts it where logs and audits can
 * find it (doc 09 §15).
 *
 * <p>Runs first in the chain so that even an authentication failure is logged
 * against an ID the user can quote to support.
 *
 * <p>An inbound {@code X-Request-Id} is honoured so a mobile client's ID can be
 * followed end to end — but it is length-capped and stripped of anything outside
 * a safe character set first. The value reaches log files and the audit table,
 * and an unsanitised header is a log-injection vector (a newline lets a caller
 * forge log lines).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        String requestId = sanitize(request.getHeader(HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        RequestContext.setRequestId(requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused. Not clearing would leak this
            // request's ID onto whichever request lands on the thread next.
            MDC.remove(MDC_KEY);
            RequestContext.clear();
        }
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.length() > MAX_LENGTH ? raw.substring(0, MAX_LENGTH) : raw;
        String cleaned = trimmed.replaceAll("[^A-Za-z0-9._-]", "");
        return cleaned.isBlank() ? null : cleaned;
    }
}
