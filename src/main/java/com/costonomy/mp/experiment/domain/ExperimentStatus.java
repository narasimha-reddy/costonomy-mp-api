package com.costonomy.mp.experiment.domain;

/**
 * Lifecycle status of an experiment.
 */
public enum ExperimentStatus {
    /** Created and configurable, but not served to general traffic yet. */
    DRAFT,
    /** Actively serving variants to live traffic according to rules & rollout. */
    ACTIVE,
    /** Temporarily stopped; traffic receives control variant. */
    PAUSED,
    /** Permanently concluded; traffic receives control variant or default. */
    ARCHIVED;

    public boolean isServing() {
        return this == ACTIVE;
    }
}
