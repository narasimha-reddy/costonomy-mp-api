package com.costonomy.mp.common;

import com.costonomy.mp.common.ratelimit.InMemoryRateLimiter;
import com.costonomy.mp.common.ratelimit.RateLimitPolicy;
import com.costonomy.mp.common.ratelimit.RateLimitPolicy.KeyBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The counting itself. Doc 09 §14. */
class RateLimiterTest {

    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new InMemoryRateLimiter();
    }

    private RateLimitPolicy policy(int limit) {
        return new RateLimitPolicy("test", limit, Duration.ofMinutes(1), KeyBy.IP);
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("the first requests pass and the next is refused")
        void limitIsEnforced() {
            var policy = policy(3);

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire("caller", policy).allowed())
                        .describedAs("request %d", i + 1).isTrue();
            }
            assertThat(limiter.tryAcquire("caller", policy).allowed()).isFalse();
        }

        @Test
        @DisplayName("a refusal says how long to wait")
        void refusalCarriesRetryAfter() {
            var policy = policy(1);
            limiter.tryAcquire("caller", policy);

            var refused = limiter.tryAcquire("caller", policy);
            assertThat(refused.allowed()).isFalse();
            // Without this a client can only guess — and a client that guesses
            // wrong retries immediately and makes the problem worse.
            assertThat(refused.retryAfterSeconds()).isPositive();
        }

        @Test
        @DisplayName("remaining counts down")
        void remainingIsReported() {
            var policy = policy(3);

            assertThat(limiter.tryAcquire("caller", policy).remaining()).isEqualTo(2);
            assertThat(limiter.tryAcquire("caller", policy).remaining()).isEqualTo(1);
            assertThat(limiter.tryAcquire("caller", policy).remaining()).isZero();
        }

        @Test
        @DisplayName("callers are counted separately")
        void callersDoNotShareABucket() {
            var policy = policy(1);

            assertThat(limiter.tryAcquire("one", policy).allowed()).isTrue();
            // The whole point of a key. One busy caller must not lock out everyone
            // else — which is also why authenticated endpoints key by user rather
            // than by the office router's IP.
            assertThat(limiter.tryAcquire("two", policy).allowed()).isTrue();
            assertThat(limiter.tryAcquire("one", policy).allowed()).isFalse();
        }

        @Test
        @DisplayName("policies are counted separately")
        void policiesDoNotShareABucket() {
            var otp = new RateLimitPolicy("otp", 1, Duration.ofMinutes(1), KeyBy.IP);
            var search = new RateLimitPolicy("search", 1, Duration.ofMinutes(1), KeyBy.IP);

            assertThat(limiter.tryAcquire("caller", otp).allowed()).isTrue();
            // A provider's webhook burst and a script guessing OTPs look identical
            // to one global counter, which is why each endpoint gets its own.
            assertThat(limiter.tryAcquire("caller", search).allowed()).isTrue();
        }

        @Test
        @DisplayName("a window that has passed starts again")
        void windowsExpire() {
            var brief = new RateLimitPolicy("brief", 1, Duration.ofMillis(50), KeyBy.IP);

            assertThat(limiter.tryAcquire("caller", brief).allowed()).isTrue();
            assertThat(limiter.tryAcquire("caller", brief).allowed()).isFalse();

            await(80);

            assertThat(limiter.tryAcquire("caller", brief).allowed())
                    .describedAs("a new window")
                    .isTrue();
        }

        @Test
        @DisplayName("a zero limit lets everything through")
        void zeroMeansOff() {
            // How the test profile keeps the suite from throttling its own
            // fixtures, and how a limit is turned off in production without a
            // deploy.
            var off = new RateLimitPolicy("off", 0, Duration.ofMinutes(1), KeyBy.IP);

            for (int i = 0; i < 100; i++) {
                assertThat(limiter.tryAcquire("caller", off).allowed()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("under concurrency")
    class Concurrency {

        @Test
        @DisplayName("exactly the limit gets through, however many arrive at once")
        void concurrentCallersCannotOvershoot() throws Exception {
            var policy = policy(10);
            int callers = 50;

            var start = new CountDownLatch(1);
            var done = new CountDownLatch(callers);
            var allowed = new AtomicInteger();
            var pool = Executors.newFixedThreadPool(16);

            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquire("caller", policy).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();

            // A read-then-increment would let several threads past the check at
            // once, which is exactly the burst a limiter exists to stop.
            assertThat(allowed.get()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("expired windows are evicted")
        void expiredWindowsAreDropped() {
            var brief = new RateLimitPolicy("brief", 1, Duration.ofMillis(20), KeyBy.IP);
            for (int i = 0; i < 100; i++) {
                limiter.tryAcquire("caller-" + i, brief);
            }

            await(40);
            limiter.evictExpired();

            // The keys include client IPs, so without eviction the map grows
            // without bound. A rate limiter that exhausts memory has not helped.
            assertThat(limiter.tryAcquire("caller-0", brief).allowed()).isTrue();
        }
    }

    private void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
