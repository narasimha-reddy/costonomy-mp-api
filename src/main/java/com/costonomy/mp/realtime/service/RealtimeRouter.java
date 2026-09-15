package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.domain.RealtimeChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides which channels an event belongs on.
 *
 * <p>Reads the ids out of the event's own payload rather than re-querying the
 * database. Every domain event that concerns a tenant already carries
 * {@code outletId} or {@code supplierStoreId} — they were put there for
 * notifications and analytics — and a second lookup would be a second answer to a
 * question the producer already answered.
 *
 * <p><b>An event routed nowhere is dropped, and that is correct.</b> Plenty of
 * domain events are nobody's business in realtime: a catalog import finishing, a
 * settlement being calculated. Routing on a guess instead would put events on a
 * channel by accident, and on this transport an accident is a disclosure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeRouter {

    private final ObjectMapper json;

    public Set<RealtimeChannel> channelsFor(String aggregateType, String payloadJson) {
        Set<RealtimeChannel> channels = new LinkedHashSet<>();

        JsonNode payload;
        try {
            payload = json.readTree(payloadJson == null ? "{}" : payloadJson);
        } catch (Exception ex) {
            log.warn("Could not route a {} event: unreadable payload", aggregateType);
            return channels;
        }

        Long outletId = asLong(payload, "outletId");
        if (outletId != null) {
            channels.add(RealtimeChannel.outlet(outletId));
        }

        Long storeId = asLong(payload, "supplierStoreId");
        if (storeId != null) {
            channels.add(RealtimeChannel.supplierStore(storeId));
        }

        return channels;
    }

    private Long asLong(JsonNode payload, String field) {
        var node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
