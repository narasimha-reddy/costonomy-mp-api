package com.costonomy.mp.procurement.domain;

public enum ApprovalStatus {
    /** No policy matched. The order can be submitted by whoever built it. */
    NOT_REQUIRED,
    /** A policy matched and is waiting on an approver. */
    PENDING,
    APPROVED,
    REJECTED,
}
