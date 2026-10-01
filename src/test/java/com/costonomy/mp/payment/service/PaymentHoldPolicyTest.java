package com.costonomy.mp.payment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One setting for how long a provider holds an authorisation, and what we allow against it (D-109). */
class PaymentHoldPolicyTest {

    @Test
    @DisplayName("three days is usable for sixty-six hours: the limit less six")
    void threeDays() {
        var policy = new PaymentHoldPolicy(4320);

        assertThat(policy.holdLimit()).isEqualTo(Duration.ofHours(72));
        assertThat(policy.usableFor()).isEqualTo(Duration.ofHours(66));
        assertThat(policy.warnAfter()).isEqualTo(Duration.ofHours(48));
    }

    @Test
    @DisplayName("five days is usable for 114 hours, and warns at four days, as before")
    void fiveDays() {
        var policy = new PaymentHoldPolicy(7200);

        assertThat(policy.usableFor()).isEqualTo(Duration.ofHours(114));
        assertThat(policy.warnAfter()).isEqualTo(Duration.ofDays(4));
    }

    @Test
    @DisplayName("a twelve-minute test hold keeps a margin of a quarter, so it still has a usable window")
    void shortHold() {
        var policy = new PaymentHoldPolicy(12);

        assertThat(policy.usableFor()).isEqualTo(Duration.ofMinutes(9));
        assertThat(policy.warnAfter()).isLessThanOrEqualTo(policy.usableFor()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("the warning never comes after the point the supplier is refused")
    void warningPrecedesRefusal() {
        for (int minutes : new int[] {12, 60, 360, 1440, 4320, 7200}) {
            var policy = new PaymentHoldPolicy(minutes);
            assertThat(policy.warnAfter()).describedAs(minutes + " min")
                    .isLessThanOrEqualTo(policy.usableFor());
            assertThat(policy.usableFor()).isLessThan(policy.holdLimit());
        }
    }

    @Test
    @DisplayName("a limit outside Razorpay's range is refused at start-up")
    void outsideTheRangeIsRefused() {
        assertThatThrownBy(() -> new PaymentHoldPolicy(11)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manual-expiry-minutes");
        assertThatThrownBy(() -> new PaymentHoldPolicy(7201)).isInstanceOf(IllegalStateException.class);
    }

    private static com.costonomy.mp.payment.domain.Payment paymentHeld(Integer minutes) {
        var payment = new com.costonomy.mp.payment.domain.Payment();
        payment.setHoldMinutes(minutes);
        return payment;
    }

    @Test
    @DisplayName("F3: a payment's own stored hold is what limits it, whatever the setting says now")
    void storedHoldWinsOverTheSetting() {
        var raised = new PaymentHoldPolicy(7200);
        var payment = paymentHeld(4320);

        assertThat(raised.holdLimit()).isEqualTo(Duration.ofDays(5));
        assertThat(raised.holdLimit(payment)).isEqualTo(Duration.ofHours(72));
        assertThat(raised.usableFor(payment)).isEqualTo(Duration.ofHours(66));
        assertThat(raised.warnAfter(payment)).isEqualTo(Duration.ofHours(48));

        var lowered = new PaymentHoldPolicy(4320);
        var longer = paymentHeld(7200);
        assertThat(lowered.usableFor(longer)).isEqualTo(Duration.ofHours(114));
    }

    @Test
    @DisplayName("F3: a payment with no stored hold, or a nonsense one, falls back to the current setting")
    void noStoredHoldFallsBack() {
        var policy = new PaymentHoldPolicy(4320);

        assertThat(policy.holdLimit(paymentHeld(null))).isEqualTo(Duration.ofHours(72));
        assertThat(policy.holdLimit(paymentHeld(0))).isEqualTo(Duration.ofHours(72));
        assertThat(policy.usableFor(paymentHeld(null))).isEqualTo(policy.usableFor());
        assertThat(policy.warnAfter(paymentHeld(null))).isEqualTo(policy.warnAfter());
    }
}
