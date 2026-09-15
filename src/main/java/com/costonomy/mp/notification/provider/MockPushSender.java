package com.costonomy.mp.notification.provider;

import com.costonomy.mp.notification.domain.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The push provider local development and CI run on. Doc 08, doc 10 §6.
 *
 * <p>Two failures are reachable by the destination itself, so a test produces one
 * by asking for it rather than by mocking: a token containing {@code invalid} is
 * refused permanently (the app was uninstalled — retrying forever is how a dead
 * token becomes a permanent queue entry), and one containing {@code flaky} fails
 * once in a way the sweep should retry.
 *
 * <p><b>The body is logged at debug and the token is not.</b> A push token is a
 * handle for an app install, and writing thousands of them into logs is the kind
 * of thing that is nobody's problem until it is.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "costonomy.mp.providers.notification", havingValue = "MOCK",
        matchIfMissing = true)
public class MockPushSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public String providerName() {
        return "MOCK_PUSH";
    }

    @Override
    public String send(String destination, String title, String body) {
        if (destination == null || destination.isBlank()) {
            throw new NotificationSendException("No push token for this device", false);
        }
        if (destination.contains("invalid")) {
            // Permanent. The app is gone; the token will never work again.
            throw new NotificationSendException("Unregistered push token", false);
        }
        if (destination.contains("flaky")) {
            throw new NotificationSendException("Push service unavailable", true);
        }

        log.debug("Mock push: {}", title);
        return "mock_push_" + UUID.randomUUID().toString().replace("-", "");
    }
}
