package com.costonomy.mp.common.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts in this JVM. The default, and correct for a single instance.
 *
 * <p>A <b>fixed window</b> rather than a sliding one: it is one counter and one
 * timestamp per key, it cannot drift, and its worst case is well understood — a
 * caller can send two windows' worth across a window boundary. For protecting an
 * OTP endpoint from a script that is the difference between ten and twenty
 * attempts an hour, which is not the difference that matters. A sliding window
 * costs a data structure per key to close a gap this small.
 *
 * <p><b>Switch to Redis when running more than one instance.</b> Each instance
 * counting separately means a three-instance deployment enforces three times the
 * limit, silently.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "costonomy.mp.ratelimit.backend", havingValue = "MEMORY",
        matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public Decision tryAcquire(String key, RateLimitPolicy policy) {
        if (policy.unlimited()) {
            return Decision.allowed(Integer.MAX_VALUE);
        }

        Instant now = Instant.now();
        var window = windows.compute(policy.name() + "|" + key, (ignored, existing) -> {
            if (existing == null || existing.expiresAt.isBefore(now)) {
                return new Window(now.plus(policy.window()));
            }
            return existing;
        });

        int used = window.count.incrementAndGet();
        if (used > policy.limit()) {
            long retryAfter = Duration.between(now, window.expiresAt).getSeconds();
            return Decision.refused(retryAfter);
        }
        return Decision.allowed(policy.limit() - used);
    }

    /**
     * Drop expired windows.
     *
     * <p>Without this the map grows by one entry per distinct caller for the life
     * of the process — and the keys include client IPs, so "distinct callers" is
     * unbounded. A rate limiter that runs the machine out of memory has not helped.
     */
    @Scheduled(fixedDelayString = "${costonomy.mp.ratelimit.cleanup-interval:PT5M}")
    public void evictExpired() {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(now));
    }

    /** Test seam: forget everything, so one test's traffic cannot fail the next. */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        private final Instant expiresAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }
    }
}
