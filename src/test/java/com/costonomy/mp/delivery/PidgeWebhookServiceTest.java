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
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.provider.UnclaimedDeliveryEventHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    // --- Events for consignments the supplier flow does not own (D-197) ---

    private static final String ORDER = "{\"id\":\"p-77\",\"status\":\"PLACED\"}";

    private JsonNode order() throws Exception {
        return new ObjectMapper().readTree(ORDER);
    }

    private void handlers(UnclaimedDeliveryEventHandler... handlers) {
        ReflectionTestUtils.setField(service, "unclaimedHandlers", java.util.List.of(handlers));
    }

    @Test
    @DisplayName("an event for no supplier delivery is offered to the other handlers, and a claim ends it")
    void unknownDeliveryIsOfferedToHandlers() throws Exception {
        when(deliveries.findByProviderCodeAndProviderDeliveryId("PIDGE", "p-77")).thenReturn(Optional.empty());
        var declines = mock(UnclaimedDeliveryEventHandler.class);
        var claims = mock(UnclaimedDeliveryEventHandler.class);
        var neverAsked = mock(UnclaimedDeliveryEventHandler.class);
        when(declines.handle(eq("PIDGE"), eq("p-77"), any())).thenReturn(false);
        when(claims.handle(eq("PIDGE"), eq("p-77"), any())).thenReturn(true);
        handlers(declines, claims, neverAsked);

        assertThat(service.process(order())).isTrue();

        verify(declines).handle(eq("PIDGE"), eq("p-77"), any());
        verify(claims).handle(eq("PIDGE"), eq("p-77"), any());
        verifyNoInteractions(neverAsked);
        verifyNoInteractions(eventService);
    }

    @Test
    @DisplayName("an event nobody owns is still acknowledged, so the provider stops retrying")
    void unclaimedEventIsStillAcknowledged() throws Exception {
        when(deliveries.findByProviderCodeAndProviderDeliveryId("PIDGE", "p-77")).thenReturn(Optional.empty());
        var declines = mock(UnclaimedDeliveryEventHandler.class);
        when(declines.handle(any(), any(), any())).thenReturn(false);
        handlers(declines);

        assertThat(service.process(order())).isTrue();
        verifyNoInteractions(eventService);
    }

    @Test
    @DisplayName("a supplier delivery is handled as before and the other handlers are never consulted")
    void supplierDeliveryNeverReachesOtherHandlers() throws Exception {
        when(deliveries.findByProviderCodeAndProviderDeliveryId("PIDGE", "p-77"))
                .thenReturn(Optional.of(new Delivery()));
        var handler = mock(UnclaimedDeliveryEventHandler.class);
        handlers(handler);

        service.process(order());

        verify(handler, never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("with no other handlers registered, behaviour is exactly what it was")
    void noHandlersIsUnchanged() throws Exception {
        when(deliveries.findByProviderCodeAndProviderDeliveryId("PIDGE", "p-77")).thenReturn(Optional.empty());
        assertThat(service.process(order())).isTrue();
    }
}
