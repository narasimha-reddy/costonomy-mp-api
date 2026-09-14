package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Normalisation is what makes the unique index on {@code users.phone} mean "one
 * row per person". Every case below is a way the same human's number reaches us.
 */
class PhoneNumbersTest {

    @ParameterizedTest(name = "{0} → +919999000001")
    @ValueSource(strings = {
            "9999000001",           // bare national
            "09999000001",          // national trunk prefix
            "+919999000001",        // already E.164
            "919999000001",         // country code, no plus
            "0091 9999000001",      // international prefix
            "+91 99990 00001",      // spaced, as a human types it
            "+91-99990-00001",      // dashed
            "(+91) 9999000001",     // bracketed
    })
    @DisplayName("every way of writing one number collapses to the same E.164 value")
    void normalizesIndianNumbers(String input) {
        // If any of these diverged, the same purchase manager could end up with
        // two accounts holding different permissions.
        assertThat(PhoneNumbers.normalize(input)).isEqualTo("+919999000001");
    }

    @Test
    @DisplayName("other markets are a parameter, not a special case")
    void supportsConfiguredCountries() {
        assertThat(PhoneNumbers.normalize("501234567", "AE")).isEqualTo("+971501234567");
        assertThat(PhoneNumbers.normalize("81234567", "SG")).isEqualTo("+6581234567");
    }

    @Test
    @DisplayName("an explicit + is honoured regardless of the default country")
    void explicitCountryCodeWins() {
        assertThat(PhoneNumbers.normalize("+6581234567", "IN")).isEqualTo("+6581234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "12345",                 // too short
            "99990000011111111111",  // too long
            "99990abcde",            // letters
            "+",                     // prefix only
    })
    @DisplayName("an unusable number is rejected, not silently stored")
    void rejectsInvalid(String input) {
        assertThatThrownBy(() -> PhoneNumbers.normalize(input))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("null is rejected")
    void rejectsNull() {
        assertThatThrownBy(() -> PhoneNumbers.normalize(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("an unknown country is rejected rather than guessed at")
    void rejectsUnknownCountry() {
        assertThatThrownBy(() -> PhoneNumbers.normalize("9999000001", "ZZ"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("masking keeps enough to recognise the number and hides the rest")
    void masksForLogs() {
        // §23A.7 shows the masked number on the OTP screen; doc 09 §6 requires
        // logs to minimise PII.
        assertThat(PhoneNumbers.mask("+919999000001")).isEqualTo("+91******0001");
        assertThat(PhoneNumbers.mask("123")).isEqualTo("***");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("***");
    }
}
