package com.costonomy.mp.wallet.domain;

/**
 * Which way the money went, from the wallet's point of view.
 *
 * <p>That is the opposite of the restaurant's, and worth saying: a {@code DEBIT}
 * here is money leaving the wallet to pay for an order. It is also not the
 * credit in {@code credit_agreement} — that is a supplier extending terms, this
 * is a balance already paid in.
 */
public enum WalletDirection {
    /** Out: to fund an order, or back to a card. */
    DEBIT,
    /** In: a top-up, a cancelled wallet order, or a refund. */
    CREDIT
}
