package com.costonomy.mp.quickscan.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The mock's amount-driven scenarios (see its javadoc), which the tests rely on. */
class MockPayoutProviderTest {

    private final MockPayoutProvider provider = new MockPayoutProvider();

    private PayoutProvider.ProviderPayout create(String amount) {
        return provider.createPayout("shop@upi", "Shop", new BigDecimal(amount), "ref",
                UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("anything else is PROCESSED immediately")
    void defaultsToProcessed() {
        var payout = create("250.00");
        assertThat(payout.status()).isEqualTo(PayoutProvider.PayoutStatus.PROCESSED);
        assertThat(payout.providerPayoutId()).isNotBlank();
    }

    @Test
    @DisplayName(".13 is FAILED immediately")
    void declined() {
        var payout = create("100.13");
        assertThat(payout.status()).isEqualTo(PayoutProvider.PayoutStatus.FAILED);
        assertThat(payout.failureCode()).isNotBlank();
        assertThat(payout.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName(".23 is PENDING, then PROCESSED on the first fetch — and stays that way")
    void pendingThenProcessed() {
        var created = create("100.23");
        assertThat(created.status()).isEqualTo(PayoutProvider.PayoutStatus.PENDING);

        var first = provider.fetchPayout(created.providerPayoutId());
        assertThat(first.status()).isEqualTo(PayoutProvider.PayoutStatus.PROCESSED);

        var second = provider.fetchPayout(created.providerPayoutId());
        assertThat(second.status()).isEqualTo(PayoutProvider.PayoutStatus.PROCESSED);
    }

    @Test
    @DisplayName(".31 is PENDING, then REVERSED on the first fetch")
    void pendingThenReversed() {
        var created = create("100.31");
        assertThat(created.status()).isEqualTo(PayoutProvider.PayoutStatus.PENDING);

        var resolved = provider.fetchPayout(created.providerPayoutId());
        assertThat(resolved.status()).isEqualTo(PayoutProvider.PayoutStatus.REVERSED);
        assertThat(resolved.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("the same idempotency key never sends a second payout")
    void idempotentOnKey() {
        String key = UUID.randomUUID().toString();
        var first = provider.createPayout("shop@upi", "Shop", new BigDecimal("50.00"), "ref", key);
        var second = provider.createPayout("shop@upi", "Shop", new BigDecimal("50.00"), "ref", key);

        assertThat(second.providerPayoutId()).isEqualTo(first.providerPayoutId());
    }

    @Test
    @DisplayName("asking about an unknown payout fails rather than fabricating one")
    void unknownPayoutFails() {
        assertThatThrownBy(() -> provider.fetchPayout("nope"))
                .isInstanceOf(PayoutProviderException.class);
    }
}
