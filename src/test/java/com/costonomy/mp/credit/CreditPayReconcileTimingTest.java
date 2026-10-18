package com.costonomy.mp.credit;

import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import com.costonomy.mp.credit.service.CreditPayReconcileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure timing rules of the credit payment poller (D-199). */
class CreditPayReconcileTimingTest {

    private static final Duration EXPIRE = Duration.ofMinutes(30);
    private static final Duration STUCK = Duration.ofMinutes(15);

    @Test
    @DisplayName("an unpaid CREATED attempt expires only after the window")
    void expiresAfterWindow() {
        assertThat(CreditPayReconcileService.shouldExpire(CreditPaymentAttemptStatus.CREATED,
                Duration.ofMinutes(30), EXPIRE)).isFalse();
        assertThat(CreditPayReconcileService.shouldExpire(CreditPaymentAttemptStatus.CREATED,
                Duration.ofMinutes(31), EXPIRE)).isTrue();
    }

    @Test
    @DisplayName("an attempt that is not CREATED never expires")
    void onlyCreatedExpires() {
        for (var status : new CreditPaymentAttemptStatus[]{CreditPaymentAttemptStatus.PAID,
                CreditPaymentAttemptStatus.REFUNDED, CreditPaymentAttemptStatus.EXPIRED,
                CreditPaymentAttemptStatus.FAILED}) {
            assertThat(CreditPayReconcileService.shouldExpire(status, Duration.ofHours(2), EXPIRE)).isFalse();
        }
    }

    @Test
    @DisplayName("the stuck alert fires once, and only after the grace period")
    void stuckAlertOnce() {
        assertThat(CreditPayReconcileService.shouldAlertStuck(null, Duration.ofMinutes(15), STUCK)).isFalse();
        assertThat(CreditPayReconcileService.shouldAlertStuck(null, Duration.ofMinutes(16), STUCK)).isTrue();
        assertThat(CreditPayReconcileService.shouldAlertStuck(java.time.Instant.now(), Duration.ofMinutes(60), STUCK))
                .isFalse();
    }
}
