package com.costonomy.mp.common;

import com.costonomy.mp.common.ratelimit.InMemoryRateLimiter;
import com.costonomy.mp.support.AbstractIntegrationTest;
import com.costonomy.mp.support.ApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limiting, over HTTP. Doc 09 §14.
 *
 * <p><b>Runs with its own limits.</b> The test profile turns them off, because the
 * suite creates hundreds of users from one address and throttling its own fixtures
 * would be testing the fixtures. {@code @TestPropertySource} turns them back on
 * here — at the cost of a second application context, which is the price of
 * testing a cross-cutting concern honestly rather than around it.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "costonomy.mp.ratelimit.otp-request=3",
        "costonomy.mp.ratelimit.otp-verify=3",
        "costonomy.mp.ratelimit.auth=50",
        "costonomy.mp.ratelimit.search=4"
})
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private InMemoryRateLimiter limiter;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, json);
        // One test's traffic must not fail the next. The limiter is a singleton
        // across the context, so this is not optional.
        limiter.reset();
    }

    private MvcResult requestOtp(String phone, String forwardedFor) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/otp/request")
                        .header("X-Forwarded-For", forwardedFor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("phone", phone, "purpose", "LOGIN"))))
                .andReturn();
    }

    @Nested
    @DisplayName("throttling")
    class Throttling {

        @Test
        @DisplayName("the limit is enforced and the refusal is a 429")
        void tooManyRequestsIsRefused() throws Exception {
            for (int i = 0; i < 3; i++) {
                assertThat(requestOtp(ApiClient.freshPhone(), "203.0.113.10")
                        .getResponse().getStatus())
                        .describedAs("request %d", i + 1).isEqualTo(200);
            }

            var refused = requestOtp(ApiClient.freshPhone(), "203.0.113.10").getResponse();

            // Doc 04 §22's HTTP guidance: 429 throttled.
            assertThat(refused.getStatus()).isEqualTo(429);
            assertThat(refused.getContentAsString()).contains("RATE_LIMITED");
        }

        @Test
        @DisplayName("a refusal says when to come back")
        void retryAfterIsSet() throws Exception {
            for (int i = 0; i < 3; i++) {
                requestOtp(ApiClient.freshPhone(), "203.0.113.20");
            }

            var refused = requestOtp(ApiClient.freshPhone(), "203.0.113.20").getResponse();

            // A client with no Retry-After can only guess, and a client that
            // guesses wrong retries immediately and makes it worse.
            assertThat(refused.getHeader("Retry-After")).isNotNull();
            assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isPositive();
        }

        @Test
        @DisplayName("remaining allowance is reported before it runs out")
        void remainingHeaderCountsDown() throws Exception {
            var first = requestOtp(ApiClient.freshPhone(), "203.0.113.30").getResponse();

            assertThat(first.getHeader("X-RateLimit-Limit")).isEqualTo("3");
            assertThat(first.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
        }

        @Test
        @DisplayName("one caller's limit does not affect another's")
        void limitsArePerCaller() throws Exception {
            for (int i = 0; i < 3; i++) {
                requestOtp(ApiClient.freshPhone(), "203.0.113.40");
            }
            assertThat(requestOtp(ApiClient.freshPhone(), "203.0.113.40")
                    .getResponse().getStatus()).isEqualTo(429);

            // A shared bucket would mean one script locking every restaurant out
            // of signing in.
            assertThat(requestOtp(ApiClient.freshPhone(), "203.0.113.41")
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("endpoints are limited separately")
        void policiesAreIndependent() throws Exception {
            for (int i = 0; i < 3; i++) {
                requestOtp(ApiClient.freshPhone(), "203.0.113.50");
            }
            assertThat(requestOtp(ApiClient.freshPhone(), "203.0.113.50")
                    .getResponse().getStatus()).isEqualTo(429);

            // OTP verification has its own budget: exhausting the request endpoint
            // must not lock out someone who already has a code in their hand.
            int verifyStatus = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/auth/otp/verify")
                            .header("X-Forwarded-For", "203.0.113.50")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "phone", ApiClient.freshPhone(),
                                    "otp", "123456", "purpose", "LOGIN"))))
                    .andReturn().getResponse().getStatus();

            assertThat(verifyStatus).isNotEqualTo(429);
        }

        @Test
        @DisplayName("an unlimited endpoint is not throttled")
        void unlimitedEndpointsPassThrough() throws Exception {
            // Every limit is configurable and zero means off — payment is zero in
            // the base test profile and this class does not override it.
            for (int i = 0; i < 10; i++) {
                int status = mvc.perform(MockMvcRequestBuilders.get("/api/v1/notifications")
                                .header("X-Forwarded-For", "203.0.113.60"))
                        .andReturn().getResponse().getStatus();
                assertThat(status).isNotEqualTo(429);
            }
        }
    }

    @Nested
    @DisplayName("authenticated endpoints")
    class PerUser {

        @Test
        @DisplayName("search is counted per user, not per address")
        void searchIsCountedPerUser() throws Exception {
            String one = api.loginFresh();
            String two = api.loginFresh();

            for (int i = 0; i < 4; i++) {
                assertThat(api.getStatus(one, "/api/v1/search/products?query=paneer"))
                        .describedAs("request %d", i + 1).isNotEqualTo(429);
            }
            assertThat(api.getStatus(one, "/api/v1/search/products?query=paneer"))
                    .isEqualTo(429);

            // Same address, different user. Keying an authenticated endpoint by IP
            // would throttle an entire restaurant for one person's enthusiasm.
            assertThat(api.getStatus(two, "/api/v1/search/products?query=paneer"))
                    .isNotEqualTo(429);
        }
    }
}
