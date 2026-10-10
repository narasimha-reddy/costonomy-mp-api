package com.costonomy.mp.intent.service;

import com.costonomy.mp.common.config.AppConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeliveryChargeWarningTest {

    private DeliveryChargeWarning warning(String percent, String floor) {
        var config = mock(AppConfigService.class);
        when(config.getDecimal(eq("delivery.highCharge.percent"), any())).thenReturn(new BigDecimal(percent));
        when(config.getDecimal(eq("delivery.highCharge.minAmount"), any())).thenReturn(new BigDecimal(floor));
        return new DeliveryChargeWarning(config);
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("high needs both a share of the goods and a floor: small orders are not flagged for a normal charge")
    void bothTests() {
        var w = warning("10", "100");

        assertThat(w.isHigh(d("40"), d("200"))).as("20% but under the floor").isFalse();
        assertThat(w.isHigh(d("150"), d("5000"))).as("over the floor but 3%").isFalse();
        assertThat(w.isHigh(d("300"), d("2460"))).as("12% and over the floor").isTrue();
    }

    @Test
    @DisplayName("the boundaries count as high")
    void boundaries() {
        var w = warning("10", "100");

        assertThat(w.isHigh(d("100"), d("1000"))).as("exactly 10% and exactly the floor").isTrue();
        assertThat(w.isHigh(d("99.99"), d("500"))).as("a paisa under the floor").isFalse();
        assertThat(w.isHigh(d("100"), d("1000.01"))).as("a paisa under 10%").isFalse();
    }

    @Test
    @DisplayName("free, missing or meaningless amounts are never high")
    void nothingToWarnAbout() {
        var w = warning("10", "100");

        assertThat(w.isHigh(d("0"), d("500"))).isFalse();
        assertThat(w.isHigh(null, d("500"))).isFalse();
        assertThat(w.isHigh(d("200"), null)).isFalse();
        assertThat(w.isHigh(d("200"), d("0"))).isFalse();
    }

    @Test
    @DisplayName("the percentage and the floor come from configuration")
    void configurable() {
        assertThat(warning("5", "50").isHigh(d("60"), d("1000"))).isTrue();
        assertThat(warning("20", "100").isHigh(d("300"), d("2460"))).isFalse();
    }
}
