package com.costonomy.mp.notification.provider;

import com.costonomy.mp.notification.domain.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The SMS provider local development and CI run on.
 *
 * <p>Separate from {@code MockOtpProvider} deliberately. An OTP and a notification
 * go out over the same wire but are not the same thing: one is a credential with
 * its own rate limits and its own "never log this" rule, the other is a message.
 * Merging them would put notification traffic through the code path that handles
 * authentication secrets.
 *
 * <p>The message is never logged — doc 08 §8, and an SMS body can carry an amount
 * or an order number that has no business in a log file.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "costonomy.mp.providers.notification", havingValue = "MOCK",
        matchIfMissing = true)
public class MockSmsSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.SMS;
    }

    @Override
    public String providerName() {
        return "MOCK_SMS";
    }

    @Override
    public String send(String destination, String title, String body) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationSendException("No phone number for this user", false);
        }
        if (destination.endsWith("00000")) {
            throw new NotificationSendException("Unreachable number", false);
        }

        log.debug("Mock SMS to {}", mask(destination));
        return "mock_sms_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Enough to identify a number in a support call, not enough to be one. */
    private String mask(String phone) {
        return phone.length() <= 4 ? "****"
                : "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
