package com.costonomy.mp.supplier.domain;

import java.util.Set;

/**
 * Supplier organisation lifecycle. Doc 01 §6, doc 03 §2.
 *
 * <pre>
 * INVITED → REGISTERED → VERIFICATION_PENDING → VERIFIED → ACTIVE
 * </pre>
 *
 * with {@code SUSPENDED} and {@code OFFLINE} as operational states.
 *
 * <p>{@code VERIFIED} and {@code ACTIVE} are deliberately separate. Verification
 * confirms the business is who it claims to be; activation is the decision to let
 * it trade. Collapsing them would make a verification result auto-activate, which
 * doc 03 §2 forbids.
 */
public enum SupplierLifecycleStatus {

    /** Invited by phone; has not registered. */
    INVITED,
    /** Registered, nothing submitted for verification yet. */
    REGISTERED,
    /** Submitted, awaiting review. */
    VERIFICATION_PENDING,
    /** Verification passed. Still cannot trade until activated. */
    VERIFIED,
    /** Trading. */
    ACTIVE,
    /** Stopped by operations — quality, fraud, unresolved disputes. */
    SUSPENDED,
    /** Temporarily not accepting orders, by the supplier's own choice. */
    OFFLINE;

    /** Legal next states. Anything else is an {@code INVALID_STATE_TRANSITION}. */
    public Set<SupplierLifecycleStatus> allowedTransitions() {
        return switch (this) {
            case INVITED -> Set.of(REGISTERED);
            case REGISTERED -> Set.of(VERIFICATION_PENDING, SUSPENDED);
            case VERIFICATION_PENDING -> Set.of(VERIFIED, REGISTERED, SUSPENDED);
            case VERIFIED -> Set.of(ACTIVE, SUSPENDED);
            case ACTIVE -> Set.of(OFFLINE, SUSPENDED);
            case OFFLINE -> Set.of(ACTIVE, SUSPENDED);
            case SUSPENDED -> Set.of(ACTIVE);
        };
    }

    public boolean canTransitionTo(SupplierLifecycleStatus target) {
        return allowedTransitions().contains(target);
    }
}
