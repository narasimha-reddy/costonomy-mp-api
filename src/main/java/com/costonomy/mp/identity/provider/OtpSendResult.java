package com.costonomy.mp.identity.provider;

/**
 * The outcome of a send.
 *
 * <p>Carries only a reference — deliberately never the code. This value is
 * logged and persisted, and doc 09 §16 forbids an OTP reaching either.
 */
public record OtpSendResult(String providerReference) {
}
