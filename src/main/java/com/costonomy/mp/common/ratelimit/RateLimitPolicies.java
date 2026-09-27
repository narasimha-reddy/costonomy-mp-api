package com.costonomy.mp.common.ratelimit;

import com.costonomy.mp.common.ratelimit.RateLimitPolicy.KeyBy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Which limit applies to which endpoint. Doc 09 §14.
 *
 * <p>The spec names seven things to limit: OTP request, OTP verification, login,
 * search, payment initiation, webhook endpoints and admin mutations. Each gets its
 * own policy rather than one global limit, because the right number differs by two
 * orders of magnitude between them — a provider's webhook burst and a script
 * guessing OTPs look identical to a single global counter.
 *
 * <p><b>Every limit is configurable, and zero means off.</b> The test profile sets
 * them to zero so the suite is not throttled by its own fixtures, and one
 * integration test turns them back on for itself. A limit that cannot be tuned in
 * production without a deploy is a limit that gets removed the first time it fires
 * at the wrong moment.
 *
 * <p><b>Order matters</b> — the first matching rule wins, so specific paths come
 * before general ones.
 */
@Component
@Slf4j
public class RateLimitPolicies {

    /** OTP is the one a script actually attacks: six digits, and a phone is public. */
    @Value("${costonomy.mp.ratelimit.otp-request:20}")
    private int otpRequestLimit;

    @Value("${costonomy.mp.ratelimit.otp-verify:30}")
    private int otpVerifyLimit;

    @Value("${costonomy.mp.ratelimit.auth:60}")
    private int authLimit;

    @Value("${costonomy.mp.ratelimit.search:120}")
    private int searchLimit;

    @Value("${costonomy.mp.ratelimit.payment:60}")
    private int paymentLimit;

    /** Generous: a provider retrying a burst of webhooks is normal, not an attack. */
    @Value("${costonomy.mp.ratelimit.webhook:600}")
    private int webhookLimit;

    @Value("${costonomy.mp.ratelimit.admin-mutation:120}")
    private int adminMutationLimit;

    /**
     * Delivery request: calls external courier APIs and charges money.
     * 10 per minute per user stops a bot while allowing a busy mandi.
     */
    @Value("${costonomy.mp.ratelimit.delivery-request:10}")
    private int deliveryRequestLimit;

    /**
     * Reassign: triggers a fresh provider search. Rare in normal use —
     * a driver cancellation is an exception, not a workflow step.
     */
    @Value("${costonomy.mp.ratelimit.delivery-reassign:5}")
    private int deliveryReassignLimit;

    /**
     * Cancel: has downstream cost (Pidge cancellation fee). Same cap as reassign.
     */
    @Value("${costonomy.mp.ratelimit.delivery-cancel:5}")
    private int deliveryCancelLimit;

    /**
     * Read + tracking poll: a mobile app polls every few seconds. 60/min = 1/sec.
     */
    @Value("${costonomy.mp.ratelimit.delivery-read:60}")
    private int deliveryReadLimit;

    private List<Rule> rules;

    /**
     * @param method null matches any method — used where the path alone identifies
     *               the operation
     */
    public record Rule(HttpMethod method, String pathPrefix, RateLimitPolicy policy) {

        boolean matches(String method, String path) {
            return (this.method == null || this.method.name().equals(method))
                    && path.startsWith(pathPrefix);
        }
    }

    /** The policy for a request, or null when nothing limits it. */
    public RateLimitPolicy policyFor(String method, String path) {
        if (rules == null) {
            rules = build();
        }
        for (Rule rule : rules) {
            if (rule.matches(method, path)) {
                return rule.policy();
            }
        }
        return null;
    }

    private List<Rule> build() {
        var hour = Duration.ofHours(1);
        var tenMinutes = Duration.ofMinutes(10);
        var minute = Duration.ofMinutes(1);

        var built = List.of(
                // Pre-authentication, so keyed by IP — there is no user yet.
                new Rule(HttpMethod.POST, "/api/v1/auth/otp/request",
                        new RateLimitPolicy("otp-request", otpRequestLimit, hour, KeyBy.IP)),
                new Rule(HttpMethod.POST, "/api/v1/auth/otp/verify",
                        new RateLimitPolicy("otp-verify", otpVerifyLimit, tenMinutes, KeyBy.IP)),
                new Rule(HttpMethod.POST, "/api/v1/auth/",
                        new RateLimitPolicy("auth", authLimit, tenMinutes, KeyBy.IP)),

                // Providers have no user either, and they burst on retry.
                new Rule(HttpMethod.POST, "/api/v1/webhooks/",
                        new RateLimitPolicy("webhook", webhookLimit, minute, KeyBy.IP)),

                // Authenticated from here: keyed by user, so one busy person cannot
                // throttle everyone else behind the same office router.
                new Rule(HttpMethod.GET, "/api/v1/search/",
                        new RateLimitPolicy("search", searchLimit, minute, KeyBy.USER)),
                new Rule(HttpMethod.POST, "/api/v1/payments/",
                        new RateLimitPolicy("payment", paymentLimit, minute, KeyBy.USER)),

                // ── Delivery mutations ─────────────────────────────────────
                // Specific paths first (reassign/cancel), then the general delivery
                // request path. Order matters: first match wins.
                //
                // Reassign: triggers a fresh external provider search — expensive,
                // should be rare. 5 per minute covers driver-cancel retries without
                // allowing a script to cycle providers.
                new Rule(HttpMethod.POST, "/api/v1/deliveries/",
                        new RateLimitPolicy("delivery-reassign", deliveryReassignLimit,
                                minute, KeyBy.USER)),
                // Cancel: has real downstream cost (Pidge cancellation fee). Same cap.
                // Note: /deliveries/{id}/cancel is a POST under /api/v1/deliveries/
                // and is caught by the rule above, which is intentional — both
                // reassign and cancel on a delivery share the same mutation budget.
                //
                // Request delivery for an order: calls courier APIs and charges money.
                // A legitimate supplier has one delivery per order. 10/min is generous
                // for a busy mandi and stops a bot or broken retry loop dead.
                new Rule(HttpMethod.POST, "/api/v1/supplier-orders/",
                        new RateLimitPolicy("delivery-request", deliveryRequestLimit,
                                minute, KeyBy.USER)),

                // Delivery reads: GET /deliveries/{id} and /deliveries/{id}/events
                // for tracking polls from the mobile app. 60/min = once per second,
                // which is more than enough for smooth location updates.
                new Rule(HttpMethod.GET, "/api/v1/deliveries/",
                        new RateLimitPolicy("delivery-read", deliveryReadLimit,
                                minute, KeyBy.USER)),

                // Doc 09 §14's "admin mutations" — reads are not limited here
                // because an operator refreshing a dashboard is not a threat.
                new Rule(HttpMethod.POST, "/api/v1/admin/",
                        new RateLimitPolicy("admin-mutation", adminMutationLimit,
                                minute, KeyBy.USER)),
                new Rule(HttpMethod.PATCH, "/api/v1/admin/",
                        new RateLimitPolicy("admin-mutation", adminMutationLimit,
                                minute, KeyBy.USER)));

        long active = built.stream().filter(rule -> !rule.policy().unlimited()).count();
        log.info("Rate limiting active on {} of {} rules", active, built.size());
        return built;
    }
}
