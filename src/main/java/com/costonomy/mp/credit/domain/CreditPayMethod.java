package com.costonomy.mp.credit.domain;

/** What the restaurant chose to pay with. UPI and CARD only pre-select the method in Razorpay's checkout (D-199). */
public enum CreditPayMethod {
    UPI,
    CARD,
    WALLET;

    public CreditPayChannel channel() {
        return this == WALLET ? CreditPayChannel.WALLET : CreditPayChannel.ONLINE;
    }
}
