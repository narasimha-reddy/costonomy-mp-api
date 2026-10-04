package com.costonomy.mp.wallet.statement;

import com.costonomy.mp.wallet.domain.WalletDirection;
import com.costonomy.mp.wallet.domain.WalletEntryKind;

/**
 * What a statement line says it was, in the restaurant's words. The same wording as the
 * mobile app's {@code entryLabel} (lib/wallet/entryCopy.ts): a downloaded statement that
 * called a row something the screen did not would be two answers to "what was this".
 * Change one, change the other.
 */
public final class WalletEntryCopy {

    private WalletEntryCopy() {
    }

    public static String label(WalletEntryKind kind, WalletDirection direction) {
        if (kind == null) {
            return direction == WalletDirection.CREDIT ? "Money in" : "Money out";
        }
        return switch (kind) {
            case TOP_UP -> "Money added";
            case ORDER_PAYMENT -> "Paid for an order";
            case ORDER_REFUND -> "Order cancelled · money back";
            case REFUND -> "Refund";
            case DISPUTE_REFUND -> "Refund from a dispute";
            case WITHDRAWAL -> "Sent back to your card or bank";
            case QUICKSCAN_PAYMENT -> "Paid a shop (QuickScan)";
            case QUICKSCAN_RETURN -> "QuickScan payment returned";
            case WITHDRAWAL_REVERSAL -> "Withdrawal returned to your wallet";
            case ORDER_ADJUSTMENT -> direction == WalletDirection.CREDIT
                    ? "Order adjusted · money back" : "Order adjusted · extra charge";
            case BANK_PAYOUT -> "Transferred to verified bank account";
            case BANK_PAYOUT_REVERSAL -> "Bank transfer returned to wallet";
        };
    }
}
