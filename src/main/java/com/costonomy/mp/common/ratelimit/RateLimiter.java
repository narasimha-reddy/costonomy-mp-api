package com.costonomy.mp.common.ratelimit;

/**
 * Decides whether one more request is allowed. Doc 09 §14.
 *
 * <p>A port because the answer depends on how the application is deployed: one
 * instance can count in memory, several have to share a counter or each would
 * allow the whole limit — a three-instance deployment with a "10 per hour" policy
 * would let through thirty. Redis is where that count belongs, and guardrail 6
 * names rate limiting as one of the things it is for.
 */
public interface RateLimiter {

    /**
     * Take one request's worth of allowance.
     *
     * @param key    the caller, already scoped to the policy
     * @param policy what they are doing and how often they may
     */
    Decision tryAcquire(String key, RateLimitPolicy policy);

    /**
     * @param allowed          whether to serve the request
     * @param remaining        allowance left in this window, for the response header
     * @param retryAfterSeconds how long until the window frees up. Only meaningful
     *                          when refused, and the reason a 429 is actionable
     *                          rather than just discouraging.
     */
    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {

        public static Decision allowed(int remaining) {
            return new Decision(true, remaining, 0);
        }

        public static Decision refused(long retryAfterSeconds) {
            return new Decision(false, 0, Math.max(1, retryAfterSeconds));
        }
    }
}
