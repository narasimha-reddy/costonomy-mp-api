package com.costonomy.mp.payment.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a provider's refusal says (F2): only a 404 says something about the payment; a rate
 * limit and refused credentials say something about the call, and an outage or a malformed
 * request about neither.
 */
class PaymentProviderExceptionTest {

    private static PaymentProviderException with(String code) {
        return new PaymentProviderException("x", false, code);
    }

    @Test
    @DisplayName("only 404 (or the mock's NOT_FOUND) is 'the provider does not know this payment'")
    void notFound() {
        assertThat(with("404").isNotFound()).isTrue();
        assertThat(with("NOT_FOUND").isNotFound()).isTrue();
        for (String other : new String[] {"400", "401", "403", "429", "500", "503", "INVALID_STATE", null}) {
            assertThat(with(other).isNotFound()).describedAs(String.valueOf(other)).isFalse();
        }
        assertThat(PaymentProviderException.unreachable("down", new RuntimeException()).isNotFound()).isFalse();
    }

    @Test
    @DisplayName("429 is a rate limit; 401 and 403 are refused credentials; nothing else is either")
    void rateLimitAndCredentials() {
        assertThat(with("429").isRateLimited()).isTrue();
        assertThat(with("401").isCredentialsRefused()).isTrue();
        assertThat(with("403").isCredentialsRefused()).isTrue();
        for (String other : new String[] {"400", "404", "500", "503", null}) {
            assertThat(with(other).isRateLimited()).describedAs(String.valueOf(other)).isFalse();
            assertThat(with(other).isCredentialsRefused()).describedAs(String.valueOf(other)).isFalse();
        }
        assertThat(with("429").isCredentialsRefused()).isFalse();
        assertThat(with("401").isRateLimited()).isFalse();
    }
}
