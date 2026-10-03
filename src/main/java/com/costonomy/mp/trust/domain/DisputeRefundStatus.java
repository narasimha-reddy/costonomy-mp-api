package com.costonomy.mp.trust.domain;

/**
 * A refund asked for on a dispute (D-104).
 *
 * <pre>
 *   REQUESTED ─┬─ supplier approves ──────────────────────→ APPROVED
 *              ├─ supplier declines ──→ DECLINED ─┬─ ops ──→ OPS_APPROVED
 *              │                                  └─ ops ──→ OPS_DECLINED
 *              └─ 48 hours, no answer: ops may decide, as from DECLINED
 * </pre>
 *
 * <p>The supplier may still answer after 48 hours, until operations has.
 */
public enum DisputeRefundStatus {

    REQUESTED,
    /** The supplier agreed. The restaurant's wallet was credited and the payout charged. */
    APPROVED,
    /** The supplier said no. Waiting for operations, who may agree or not. */
    DECLINED,
    OPS_APPROVED,
    OPS_DECLINED;

    /** Nobody has had the last word: the payout it would come from cannot be approved. */
    public boolean isUndecided() {
        return this == REQUESTED || this == DECLINED;
    }

    public boolean isPaid() {
        return this == APPROVED || this == OPS_APPROVED;
    }

    /** The event this status raises. Named here, never assembled (D-044). */
    public String eventName() {
        return switch (this) {
            case REQUESTED -> "DisputeRefundRequested";
            case APPROVED, OPS_APPROVED -> "DisputeRefundApproved";
            case DECLINED, OPS_DECLINED -> "DisputeRefundDeclined";
        };
    }
}
