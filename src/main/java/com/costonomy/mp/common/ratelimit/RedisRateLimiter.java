package com.costonomy.mp.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Counts in Redis, so every instance shares one window. Guardrail 6 names rate
 * limiting as one of the things Redis is for.
 *
 * <p>{@code INCR} then {@code EXPIRE} on first use: the increment is atomic, which
 * is the part that matters — two instances incrementing the same key cannot both
 * see the same value and both allow the request.
 *
 * <p><b>Redis being down does not close the door.</b> A failed count allows the
 * request and logs it. A rate limiter that refuses everything when its counter is
 * unreachable turns a cache outage into a total outage, which is a far worse
 * failure than briefly permitting more traffic than intended — and the thing being
 * protected has its own defences (OTP attempt limits, idempotency, authorization).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "costonomy.mp.ratelimit.backend", havingValue = "REDIS")
public class RedisRateLimiter implements RateLimiter {

    private static final String PREFIX = "mp:ratelimit:";

    private final StringRedisTemplate redis;

    @Override
    public Decision tryAcquire(String key, RateLimitPolicy policy) {
        if (policy.unlimited()) {
            return Decision.allowed(Integer.MAX_VALUE);
        }

        String redisKey = PREFIX + policy.name() + ":" + key;
        try {
            Long used = redis.opsForValue().increment(redisKey);
            if (used == null) {
                return Decision.allowed(policy.limit());
            }
            if (used == 1L) {
                // Only on first use, so the window is fixed from the first request
                // rather than extended by every one after it — an EXPIRE on every
                // increment would make a busy caller's window never end.
                redis.expire(redisKey, policy.window());
            }
            if (used > policy.limit()) {
                Long ttl = redis.getExpire(redisKey);
                return Decision.refused(ttl == null || ttl < 0
                        ? policy.window().getSeconds() : ttl);
            }
            return Decision.allowed((int) (policy.limit() - used));

        } catch (RuntimeException ex) {
            log.warn("Rate limit check failed, allowing the request: {}", ex.getMessage());
            return Decision.allowed(policy.limit());
        }
    }
}
