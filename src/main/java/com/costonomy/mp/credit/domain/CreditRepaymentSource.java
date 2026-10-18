package com.costonomy.mp.credit.domain;

/** What funded a {@link CreditRepayment}. WALLET, and SUPPLIER_RECORDED (D-164); UPI and CARD are reserved. D-151. */
public enum CreditRepaymentSource {
    WALLET,
    /** The supplier recorded money received outside Mandi: no wallet debit, no payout. */
    SUPPLIER_RECORDED,
    UPI,
    CARD,
    /** Paid online through Razorpay (UPI, card) through Mandi, one repayment per supplier line (D-199). */
    ONLINE
}
