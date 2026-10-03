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

    @Test
    @DisplayName("D-110: a failure says what it means for the money: throttled, credentials, unknown payment, refused, or ambiguous")
    void kindIsDerivedFromWhatTheFailureSays() {
        assertThat(with("429").kind()).isEqualTo(ProviderFailureKind.THROTTLED);
        assertThat(with("401").kind()).isEqualTo(ProviderFailureKind.CONFIG);
        assertThat(with("403").kind()).isEqualTo(ProviderFailureKind.CONFIG);
        assertThat(with("404").kind()).isEqualTo(ProviderFailureKind.PAYMENT_UNKNOWN);
        assertThat(with("NOT_FOUND").kind()).isEqualTo(ProviderFailureKind.PAYMENT_UNKNOWN);
        assertThat(with("400").kind()).isEqualTo(ProviderFailureKind.REJECTED_OTHER);
        assertThat(with("INVALID_STATE").kind()).isEqualTo(ProviderFailureKind.REJECTED_OTHER);
        // Retryable, or unreachable, says nothing about whether it was done.
        assertThat(new PaymentProviderException("x", true, "500").kind()).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        assertThat(new PaymentProviderException("x", true, null).kind()).isEqualTo(ProviderFailureKind.AMBIGUOUS);
        assertThat(PaymentProviderException.unreachable("down", new RuntimeException()).kind())
                .isEqualTo(ProviderFailureKind.AMBIGUOUS);
        // An explicit kind wins over the code.
        assertThat(new PaymentProviderException("x", false, "400", ProviderFailureKind.ALREADY_REFUNDED, "fully refunded").kind())
                .isEqualTo(ProviderFailureKind.ALREADY_REFUNDED);
    }

    @Test
    @DisplayName("D-110: only a definite refusal is checked for a reversal; only the permanent ones block; two of them trip the breaker")
    void kindProperties() {
        for (var kind : ProviderFailureKind.values()) {
            boolean definite = kind != ProviderFailureKind.AMBIGUOUS && kind != ProviderFailureKind.THROTTLED
                    && kind != ProviderFailureKind.CONFIG;
            assertThat(kind.isDefinite()).describedAs(kind.name()).isEqualTo(definite);
        }
        assertThat(java.util.Arrays.stream(ProviderFailureKind.values()).filter(ProviderFailureKind::isPermanent))
                .containsExactlyInAnyOrder(ProviderFailureKind.PAYMENT_UNKNOWN, ProviderFailureKind.NOT_CAPTURED,
                        ProviderFailureKind.WINDOW_PASSED);
        assertThat(java.util.Arrays.stream(ProviderFailureKind.values()).filter(ProviderFailureKind::tripsBreaker))
                .containsExactlyInAnyOrder(ProviderFailureKind.INSUFFICIENT_BALANCE, ProviderFailureKind.CONFIG);
    }
}
