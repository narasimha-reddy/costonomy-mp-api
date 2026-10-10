package com.costonomy.mp.credit.domain;

/** Who suspended a credit line. Only a {@link #SYSTEM} suspension lifts itself on repayment. */
public enum SuspensionSource {
    /** The overdue sweep. */
    SYSTEM,
    /** A supplier user, through the suspend endpoint. */
    SUPPLIER
}
