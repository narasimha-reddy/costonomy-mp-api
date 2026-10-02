package com.costonomy.mp.quickscan.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code local-part@handle} shape checking for a UPI VPA. */
class QuickScanValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "shop@upi", "shop.owner@okhdfcbank", "9876543210@ybl", "a-b_c.d@paytm",
            "ab@okaxis", "shop.name.99@ok" // handle need only start with a letter
    })
    @DisplayName("a well-formed VPA is valid")
    void validVpas(String vpa) {
        assertThat(QuickScanValidation.isValidVpa(vpa)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "noatsign", "@okhdfcbank", "shop@", "shop@1bank", "a@ok",
            "shop @okhdfcbank", "shop@ok hdfc", "shop@okhdfcbank\nINFO forged"
    })
    @DisplayName("a malformed VPA is rejected")
    void invalidVpas(String vpa) {
        assertThat(QuickScanValidation.isValidVpa(vpa)).isFalse();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("null is rejected, not thrown on")
    void nullIsRejected() {
        assertThat(QuickScanValidation.isValidVpa(null)).isFalse();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a one-character local part is too short")
    void tooShortLocalPart() {
        assertThat(QuickScanValidation.isValidVpa("a@okhdfcbank")).isFalse();
    }
}
