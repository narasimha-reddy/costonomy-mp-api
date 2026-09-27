package com.costonomy.mp.wallet.domain;

/**
 * Why a wallet balance moved (D-104). {@link WalletDirection} says which way;
 * this says what for, which is what a statement shows and what a withdrawal needs
 * to tell card money from money that has nowhere to go back to.
 */
public enum WalletEntryKind {

    /** Money put in directly. Only on the mock provider (D-099). */
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
    WITHDRAWAL
}
