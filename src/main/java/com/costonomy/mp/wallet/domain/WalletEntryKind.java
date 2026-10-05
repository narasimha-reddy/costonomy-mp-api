package com.costonomy.mp.wallet.domain;

/**
 * Why a wallet balance moved (D-104). {@link WalletDirection} says which way;
 * this says what for, which is what a statement shows and what a withdrawal needs
 * to tell card money from money that has nowhere to go back to.
 */
public enum WalletEntryKind {

    /**
     * Money put in. Either a Razorpay top-up that was captured (D-107), whose
     * ledger reference is {@code topup-{id}}, or the mock endpoint's credit with
     * no reference and no money behind it (D-099).
     */
    TOP_UP,
    /** An order paid from the wallet. */
    ORDER_PAYMENT,
    /** A wallet-paid order's money given back, when it was not fulfilled. */
    ORDER_REFUND,
    /**
     * A refund of a card or bank payment, credited here instead of to the card.
     * This is the only kind a withdrawal can send back: it knows which payment
     * the money came from.
     */
    REFUND,
    /** Refund money sent back to the card or bank it came from. */
    WITHDRAWAL,
    /**
     * A dispute refund on an order paid from the wallet (D-104). No card behind
     * it, so like a wallet order's return it can be spent and not withdrawn.
     */
    DISPUTE_REFUND,
    /**
     * A withdrawal part the provider did not send, put back (D-110). A credit, unique per
     * refund (reference {@code withdrawal-reversal-{refundId}}), written only after a look at
     * the provider showed no refund of ours. Money that came from a card, so it is
     * withdrawable again unless its source payment has since been blocked.
     */
    WITHDRAWAL_REVERSAL,
    /** A QuickScan payment to a UPI merchant, debited up front (D-106). */
    QUICKSCAN_PAYMENT,
    /**
     * A QuickScan payment's money given back — the payout was refused or
     * reversed. No card behind it, like an order's return.
     */
    QUICKSCAN_RETURN,
    /**
     * Money leaving the wallet to repay a supplier-funded credit invoice (D-122). A debit that settles
     * credit invoices; it never takes a bill. Unique per repayment (reference
     * {@code credit-repayment-{repaymentId}}).
     */
    CREDIT_REPAYMENT
}
