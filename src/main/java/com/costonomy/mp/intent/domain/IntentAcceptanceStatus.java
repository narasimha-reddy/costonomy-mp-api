package com.costonomy.mp.intent.domain;

/**
 * A supplier's answer to an intent. §10, §20.
 *
 * <p>{@code SUBMITTED} is immutable: once a supplier has said what they will
 * supply and at what price, neither side may change it. If the supplier can no
 * longer honour it, the window expires or the restaurant cancels — the accepted
 * proposal is never edited into a different one, because the restaurant may
 * already be deciding on the basis of what it said.
 */
public enum IntentAcceptanceStatus {
    /** Being filled in. Never visible to the restaurant. */
    DRAFT,
    /** Said and frozen. */
    SUBMITTED,
    /** Nobody ordered against it in time. */
    EXPIRED
}
