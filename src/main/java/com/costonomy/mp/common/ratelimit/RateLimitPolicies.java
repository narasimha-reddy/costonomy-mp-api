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
