package com.costonomy.mp.identity.domain;

public enum RefreshTokenStatus {
    ACTIVE,
    /** Rotated: exchanged for a successor. Presenting it again is a theft signal. */
    ROTATED,
    /** Explicitly invalidated — logout, suspension, or a detected token replay. */
    REVOKED,
}
