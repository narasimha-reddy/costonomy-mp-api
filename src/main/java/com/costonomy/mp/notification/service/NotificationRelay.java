package com.costonomy.mp.notification.service;

import com.costonomy.mp.common.outbox.OutboxPublisher;
import com.costonomy.mp.notification.domain.*;
import com.costonomy.mp.notification.repository.NotificationDeliveryRepository;
import com.costonomy.mp.notification.repository.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns domain events into notifications. Doc 08 §1, §4.
 *
 * <p><b>It listens to the outbox</b>, for the same reasons the realtime relay does
 * (D-031): every state change already publishes there, a parallel {@code notify(…)}
 * call in five modules would be a second mechanism to keep in step, and it would be
 * the one people forget — a new event type would simply never reach anybody, with
 * nothing failing to say so. It also inherits the outbox's transactional
 * guarantee: nobody is told about an order that rolled back.
 *
 * <p><b>The body is rendered from named fields, never from the payload.</b> Doc 08
 * §8 forbids an OTP, a card number or a provider secret from being logged, and a
 * notification goes further than a log — it lands on a lock screen and is mirrored
 * to a watch. Interpolating the whole payload would make that a matter of hoping no
 * producer ever adds the wrong field; a template that asks for {@code orderNumber}
 * can only ever contain an order number.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> Like the realtime relay, this
 * runs inside the outbox drain's transaction. Each notification is written by
 * {@code NotificationStore} under {@code REQUIRES_NEW}, so a duplicate for one
 * recipient cannot roll back a batch of a hundred events — or the notifications of
 * everyone else on this one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationRelay {

    private final NotificationStore store;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationAudience audience;
    private final NotificationPreferences preferences;
    private final ObjectMapper json;

    @EventListener
    public void onDomainEvent(OutboxPublisher.DomainEventEnvelope envelope) {
        var rules = NotificationRules.forEvent(envelope.eventType());
        if (rules.isEmpty()) {
            // Most domain events are nobody's inbox item. A location update arrives
            // every few seconds and belongs on a map; pushing it would be the
            // fastest way to get notifications turned off entirely.
            return;
        }

        JsonNode payload;
        try {
            payload = json.readTree(envelope.payload() == null ? "{}" : envelope.payload());
        } catch (Exception ex) {
            log.warn("Could not read the payload of {} for notifications", envelope.eventType());
            return;
        }

        var fields = flatten(payload);

        for (NotificationRule rule : rules) {
            Long scopeId = scopeId(rule, payload);
            if (scopeId == null) {
                continue;
            }

            var recipients = rule.audience() == NotificationRule.Audience.OUTLET
                    ? audience.forOutlet(scopeId)
                    : audience.forSupplierStore(scopeId);

            // Deliberately not excluding the actor. An order rejected at 6am is the
            // shift manager's problem whether or not they placed it, and the person
            // who acted usually wants the receipt anyway.
            recipients.forEach(userId -> notify(userId, rule, envelope, fields));
        }
    }

    private void notify(Long userId, NotificationRule rule,
                        OutboxPublisher.DomainEventEnvelope envelope,
                        Map<String, String> fields) {

        var notification = new Notification();
        notification.setUserId(userId);
        notification.setCategory(rule.category());
        notification.setEventType(envelope.eventType());
        notification.setEventId(envelope.eventId());
        notification.setTitle(rule.title());
        notification.setBody(rule.render(fields));
        notification.setTargetType(rule.targetType());
        notification.setTargetId(targetIdFor(rule, envelope, fields));
        // The rule that produced this row is the only thing that knows which
        // side it was written for, and one event has a rule for each.
        notification.setAudience(rule.audience().name());
        notification.setCritical(rule.critical());

        Notification saved;
        try {
            saved = store.create(notification);
        } catch (DataIntegrityViolationException ex) {
            // uk_notification_user_event: the outbox re-offered an event it had
            // already fanned out. Telling someone twice is worse than not at all.
            log.debug("Notification for user {} and event {} already exists",
                    userId, envelope.eventId());
            return;
        }

        for (NotificationChannel channel : rule.channels()) {
            if (!channel.isOutbound()) {
                // IN_APP is the notification. There is nothing to attempt, and
                // tracking whether we successfully wrote to our own database would
                // be a delivery record about nothing.
                continue;
            }
            if (!preferences.allows(userId, rule.category(), channel, rule.critical())) {
                continue;
            }
            queue(saved, userId, channel);
        }
    }

    /** One delivery row per destination: a user with two phones gets two pushes. */
    /**
     * What this notification should open.
     *
     * <p>The event's own aggregate, unless the rule names a payload field instead.
     * A delivery event's aggregate is the delivery, and neither side has a screen
     * keyed by one — so those rules point at the order, and a notification that
     * says "a driver is collecting your order" opens that order.
     *
     * <p>Falls back to the aggregate rather than failing: a missing or unreadable
     * field means the payload changed shape, and an inbox row that opens the wrong
     * thing is still better than an event that is never delivered.
     */
    private Long targetIdFor(NotificationRule rule,
                             OutboxPublisher.DomainEventEnvelope envelope,
                             Map<String, String> fields) {
        if (rule.targetIdField() == null) {
            return envelope.aggregateId();
        }
        String raw = fields.get(rule.targetIdField());
        if (raw == null || raw.isBlank()) {
            log.warn("{} names target field {} but the payload has none",
                    rule.eventType(), rule.targetIdField());
            return envelope.aggregateId();
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("{} target field {} is not a number: {}",
                    rule.eventType(), rule.targetIdField(), raw);
            return envelope.aggregateId();
        }
    }

    private void queue(Notification notification, Long userId, NotificationChannel channel) {
        if (channel == NotificationChannel.PUSH) {
            var devices = audience.devicesOf(userId);
            if (devices.isEmpty()) {
                // No registered device. Not a failure — the notification is in
                // their inbox, and a delivery row would be a permanent failure
                // against a phone that does not exist.
                return;
            }
            devices.forEach(device -> deliveries.save(
                    delivery(notification, channel, device.pushToken(), device.id())));
            return;
        }

        String phone = audience.phoneOf(userId);
        if (phone != null) {
            deliveries.save(delivery(notification, channel, phone, null));
        }
    }

    private NotificationDelivery delivery(Notification notification, NotificationChannel channel,
                                          String destination, Long deviceId) {
        var delivery = new NotificationDelivery();
        delivery.setNotificationId(notification.getId());
        delivery.setChannel(channel);
        delivery.setStatus(NotificationDeliveryStatus.QUEUED);
        delivery.setDestination(destination);
        delivery.setDeviceId(deviceId);
        delivery.setNextAttemptAt(Instant.now());
        return delivery;
    }

    private Long scopeId(NotificationRule rule, JsonNode payload) {
        var node = payload.get(rule.audience() == NotificationRule.Audience.OUTLET
                ? "outletId" : "supplierStoreId");
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return node.isNumber() ? node.asLong() : Long.parseLong(node.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * The payload's top-level scalars, as strings.
     *
     * <p>Scalars only: a nested object cannot be interpolated into a sentence, and
     * flattening one would put JSON in front of a user. A template can only use
     * what it names anyway — this is the set it may choose from.
     */
    private Map<String, String> flatten(JsonNode payload) {
        Map<String, String> fields = new HashMap<>();
        payload.fields().forEachRemaining(entry -> {
            var value = entry.getValue();
            if (value != null && !value.isNull() && value.isValueNode()) {
                fields.put(entry.getKey(), stringify(entry.getKey(), value));
            }
        });
        return fields;
    }

    /**
     * Money fields, by name.
     *
     * <p>A {@code DECIMAL(19,4)} reaches a template as "35000.0000", and
     * "You have 35000.0000 of credit" is not a sentence anyone should be sent.
     *
     * <p>Named rather than inferred: a rule like "format anything with decimals"
     * would turn a GST rate of 5.0000 into ₹5.00. A new money field is one line
     * here, and forgetting it degrades to the raw number rather than to a wrong
     * currency.
     */
    private static final Set<String> MONEY_FIELDS = Set.of(
            "amount", "approvedLimit", "requestedLimit", "totalAmount", "acceptedAmount",
            "outstanding", "netAmount", "grossAmount", "claimedAmount", "fee", "available");

    private static final NumberFormat RUPEES = NumberFormat.getCurrencyInstance(
            Locale.forLanguageTag("en-IN"));

    private String stringify(String key, JsonNode value) {
        if (!MONEY_FIELDS.contains(key)) {
            return value.asText();
        }
        try {
            return RUPEES.format(new java.math.BigDecimal(value.asText()));
        } catch (NumberFormatException ex) {
            // Not a number after all. The raw text is still better than nothing.
            return value.asText();
        }
    }
}
