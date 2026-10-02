package com.costonomy.mp.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** When a cancelled order's money that has not started back becomes an error someone sees (D-109). */
class CancellationAlertTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    @DisplayName("waiting fifteen minutes or less is normal; longer is an error")
    void fifteenMinutes() {
        assertThat(PaymentJobsAccess.cancellationOverdue(NOW.minus(Duration.ofMinutes(5)), NOW)).isFalse();
        assertThat(PaymentJobsAccess.cancellationOverdue(NOW.minus(Duration.ofMinutes(15)), NOW)).isFalse();
        assertThat(PaymentJobsAccess.cancellationOverdue(NOW.minus(Duration.ofMinutes(15)).minusSeconds(1), NOW)).isTrue();
        assertThat(PaymentJobsAccess.cancellationOverdue(NOW.minus(Duration.ofHours(3)), NOW)).isTrue();
    }
}
