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
        assertThatCode(() -> ProductionProviderGuard.check(prod)).doesNotThrowAnyException();

        var local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> ProductionProviderGuard.check(local)).doesNotThrowAnyException();
    }
}
