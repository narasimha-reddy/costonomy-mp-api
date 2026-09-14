package com.costonomy.mp.supplier.domain;

public enum VerificationStatus {
    NOT_SUBMITTED,
    PENDING,
    VERIFIED,
    REJECTED,
    /** Reviewer needs more from the supplier. */
    INFORMATION_REQUESTED,
}
