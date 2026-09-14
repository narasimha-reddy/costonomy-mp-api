package com.costonomy.mp.procurement.domain;

import java.util.Set;

/**
 * Requirement lifecycle. Doc 03 §3.
 *
 * <pre>
 * OPEN → SOURCING → PARTIALLY_FULFILLED → FULFILLED
 * </pre>
 *
 * with {@code CANCELLED} and {@code EXPIRED} branches.
 *
 * <p>{@code SOURCING} appears in doc 03 §3 and not in Engineering PRD v1.0 §6.
 * Included, per D-001 — the numbered docs are authoritative — and because it is a
 * genuinely distinct state: "we have submitted this to a supplier and are waiting"
 * is not the same as "nothing has happened yet", and §23A.14 shows them
 * differently. This settles OPEN-003.
 */
public enum RequirementStatus {

    /** Raised, nothing sourced yet. */
    OPEN,
    /** At least one supplier order is outstanding against it. */
    SOURCING,
    /**
     * Some quantity delivered, some still needed.
     *
     * <p>The state guardrail 14 exists to protect. A supplier rejecting, timing
     * out or accepting less lands here, with the shortfall still sourceable —
     * the restaurant never retypes what they already asked for.
     */
    PARTIALLY_FULFILLED,
    FULFILLED,
    CANCELLED,
    /** Only reachable where a business policy configures expiry (doc 03 §3). */
    EXPIRED;

    public Set<RequirementStatus> allowedTransitions() {
        return switch (this) {
            case OPEN -> Set.of(SOURCING, PARTIALLY_FULFILLED, FULFILLED, CANCELLED, EXPIRED);
            case SOURCING -> Set.of(OPEN, PARTIALLY_FULFILLED, FULFILLED, CANCELLED, EXPIRED);
            // Back to SOURCING when the shortfall is submitted to another supplier.
            case PARTIALLY_FULFILLED -> Set.of(SOURCING, FULFILLED, CANCELLED);
            // Terminal. A fulfilled requirement that turns out short becomes a
            // receiving discrepancy and then a dispute (doc 26), not a reopened
            // requirement — the order was delivered, and rewriting that would lose
            // the fact that it was.
            case FULFILLED, CANCELLED, EXPIRED -> Set.of();
        };
    }

    public boolean canTransitionTo(RequirementStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
