package com.costonomy.mp.credit.domain;

/** Where a {@link CreditRepayment} stands. More states arrive with asynchronous UPI and card repayments. */
public enum CreditRepaymentStatus {
    COMPLETED,
    /** The supplier undid a receipt it recorded (D-169). Its payments stay; a reversal row sits beside each. */
    REVERSED
}
