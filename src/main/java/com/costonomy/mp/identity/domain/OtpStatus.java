package com.costonomy.mp.identity.domain;

public enum OtpStatus {
    /** Issued and still redeemable. */
    PENDING,
    VERIFIED,
    /** Attempt limit reached. Terminal — the user must request a new code. */
    ATTEMPTS_EXCEEDED,
    /**
     * Superseded by a newer code for the same phone and purpose.
     *
     * <p>Kept distinct from EXPIRED so "user requested a second code" is
     * separable from "user let the code time out" when looking at drop-off.
     */
    SUPERSEDED,
    /** Past {@code expiresAt} when a verification was attempted. */
    EXPIRED,
}
