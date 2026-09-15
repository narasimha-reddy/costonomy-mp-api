package com.costonomy.mp.notification.service;

import com.costonomy.mp.notification.domain.AnalyticsEvent;
import com.costonomy.mp.notification.repository.AnalyticsEventStore;
import com.costonomy.mp.notification.web.dto.NotificationDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Product analytics ingest. Doc 08 §7–8.
 *
 * <p><b>Secrets are stripped here, not trusted away.</b> Doc 08 §8 forbids logging
 * an OTP, a card number, a CVV or a provider credential. The client is not the
 * right place to enforce that: a debugging property added in a hurry, a
 * third-party SDK that helpfully attaches form state, and suddenly a card number
 * is in a database that was never meant to hold one. The server drops any property
 * whose <em>name</em> looks like a secret, which is a blunt rule that fails safe —
 * a legitimately-named property being dropped costs one analytics field, and the
 * opposite costs a compliance incident.
 *
 * <p>Properties are also capped in size and count. An analytics table is the
 * easiest place in a system to accidentally store an entire object graph.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    /**
     * Property names that are never stored, matched as substrings of a lowercased
     * key. Deliberately broad: {@code cardNumber}, {@code card_no} and
     * {@code creditCard} all contain "card".
     */
    private static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "otp", "password", "passcode", "pin", "secret", "token", "card", "cvv",
            "cvc", "auth", "credential", "signature", "apikey", "api_key");

    private static final int MAX_PROPERTIES = 30;
    private static final int MAX_VALUE_LENGTH = 500;

    private final AnalyticsEventStore store;
    private final ObjectMapper json;

    /**
     * <b>Deliberately not {@code @Transactional}.</b> Each event commits on its own
     * through {@link AnalyticsEventStore} — see that class for why a shared
     * transaction would lose a whole batch to one duplicate.
     */
    public NotificationDtos.AnalyticsBatchResponse ingest(
            Long userId, NotificationDtos.AnalyticsBatchRequest batch) {

        int accepted = 0;
        int dropped = 0;

        for (var request : batch.events()) {
            var event = new AnalyticsEvent();
            event.setClientEventId(request.clientEventId());
            event.setEventName(request.eventName());
            event.setUserId(userId);
            // Internal ids only (doc 08 §8): which outlet, not which person.
            event.setOutletId(request.outletId());
            event.setSupplierStoreId(request.supplierStoreId());
            event.setSessionId(request.sessionId());
            event.setPlatform(request.platform());
            event.setAppVersion(request.appVersion());
            event.setProperties(sanitise(request.properties()));
            event.setOccurredAt(request.occurredAt() == null
                    ? Instant.now() : request.occurredAt());

            try {
                store.record(event);
                accepted++;
            } catch (DataIntegrityViolationException ex) {
                // uk_analytics_client_event: the client resent a batch it had
                // already delivered. Counting it twice would quietly inflate every
                // funnel metric doc 08 §9 is built from.
                dropped++;
            }
        }

        return new NotificationDtos.AnalyticsBatchResponse(accepted, dropped);
    }

    /**
     * Drop anything that looks like a secret, cap the rest.
     *
     * @return JSON, or null when nothing survived — an empty object stored for
     *         every event is noise in a table that will be the largest in the system
     */
    private String sanitise(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }

        Map<String, Object> safe = new LinkedHashMap<>();
        for (var entry : properties.entrySet()) {
            if (safe.size() >= MAX_PROPERTIES) {
                break;
            }
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_FRAGMENTS.stream().anyMatch(key::contains)) {
                log.debug("Dropped an analytics property named like a secret");
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text && text.length() > MAX_VALUE_LENGTH) {
                value = text.substring(0, MAX_VALUE_LENGTH);
            }
            // Nested structures are flattened away rather than stored. An analytics
            // table is the easiest place in a system to accidentally keep an entire
            // object graph, including the fields nobody audited.
            if (value instanceof Map || value instanceof List) {
                continue;
            }
            safe.put(entry.getKey(), value);
        }

        if (safe.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(safe);
        } catch (Exception ex) {
            return null;
        }
    }
}
