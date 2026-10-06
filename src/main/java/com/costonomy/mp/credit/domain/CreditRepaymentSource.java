package com.costonomy.mp.credit.domain;

/** What funded a {@link CreditRepayment}. WALLET, and SUPPLIER_RECORDED (D-134); UPI and CARD are reserved. D-121. */
public enum CreditRepaymentSource {
    WALLET,
    /** The supplier recorded money received outside Mandi: no wallet debit, no payout. */
    SUPPLIER_RECORDED,
    UPI,
    CARD
}
