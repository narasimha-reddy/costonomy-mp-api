package com.costonomy.mp.payment.domain;

/** Why a payment ended RELEASED (D-109): nothing was taken, and how the money went back. */
public enum ReleaseReason {

    /**
     * A card hold we let lapse. The bank put a temporary hold on the card and
     * nothing was debited, so there is nothing to refund: the hold drops when
     * Razorpay releases it.
     */
    CARD_HOLD_DROPPED,

    /**
     * Razorpay returned an authorised payment to the payer on its own, because it
     * was never captured before its hold limit. Recorded from what Razorpay says,
     * so an order whose money has gone back is not still shown as held.
     */
    PROVIDER_AUTO_REFUND
}
