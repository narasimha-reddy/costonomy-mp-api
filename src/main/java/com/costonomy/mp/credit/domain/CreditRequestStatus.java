package com.costonomy.mp.credit.domain;

/** Doc 05 §20. The negotiation, not the instrument — see {@link CreditAgreementStatus}. */
public enum CreditRequestStatus {

    REQUESTED,
    /**
     * The supplier wants more before deciding. Deliberately not a rejection: doc
     * 01 §18 lists "request additional information" as a distinct response, and
     * treating it as a refusal would end conversations that were going to succeed.
     */
    INFO_REQUESTED,
    /** Approved as asked. */
    APPROVED,
    /** Approved on different terms, which the restaurant must still accept. */
    MODIFIED,
    REJECTED
}
