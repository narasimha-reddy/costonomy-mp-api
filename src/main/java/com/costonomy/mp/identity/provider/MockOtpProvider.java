package com.costonomy.mp.identity.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Development and test OTP provider. Doc 10 §4.
 *
 * <p>Sends nothing. It exists so the whole login flow works offline with no MSG91
 * account, which doc 10 §6 requires of local development.
 *
 * <p><b>It does not log the code</b>, even though it is a mock. Two reasons: doc
 * 09 §16 is unconditional, and more practically, a mock that logs secrets is one
 * profile-misconfiguration away from doing so in production. Developers get a
 * predictable code from {@code costonomy.mp.otp.mock-code} instead, which is only
 * set in the {@code local} and {@code test} profiles.
 */
@Component
@ConditionalOnProperty(name = "costonomy.mp.providers.otp", havingValue = "MOCK", matchIfMissing = true)
@Slf4j
public class MockOtpProvider implements OtpProvider {

    @Override
    public OtpSendResult send(String phone, String code) {
        log.info("Mock OTP issued for {} (code not logged; use the configured mock code)",
                maskPhone(phone));
        return new OtpSendResult("mock-" + UUID.randomUUID());
    }

    @Override
    public String name() {
        return "MOCK";
    }

    /** {@code +919999000001} → {@code +91******0001}. Doc 09 §6, PII minimisation. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "******" + phone.substring(phone.length() - 4);
    }
}
