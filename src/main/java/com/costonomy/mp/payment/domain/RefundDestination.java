package com.costonomy.mp.payment.domain;

/**
 * Where a refund's money goes (D-104).
 *
 * <p>Kept on the refund rather than implied by its reason, because the same
 * reason can end in either place and a reconciliation against the provider has
 * to count only the refunds the provider actually made.
 */
public enum RefundDestination {

    /** A provider refund, to the card or bank the payment came from. */
    ORIGINAL,

    /**
     * A credit to the outlet's wallet. Completed in the same transaction as the
     * credit, with no provider call — the money has not left the platform, and
     * leaves it only if the restaurant withdraws it, as an ORIGINAL refund.
     */
    WALLET
}
