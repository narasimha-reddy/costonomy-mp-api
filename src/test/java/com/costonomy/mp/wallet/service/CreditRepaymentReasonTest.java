package com.costonomy.mp.wallet.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditRepaymentReasonTest {

    @Test
    @DisplayName("the reason names the supplier, trimmed")
    void names() {
        assertThat(WalletService.creditRepaymentReason("  Sri Balaji Traders "))
                .isEqualTo("Credit repayment to Sri Balaji Traders");
    }

    @Test
    @DisplayName("a blank or missing name falls back to the plain reason")
    void blankFallsBack() {
        assertThat(WalletService.creditRepaymentReason(null)).isEqualTo("Credit repayment");
        assertThat(WalletService.creditRepaymentReason("")).isEqualTo("Credit repayment");
        assertThat(WalletService.creditRepaymentReason("   ")).isEqualTo("Credit repayment");
    }

    @Test
    @DisplayName("a long name is cut to fit the 200-character column; the prefix is never cut")
    void longNameTruncatesTheName() {
        String reason = WalletService.creditRepaymentReason("X".repeat(500));

        assertThat(reason).hasSize(200).startsWith("Credit repayment to XXX");
        assertThat(reason).isEqualTo("Credit repayment to " + "X".repeat(180));
    }

    @Test
    @DisplayName("a name that fits exactly is kept whole; truncation never splits an emoji")
    void boundaryAndSurrogates() {
        assertThat(WalletService.creditRepaymentReason("Y".repeat(180))).hasSize(200).endsWith("Y");
        String reason = WalletService.creditRepaymentReason("🍛".repeat(120)); // 240 chars
        assertThat(reason.length()).isLessThanOrEqualTo(200);
        assertThat(Character.isHighSurrogate(reason.charAt(reason.length() - 1))).isFalse();
    }
}
