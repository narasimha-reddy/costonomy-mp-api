package com.costonomy.mp.identity.domain;

/**
 * Why an OTP was issued.
 *
 * <p>Part of the throttling and verification key, so a login challenge cannot be
 * redeemed to satisfy a phone-number change, and the two do not share a cooldown.
 */
public enum OtpPurpose {
    LOGIN,
    PHONE_CHANGE,
}
