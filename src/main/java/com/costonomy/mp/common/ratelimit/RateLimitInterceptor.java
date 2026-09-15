package com.costonomy.mp.common.ratelimit;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.security.ActorContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the policies. Doc 09 §14.
 *
 * <p>An interceptor rather than a filter, so it runs <b>after</b> Spring Security
 * has established the principal — a user-keyed policy needs a user, and a filter
 * ahead of the security chain would only ever see an IP.
 *
 * <p>A refusal is a {@code 429} carrying {@code Retry-After}. Without that header
 * a client's only option is to guess, and a client that guesses wrong retries
 * immediately and makes the problem worse.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final RateLimitPolicies policies;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        var policy = policies.policyFor(request.getMethod(), request.getRequestURI());
        if (policy == null || policy.unlimited()) {
            return true;
        }

        var decision = limiter.tryAcquire(keyFor(request, policy), policy);

        response.setHeader("X-RateLimit-Limit", String.valueOf(policy.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            // Logged without the key: it is an IP or a user id, and doc 09 §16
            // keeps both out of routine logs.
            log.info("Rate limit {} exceeded", policy.name());
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    /**
     * Who is being counted.
     *
     * <p>A USER policy falls back to the IP when nobody is authenticated. That is
     * not a special case to tidy away: without it, an unauthenticated request to a
     * user-keyed endpoint would share one bucket with every other anonymous
     * caller, and one script could lock the endpoint for everyone.
     */
    private String keyFor(HttpServletRequest request, RateLimitPolicy policy) {
        if (policy.keyBy() == RateLimitPolicy.KeyBy.USER) {
            var actor = ActorContext.current();
            if (actor.isPresent()) {
                return "u:" + actor.get().userId();
            }
        }
        return "ip:" + clientIp(request);
    }

    /**
     * The caller's address.
     *
     * <p>{@code X-Forwarded-For} is honoured because in any real deployment there
     * is a proxy in front and {@code getRemoteAddr} would be the proxy — one key
     * for the entire internet. Only the first entry is taken: the rest are
     * appended by intermediaries and are trivially forged by the client.
     *
     * <p>This is worth stating plainly: <b>a forwarded header is only trustworthy
     * behind a proxy that overwrites it.</b> Deployed without one, a caller can
     * change their own rate-limit key at will. The limits here are one layer among
     * several — OTP attempt counts, idempotency and authorization do not depend on
     * this being unspoofable.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank() && first.length() <= 45) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
