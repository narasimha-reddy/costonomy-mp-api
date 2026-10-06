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
            JsonNode root = objectMapper.readTree(rawBody);
            String eventId = root.path("event_id").asText(root.path("id").asText(null));
            String deliveryId = root.path("pidge_delivery_id").asText(root.path("delivery_id").asText(null));
            String statusStr = root.path("status").asText(root.path("event_type").asText(null));

            if (deliveryId == null || statusStr == null) {
                log.warn("Pidge webhook payload missing required fields: {}", rawBody);
                return false;
            }

            var delivery = deliveries.findByProviderCodeAndProviderDeliveryId(
                    PidgeDeliveryProvider.CODE, deliveryId).orElse(null);

            if (delivery == null) {
                log.info("Received Pidge webhook for unknown or unlinked delivery {}", deliveryId);
                return true; // Acknowledge to prevent provider retries
            }

            var providerStatus = PidgeApiClient.mapPidgeStatus(statusStr);
            String description = root.path("description").asText("Status update: " + statusStr);
            Instant occurredAt = root.has("timestamp")
                    ? Instant.ofEpochMilli(root.path("timestamp").asLong())
                    : Instant.now();

            // Record tracking URL if present in webhook
            if (root.has("tracking_url")) {
                String trackingUrl = root.path("tracking_url").asText(null);
                if (trackingUrl != null && !trackingUrl.isBlank()) {
                    delivery.setTrackingUrl(trackingUrl);
                    deliveries.save(delivery);
                }
            }

            // Record driver details if present in webhook
            if (root.has("driver")) {
                JsonNode driver = root.path("driver");
                String name = driver.path("name").asText(null);
                String phone = driver.path("phone").asText(null);
                String vehicle = driver.path("vehicle_number").asText(null);
                if (name != null) {
                    eventService.recordDriver(delivery, name, phone, vehicle);
                }
            }

            // Record location update if coordinates are present
            if (root.has("location")) {
                JsonNode loc = root.path("location");
                if (loc.has("lat") && loc.has("lng")) {
                    BigDecimal lat = new BigDecimal(loc.path("lat").asText());
                    BigDecimal lng = new BigDecimal(loc.path("lng").asText());
                    Double bearing = loc.has("bearing") ? loc.path("bearing").asDouble() : null;
                    Double speed = loc.has("speed") ? loc.path("speed").asDouble() : null;
                    eventService.recordLocation(delivery, lat, lng, bearing, speed, occurredAt);
                }
            }

            // Apply lifecycle event via DeliveryEventService (idempotent & out-of-order safe)
            var event = new DeliveryProvider.ProviderEvent(
                    eventId != null ? eventId : "pidge_evt_" + deliveryId + "_" + statusStr + "_" + occurredAt.toEpochMilli(),
                    providerStatus, description, occurredAt);

            String disposition = eventService.apply(delivery, event);
            log.info("Processed Pidge webhook event {} for delivery {}: disposition={}",
                    eventId, delivery.getId(), disposition);
            return true;

        } catch (Exception ex) {
            log.error("Failed to parse or process Pidge webhook", ex);
            return false;
        }
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
