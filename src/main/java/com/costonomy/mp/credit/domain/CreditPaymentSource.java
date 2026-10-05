package com.costonomy.mp.credit.domain;

/** Where a {@link CreditPayment} came from. UPI and CARD arrive with provider-funded repayments. D-121. */
public enum CreditPaymentSource {
    /** The supplier recorded money received outside Mandi. */
    SUPPLIER_RECORDED,
    /** The restaurant repaid from its wallet. */
    WALLET,
    /** The supplier confirmed a payment the restaurant claimed to have made. */
    CLAIM_CONFIRMED
}
