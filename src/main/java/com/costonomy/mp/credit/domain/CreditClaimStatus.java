package com.costonomy.mp.credit.domain;

/** Where an "I paid" claim stands (D-125). Only SUBMITTED can move; the others are final. */
public enum CreditClaimStatus {
    /** The restaurant says it paid; the supplier has not answered. Counts against what can still be claimed. */
    SUBMITTED,
    /** The supplier confirmed it, fully or in part, and the payment is recorded. */
    CONFIRMED,
    /** The supplier could not confirm it. No money effect. */
    REJECTED,
    /** The restaurant took it back before the supplier answered. */
    WITHDRAWN,
    /**
     * The invoice was settled by other means before the supplier answered, so there is nothing left to confirm
     * against (D-130). Set by the system, final, no notification.
     */
    SUPERSEDED
}
