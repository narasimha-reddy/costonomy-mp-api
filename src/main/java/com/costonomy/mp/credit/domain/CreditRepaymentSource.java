package com.costonomy.mp.credit.domain;

/** What funded a {@link CreditRepayment}. Only WALLET is used today; UPI and CARD are reserved. D-121. */
public enum CreditRepaymentSource {
    WALLET,
    UPI,
    CARD
}
