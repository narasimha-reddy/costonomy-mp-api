package com.costonomy.mp.credit.domain;

/** How the money reaches Mandi: one Razorpay order (UPI and card are one channel), or the restaurant's wallet (D-196). */
public enum CreditPayChannel {
    ONLINE,
    WALLET
}
