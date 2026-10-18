package com.costonomy.mp.experiment.domain;

/**
 * The entity type being bucketed in an experiment.
 */
public enum SubjectType {
    /** Individual marketplace user (buyer, chef, owner). */
    USER,
    /** Supplier entity (merchant, distributor, vendor). */
    SUPPLIER,
    /** Restaurant/outlet entity. */
    RESTAURANT,
    /** Client hardware device identifier (mobile UUID, browser fingerprint). */
    DEVICE,
    /** Ephemeral session identifier (e.g. guest checkout). */
    SESSION
}
