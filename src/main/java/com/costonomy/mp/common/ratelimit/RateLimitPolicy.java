package com.costonomy.mp.common.ratelimit;

import java.time.Duration;

/**
 * How often one caller may do one thing. Doc 09 §14.
 *
 * @param name     what is being limited, for logs and for configuration keys
 * @param limit    requests allowed in the window
 * @param window   the window
 * @param keyBy    what counts as "one caller" — see {@link KeyBy}
 */
public record RateLimitPolicy(
        String name,
        int limit,
        Duration window,
        KeyBy keyBy) {

    /**
     * What identifies a caller.
     *
     * <p>The choice is not cosmetic. Limiting an unauthenticated endpoint by user
     * is impossible (there is no user yet), and limiting an authenticated one by
     * IP punishes an entire restaurant behind one office router for one person's
     * enthusiasm.
     */
    public enum KeyBy {
        /** Before anyone is authenticated: OTP, login, provider webhooks. */
        IP,
        /** After authentication. Falls back to IP when there is no principal. */
        USER
    }

    /** Whether this policy is effectively off — the test profile relaxes them this way. */
    public boolean unlimited() {
        return limit <= 0;
    }
}
