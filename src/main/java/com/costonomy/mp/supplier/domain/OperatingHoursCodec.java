package com.costonomy.mp.supplier.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes {@link OperatingHours} as the JSON the column holds.
 *
 * <p>Its own class rather than annotations on the entity because the shape has to
 * survive a value written by an older build, a value written by hand, and a
 * column that is simply null — and in all three cases the right answer is the
 * defaults, never an exception. A store whose hours fail to parse must still be
 * findable; the alternative is that one malformed row removes a supplier from
 * the marketplace and nothing says why.
 */
@Slf4j
public final class OperatingHoursCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OperatingHoursCodec() {
    }

    public static OperatingHours read(String raw) {
        if (raw == null || raw.isBlank()) {
            return OperatingHours.defaults();
        }
        try {
            var node = JSON.readTree(raw);
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            var daysNode = node.get("days");
            if (daysNode != null && daysNode.isArray()) {
                daysNode.forEach(day -> {
                    try {
                        days.add(DayOfWeek.valueOf(day.asText().toUpperCase()));
                    } catch (IllegalArgumentException ignored) {
                        // One unreadable day is not a reason to close the store.
                    }
                });
            }
            return new OperatingHours(
                    days,
                    parseTime(node, "opensAt", OperatingHours.DEFAULT_OPEN),
                    parseTime(node, "closesAt", OperatingHours.DEFAULT_CLOSE));
        } catch (Exception e) {
            log.warn("Unreadable operating hours, falling back to defaults: {}", raw, e);
            return OperatingHours.defaults();
        }
    }

    public static String write(OperatingHours hours) {
        ObjectNode node = JSON.createObjectNode();
        node.putPOJO("days", hours.dayNames());
        node.put("opensAt", hours.opensAt().toString());
        node.put("closesAt", hours.closesAt().toString());
        return node.toString();
    }

    /** Parse the wire form a client sends: day names plus HH:mm. */
    public static OperatingHours fromParts(List<String> days, String opensAt, String closesAt) {
        Set<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        if (days != null) {
            days.forEach(day -> {
                try {
                    parsed.add(DayOfWeek.valueOf(day.trim().toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // Ignored here; validated where the request is checked.
                }
            });
        }
        return new OperatingHours(parsed, time(opensAt), time(closesAt));
    }

    private static LocalTime parseTime(com.fasterxml.jackson.databind.JsonNode node,
                                       String field, LocalTime fallback) {
        var value = node.get(field);
        LocalTime parsed = value == null ? null : time(value.asText());
        return parsed == null ? fallback : parsed;
    }

    private static LocalTime time(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
