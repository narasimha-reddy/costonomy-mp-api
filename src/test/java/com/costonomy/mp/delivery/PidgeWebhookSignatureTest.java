package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PidgeWebhookSignatureTest {

    private PidgeProperties properties;
    private PidgeWebhookService service;

    @BeforeEach
    void setUp() {
        properties = new PidgeProperties();
        properties.setWebhookSecret("test-secret-key-123");

        var deliveries = Mockito.mock(DeliveryRepository.class);
        var eventService = Mockito.mock(DeliveryEventService.class);
        var auditService = Mockito.mock(AuditService.class);
        service = new PidgeWebhookService(properties, deliveries, eventService, new ObjectMapper(), auditService);
    }

    @Test
    @DisplayName("valid HMAC-SHA256 signature passes verification")
    void validSignaturePasses() throws Exception {
        String body = "{\"event_id\":\"evt_1\",\"pidge_delivery_id\":\"del_1\",\"status\":\"DELIVERED\"}";
        
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret-key-123".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(service.verifySignature(body, signature)).isTrue();
    }

    @Test
    @DisplayName("invalid or forged signature fails verification")
    void invalidSignatureFails() {
        String body = "{\"event_id\":\"evt_1\",\"pidge_delivery_id\":\"del_1\",\"status\":\"DELIVERED\"}";
        assertThat(service.verifySignature(body, "invalid_signature")).isFalse();
        assertThat(service.verifySignature(body, null)).isFalse();
    }
}
