package com.costonomy.mp.delivery.provider.pidge;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.delivery.domain.Delivery;
import com.costonomy.mp.delivery.domain.DeliveryStatus;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.repository.DeliveryRepository;
import com.costonomy.mp.delivery.service.DeliveryEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Handles incoming Pidge webhooks:
 * <ul>
 *   <li>HMAC-SHA256 signature verification</li>
 *   <li>Idempotent deduplication on provider event id</li>
 *   <li>Out-of-order event protection</li>
 *   <li>Delivery milestone progression (DRIVER_ASSIGNED, PICKED_UP, DELIVERED, etc.)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PidgeWebhookService {

    private final PidgeProperties properties;
    private final DeliveryRepository deliveries;
    private final DeliveryEventService eventService;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    /**
     * Verify HMAC-SHA256 signature and process Pidge callback event.
     */
    @Transactional
    public boolean handle(String rawBody, String signature) {
        if (!verifySignature(rawBody, signature)) {
            log.warn("Rejected Pidge webhook: invalid signature");
            auditService.record(null, null, "PIDGE_WEBHOOK_REJECTED", "DELIVERY",
                    null, null, null, "Signature verification failed", "PIDGE");
            return false;
        }

        try {
            // Pidge posts the same order object the GET call returns, not wrapped in "data" (their webhook docs).
            JsonNode root = objectMapper.readTree(rawBody);
            return process(root);

        } catch (Exception ex) {
            log.error("Failed to parse or process Pidge webhook", ex);
            return false;
        }
    }

    /**
     * Apply one Pidge order object to the delivery it belongs to. Everything after the signature check: the webhook
     * calls it with the posted body, and the sandbox advance (D-188) with the order Pidge's dummy GET returned.
     * Throws on a failure so a caller decides what that means; {@link #handle} turns it into a retry request.
     *
     * @return true when handled or deliberately ignored (unknown delivery), false when the object is unusable
     */
    @Transactional
    public boolean process(JsonNode root) {
        String deliveryId = root.path("id").asText(null);
        if (deliveryId == null || deliveryId.isBlank() || !root.hasNonNull("status")) {
            log.warn("Pidge order object missing id or status");
            return false;
        }
        var delivery = deliveries.findByProviderCodeAndProviderDeliveryId(
                PidgeDeliveryProvider.CODE, deliveryId).orElse(null);

        if (delivery == null) {
            log.info("Received Pidge webhook for unknown or unlinked delivery {}", deliveryId);
            return true; // Acknowledge to prevent provider retries
        }

        var state = PidgeOrderState.parse(deliveryId, root);

        if (state.riderName() != null) {
            eventService.recordDriver(delivery, state.riderName(), state.riderPhone(), null);
        }
        boolean hasFix = state.latitude() != null && state.longitude() != null;
        // One instant for both attempts below, so the same fix is one row (the dedupe is on recordedAt).
        Instant fixAt = state.locationAt() == null ? Instant.now() : state.locationAt();
        if (hasFix) {
            // Before the stages: a fix that arrives with the last stage (delivered) is stored while the
            // delivery is still trackable.
            eventService.recordLocation(delivery, state.latitude(), state.longitude(), null, null, fixAt);
        }

        // Every stage Pidge reports, oldest first, so a delivery that moved several stages between two hits
        // walks them in order (idempotent and out-of-order safe in DeliveryEventService).
        var events = new java.util.ArrayList<>(state.events());
        java.util.Collections.reverse(events);
        for (var event : events) {
            String disposition = eventService.apply(delivery, event);
            log.info("Processed Pidge webhook event {} for delivery {}: disposition={}",
                    event.providerEventId(), delivery.getId(), disposition);
        }
        if (hasFix) {
            // Again after the stages: the first fix of a stage that assigns the rider (out for pickup) arrives
            // while the delivery is not yet trackable, and is only storable once the status has moved (D-191).
            // A fix the first call already stored is skipped by the recordedAt dedupe.
            eventService.recordLocation(delivery, state.latitude(), state.longitude(), null, null, fixAt);
        }
        return true;
    }

    /**
     * Constant-time HMAC-SHA256 signature verification.
     */
    public boolean verifySignature(String rawBody, String signature) {
        String secret = properties.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // Fail closed: an unconfigured secret accepts nothing. An unsigned webhook can mark a
            // delivery delivered, and ProductionProviderGuard refuses to start without a secret.
            log.warn("Pidge webhook rejected: no webhook secret configured");
            return false;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hash);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.error("Error verifying Pidge webhook signature", ex);
            return false;
        }
    }
}
