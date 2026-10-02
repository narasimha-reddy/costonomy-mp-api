package com.costonomy.mp.payment.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Remembers when each alert last went to the log, so a job that runs every fifteen seconds
 * writes a standing condition once per interval instead of 240 times an hour (F7, N5).
 *
 * <p>One shared instance, because the same outage is seen from three places (the cancellation
 * lookup, the refund send and the jobs) and must be one line, not three. In memory: an alert is
 * a reminder, and after a restart one more line is right.
 */
@Component
public class AlertThrottle {

    private final Map<String, Instant> last = new ConcurrentHashMap<>();

    /** True, and remembered, if this alert has not been written within {@code every}. */
    public boolean due(String key, Instant now, Duration every) {
        boolean[] due = {false};
        last.compute(key, (k, before) -> {
            if (before == null || before.isBefore(now.minus(every))) {
                due[0] = true;
                return now;
            }
            return before;
        });
        return due[0];
    }

    /** Forget an alert, so it is written again the next time it applies. */
    public void forget(String key) {
        last.remove(key);
    }

    /** Forget every alert whose key matches. */
    public void forgetIf(Predicate<String> keys) {
        last.keySet().removeIf(keys);
    }

    /** Forget everything; for tests that share one application context. */
    public void clear() {
        last.clear();
    }
}
