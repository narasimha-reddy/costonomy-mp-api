package com.costonomy.mp.procurement.domain;

import java.util.Set;

/**
 * Procurement lifecycle. Doc 03 §4.
 *
 * <pre>
 * DRAFT → VALIDATING → READY → PENDING_APPROVAL → APPROVED → SUBMITTED
 * </pre>
 *
 * <p>{@code DRAFT} is the cart (§23A.16). {@code READY} means validated against
 * live offers and safe to submit — and it decays: doc 01 §11 requires revalidation
 * at checkout, so a READY procurement left alone becomes stale and must be
 * validated again before it can be submitted.
 *
 * <p>{@code PENDING_APPROVAL} is entered by <b>policy</b>, never by the client.
 * Whether an order needs approval is a server decision (doc 03 §15).
 */
public enum ProcurementStatus {

    /** The cart. Items can be added, changed and removed. */
    DRAFT,
    /** Being checked against live offers. Transient. */
    VALIDATING,
    /** Validated and priced. Submittable, until it goes stale. */
    READY,
    /** Held by an approval policy. Doc 03 §15. */
    PENDING_APPROVAL,
    APPROVED,
    /** Supplier orders created. */
    SUBMITTED,
    REJECTED,
    CANCELLED,
    /**
     * Submission failed after validation — a supplier went offline in the seconds
     * between. Doc 03 §4 requires retry semantics, so this is not terminal: the
     * restaurant revalidates and tries again rather than rebuilding the cart.
     */
    FAILED;

    public Set<ProcurementStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> Set.of(VALIDATING, CANCELLED);
            // APPROVED is reachable because revalidating an already-approved
            // order must not silently un-approve it — see ProcurementService.
            case VALIDATING -> Set.of(READY, PENDING_APPROVAL, APPROVED, DRAFT, FAILED);
            // Back to DRAFT when the cart is edited, and back to VALIDATING when it
            // is revalidated because it went stale.
            case READY -> Set.of(SUBMITTED, VALIDATING, DRAFT, CANCELLED, FAILED);
            // Back to DRAFT so an approver's rejection can be edited and resubmitted
            // rather than forcing the cart to be rebuilt.
            case PENDING_APPROVAL -> Set.of(APPROVED, REJECTED, CANCELLED, DRAFT);
            // Revalidation is allowed after approval because an approved order can
            // still go stale before it is submitted.
            case APPROVED -> Set.of(SUBMITTED, VALIDATING, CANCELLED, FAILED);
            case FAILED -> Set.of(VALIDATING, DRAFT, CANCELLED);
            case SUBMITTED, REJECTED, CANCELLED -> Set.of();
        };
    }

    public boolean canTransitionTo(ProcurementStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isEditable() {
        return this == DRAFT || this == READY || this == FAILED;
    }
}
