package com.costonomy.mp.delivery;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.provider.pidge.PidgeProperties;
import com.costonomy.mp.delivery.provider.pidge.PidgeWebhookService;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PidgeWebhookServiceTest {

    @Mock
    private DeliveryRepository deliveries;

    @Mock
    private DeliveryEventService eventService;

    @Mock
    private AuditService auditService;

    private PidgeProperties properties;
    private PidgeWebhookService service;

    private static final String SECRET = "env-injected-secret-key-32-chars-long";

    @BeforeEach
    void setUp() {
        properties = new PidgeProperties();
        properties.setWebhookSecret(SECRET);
        service = new PidgeWebhookService(properties, deliveries, eventService, new ObjectMapper(), auditService);
    }

    private String calculateHmac(String payload, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("Valid signature generated with env secret passes verification")
    void validSignaturePasses() throws Exception {
        String payload = "{\"event_id\":\"evt-123\",\"status\":\"DELIVERED\"}";
        String signature = calculateHmac(payload, SECRET);

        boolean valid = service.verifySignature(payload, signature);
        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Tampered payload fails signature verification")
    void tamperedPayloadFails() throws Exception {
        String payload = "{\"event_id\":\"evt-123\",\"status\":\"DELIVERED\"}";
        String signature = calculateHmac(payload, SECRET);
        String tamperedPayload = "{\"event_id\":\"evt-123\",\"status\":\"CANCELLED\"}";

        boolean valid = service.verifySignature(tamperedPayload, signature);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Wrong signature fails verification")
    void wrongSignatureFails() {
        String payload = "{\"event_id\":\"evt-123\",\"status\":\"DELIVERED\"}";
        boolean valid = service.verifySignature(payload, "badf00d1234567890abcdef");
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Null or empty signature is rejected")
    void missingSignatureFails() {
        String payload = "{\"event_id\":\"evt-123\",\"status\":\"DELIVERED\"}";
        assertThat(service.verifySignature(payload, null)).isFalse();
        assertThat(service.verifySignature(payload, "")).isFalse();
        assertThat(service.verifySignature(payload, "   ")).isFalse();
    }

    @Test
    @DisplayName("When the secret is unconfigured, every webhook is rejected (fail closed)")
    void unconfiguredSecretRejectsEverything() {
        properties.setWebhookSecret(null);
        assertThat(service.verifySignature("{}", "anysig")).isFalse();
        assertThat(service.verifySignature("{}", null)).isFalse();

        properties.setWebhookSecret("");
        assertThat(service.verifySignature("{}", null)).isFalse();
    }
}
