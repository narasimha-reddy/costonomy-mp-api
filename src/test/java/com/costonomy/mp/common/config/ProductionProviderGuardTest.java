package com.costonomy.mp.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-101: a production profile must not come up on a mock payment or OTP provider. */
class ProductionProviderGuardTest {

    @Test
    @DisplayName("production with a provider left at its MOCK default refuses to start")
    void productionOnMockIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        // payment not set: the MOCK default is exactly the forgotten-setting case.
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.providers.payment");
    }

    @Test
    @DisplayName("production on real providers starts, and local on mocks starts")
    void otherwiseStarts() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("production");
        prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        prod.setProperty("costonomy.mp.providers.otp", "MSG91");
        prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        assertThatCode(() -> ProductionProviderGuard.check(prod)).doesNotThrowAnyException();

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-110: the switch that skips the check before a withdrawal is refused under production, and fine locally")
    void withdrawalPrecheckCannotBeSwitchedOffInProduction() {
        var prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        prod.setProperty("costonomy.mp.providers.otp", "MSG91");
        prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        prod.setProperty("costonomy.mp.wallet.withdraw-precheck", "false");
        assertThatThrownBy(() -> ProductionProviderGuard.check(prod))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withdraw-precheck");

        prod.setProperty("costonomy.mp.wallet.withdraw-precheck", "true");
        assertThatCode(() -> ProductionProviderGuard.check(prod)).doesNotThrowAnyException();

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        local.setProperty("costonomy.mp.wallet.withdraw-precheck", "false");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D-110: production refuses any explicit value of the pre-check switch but true, including the ones Spring reads as false")
    void withdrawalPrecheckOnlyTrueOrUnsetInProduction() {
        // Spring binds off, no and 0 (and a blank) to false for a boolean property: a guard that only looked for
        // the word "false" let each of them switch the check off in production unnoticed.
        for (String value : new String[]{"false", "FALSE", "off", "no", "0", "n", "", " ", "disabled", "yes", "1"}) {
            var prod = new MockEnvironment();
            prod.setActiveProfiles("prod");
            prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
            prod.setProperty("costonomy.mp.providers.otp", "MSG91");
            prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
            prod.setProperty("costonomy.mp.wallet.withdraw-precheck", value);
            assertThatThrownBy(() -> ProductionProviderGuard.check(prod))
                    .describedAs("value '%s'", value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("withdraw-precheck");
        }
        for (String value : new String[]{"true", "TRUE", " true "}) {
            var prod = new MockEnvironment();
            prod.setActiveProfiles("production");
            prod.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
            prod.setProperty("costonomy.mp.providers.otp", "MSG91");
            prod.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
            prod.setProperty("costonomy.mp.wallet.withdraw-precheck", value);
            assertThatCode(() -> ProductionProviderGuard.check(prod)).describedAs("value '%s'", value).doesNotThrowAnyException();
        }
        var unset = new MockEnvironment();
        unset.setActiveProfiles("prod");
        unset.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        unset.setProperty("costonomy.mp.providers.otp", "MSG91");
        unset.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        assertThatCode(() -> ProductionProviderGuard.check(unset)).doesNotThrowAnyException();
    }

    @DisplayName("production with the payout provider left at its MOCK default refuses to start")
    void productionOnMockPayoutIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        // payout not set: the MOCK default is exactly the forgotten-setting case.
        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.providers.payout");
    }

    @Test
    @DisplayName("D-106: production refuses to start with QuickScan enabled, needs legal sign-off first")
    void productionWithQuickScanEnabledIsRefused() {
        var env = new MockEnvironment();
        env.setActiveProfiles("production");
        env.setProperty("costonomy.mp.providers.payment", "RAZORPAY");
        env.setProperty("costonomy.mp.providers.otp", "MSG91");
        env.setProperty("costonomy.mp.providers.payout", "RAZORPAYX");
        env.setProperty("costonomy.mp.quickscan.enabled", "true");

        assertThatThrownBy(() -> ProductionProviderGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costonomy.mp.quickscan.enabled");

        // Disabled (the default), production starts as before.
        env.setProperty("costonomy.mp.quickscan.enabled", "false");
        assertThatCode(() -> ProductionProviderGuard.check(env)).doesNotThrowAnyException();
    }
}
