package com.costonomy.mp.credit.domain;

import java.util.List;

/**
 * How a payment on a credit invoice was made, as a person states it (D-125). The payment row itself still stores a
 * string, because a wallet repayment's method is {@code WALLET}; this is what a person may <em>say</em>.
 */
public enum CreditPaymentMethod {
    BANK_TRANSFER, UPI, CASH, CHEQUE, CARD,
    /** A supplier's own correction; never something a restaurant claims. */
    ADJUSTMENT;

    /** What a restaurant may claim to have paid with. */
    public static final List<CreditPaymentMethod> CLAIMABLE = List.of(BANK_TRANSFER, UPI, CASH, CHEQUE, CARD);

    /** The names of every method, for validation patterns. */
    public static final String ALL_PATTERN = "BANK_TRANSFER|UPI|CASH|CHEQUE|CARD|ADJUSTMENT";
    public static final String CLAIMABLE_PATTERN = "BANK_TRANSFER|UPI|CASH|CHEQUE|CARD";
}
