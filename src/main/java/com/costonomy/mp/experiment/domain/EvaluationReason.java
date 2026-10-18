package com.costonomy.mp.experiment.domain;

/**
 * Diagnostic reason explaining how a variant evaluation was determined.
 */
public enum EvaluationReason {
    /** Deterministically hashed into the variant based on traffic and weights. */
    BUCKETED,
    /** Forced into this variant by explicit whitelist / subject override. */
    OVERRIDE,
    /** Experiment is not currently ACTIVE (DRAFT, PAUSED, ARCHIVED, or outside start/end window). */
    FALLBACK_NOT_ACTIVE,
    /** Subject attributes did not satisfy targeting rule criteria. */
    FALLBACK_NOT_TARGETED,
    /** Subject fell outside the traffic rollout percentage. */
    FALLBACK_OUT_OF_TRAFFIC,
    /** Subject allocated to another experiment in this mutual-exclusion layer. */
    FALLBACK_LAYER_EXCLUDED,
    /** Experiment key was not found in the registry. */
    FALLBACK_MISSING_EXPERIMENT,
    /** Unexpected evaluation error or corrupted configuration. */
    FALLBACK_ERROR;

    public boolean isInExperiment() {
        return this == BUCKETED || this == OVERRIDE;
    }
}
