package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletEntryKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WalletEntryCopyTest {

    @Test
    @DisplayName("every kind of ledger entry has words of its own, so a statement never prints a bare code")
    void everyKindHasItsOwnWords() {
        var seen = new java.util.HashSet<String>();
        for (var kind : WalletEntryKind.values()) {
            var direction = kind == WalletEntryKind.ORDER_PAYMENT || kind == WalletEntryKind.WITHDRAWAL
                    || kind == WalletEntryKind.QUICKSCAN_PAYMENT || kind == WalletEntryKind.CREDIT_REPAYMENT
                    ? WalletDirection.DEBIT : WalletDirection.CREDIT;
            String label = WalletEntryCopy.label(kind, direction);
            assertThat(label).describedAs(kind.name()).isNotBlank().doesNotContain("_");
            // REFUND and DISPUTE_REFUND read the same to a restaurant; nothing else may share words.
            if (kind != WalletEntryKind.DISPUTE_REFUND) {
                assertThat(seen.add(label)).describedAs("label of " + kind).isTrue();
            }
        }
    }

    @Test
    @DisplayName("a withdrawal put back says so, and is not called a refund or a top-up")
    void withdrawalReversalReads() {
        assertThat(WalletEntryCopy.label(WalletEntryKind.WITHDRAWAL_REVERSAL, WalletDirection.CREDIT))
                .isEqualTo("Withdrawal returned to your wallet");
        assertThat(WalletEntryCopy.label(WalletEntryKind.QUICKSCAN_RETURN, WalletDirection.CREDIT))
                .isEqualTo("QuickScan payment returned");
    }

    @Test
    @DisplayName("a credit repayment reads as one, not as a payment for an order")
    void creditRepaymentReads() {
        assertThat(WalletEntryCopy.label(WalletEntryKind.CREDIT_REPAYMENT, WalletDirection.DEBIT))
                .isEqualTo("Credit repayment");
    }
}
