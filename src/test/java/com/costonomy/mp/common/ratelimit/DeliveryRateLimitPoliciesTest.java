package com.costonomy.mp.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the right policy matches the right path/method combination.
 *
 * <p>Uses plain JUnit with {@link ReflectionTestUtils} to inject limit values
 * directly — no Spring context needed because {@link RateLimitPolicies} has no
 * Spring dependencies beyond {@code @Value}. This keeps the test fast and
 * independent of the database.
 *
 * <p>The most important thing to test here is the <em>routing</em>: that a
 * POST to a delivery endpoint doesn't silently fall through to an unrelated
 * policy (or no policy). Getting a path prefix wrong is a silent bug — the
 * limit still applies, just to the wrong endpoint.
 */
class DeliveryRateLimitPoliciesTest {

    private RateLimitPolicies policies;

    @BeforeEach
    void setUp() {
        policies = new RateLimitPolicies();
        // Inject production-like values via ReflectionTestUtils to simulate
        // what @Value would provide from application.properties.
        ReflectionTestUtils.setField(policies, "otpRequestLimit", 20);
        ReflectionTestUtils.setField(policies, "otpVerifyLimit", 30);
        ReflectionTestUtils.setField(policies, "authLimit", 60);
        ReflectionTestUtils.setField(policies, "searchLimit", 120);
        ReflectionTestUtils.setField(policies, "paymentLimit", 60);
        ReflectionTestUtils.setField(policies, "webhookLimit", 600);
        ReflectionTestUtils.setField(policies, "adminMutationLimit", 120);
        ReflectionTestUtils.setField(policies, "deliveryRequestLimit", 10);
        ReflectionTestUtils.setField(policies, "deliveryReassignLimit", 5);
        ReflectionTestUtils.setField(policies, "deliveryCancelLimit", 5);
        ReflectionTestUtils.setField(policies, "deliveryReadLimit", 60);
    }

    // -----------------------------------------------------------------------
    // Delivery request: POST /api/v1/supplier-orders/{id}/delivery
    // -----------------------------------------------------------------------

    @Test
    void deliveryRequest_matchesCorrectPolicy() {
        var policy = policies.policyFor("POST", "/api/v1/supplier-orders/42/delivery");
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("delivery-request");
        assertThat(policy.limit()).isEqualTo(10);
        assertThat(policy.keyBy()).isEqualTo(RateLimitPolicy.KeyBy.USER);
    }

    // -----------------------------------------------------------------------
    // Delivery mutations on existing deliveries (reassign, cancel, dispatched,
    // delivered) — all POST under /api/v1/deliveries/
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "POST {0} → delivery-reassign policy")
    @CsvSource({
            "/api/v1/deliveries/99/reassign",
            "/api/v1/deliveries/99/cancel",
            "/api/v1/deliveries/99/dispatched",
            "/api/v1/deliveries/99/delivered"
    })
    void deliveryMutations_allMatchReassignPolicy(String path) {
        var policy = policies.policyFor("POST", path);
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("delivery-reassign");
        assertThat(policy.limit()).isEqualTo(5);
        assertThat(policy.keyBy()).isEqualTo(RateLimitPolicy.KeyBy.USER);
    }

    // -----------------------------------------------------------------------
    // Delivery reads: GET /api/v1/deliveries/{id} and events
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "GET {0} → delivery-read policy")
    @CsvSource({
            "/api/v1/deliveries/99",
            "/api/v1/deliveries/99/events"
    })
    void deliveryReads_matchReadPolicy(String path) {
        var policy = policies.policyFor("GET", path);
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("delivery-read");
        assertThat(policy.limit()).isEqualTo(60);
    }

    @Test
    void getForOrderDelivery_hasNoSpecificLimit() {
        // GET /api/v1/supplier-orders/{id}/delivery is not under /api/v1/deliveries/
        // so it has no delivery-read policy — it falls through to no rule (null).
        // This is intentional: it's an order read, not a tracking poll.
        var policy = policies.policyFor("GET", "/api/v1/supplier-orders/42/delivery");
        assertThat(policy).isNull();
    }

    // -----------------------------------------------------------------------
    // Unrelated paths must not accidentally match delivery policies
    // -----------------------------------------------------------------------

    @Test
    void paymentPost_doesNotMatchDeliveryPolicy() {
        var policy = policies.policyFor("POST", "/api/v1/payments/initiate");
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("payment");
    }

    @Test
    void webhookPost_doesNotMatchDeliveryPolicy() {
        var policy = policies.policyFor("POST", "/api/v1/webhooks/delivery/pidge");
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("webhook");
    }

    @Test
    void adminPost_doesNotMatchDeliveryPolicy() {
        var policy = policies.policyFor("POST", "/api/v1/admin/deliveries/5/force-waterfall");
        assertThat(policy).isNotNull();
        assertThat(policy.name()).isEqualTo("admin-mutation");
    }

    // -----------------------------------------------------------------------
    // Limit sanity: zero → unlimited() must return true
    // -----------------------------------------------------------------------

    @Test
    void zeroLimit_isUnlimited() {
        ReflectionTestUtils.setField(policies, "deliveryRequestLimit", 0);
        ReflectionTestUtils.setField(policies, "rules", null); // force rebuild
        var policy = policies.policyFor("POST", "/api/v1/supplier-orders/1/delivery");
        assertThat(policy).isNotNull();
        assertThat(policy.unlimited())
                .as("Setting a limit to 0 must disable it (test profile behaviour)")
                .isTrue();
    }
}
