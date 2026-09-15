package com.costonomy.mp.trust.domain;

/**
 * Doc 01 §24: ratings are public subject to moderation.
 *
 * <p><b>Moderation that removes, not moderation that gates.</b> A rating is
 * published when it is written and can be hidden afterwards, with a reason and an
 * audit entry. Pre-moderation would mean no rating appears until someone reviews
 * it — and a marketplace whose ratings lag by a working day effectively has none,
 * while the supplier whose rating is held back is penalised for nothing.
 */
public enum RatingModerationStatus {

    PUBLISHED,
    /** Removed by a moderator. Still stored, and still auditable. */
    HIDDEN;

    /** Whether this rating counts towards a store's public average and ranking. */
    public boolean isVisible() {
        return this == PUBLISHED;
    }
}
