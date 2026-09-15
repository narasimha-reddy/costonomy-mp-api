package com.costonomy.mp.realtime.web.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class RealtimeDtos {

    private RealtimeDtos() {
    }

    /**
     * What a client needs to open a socket.
     *
     * @param ticket    single-use, short-lived, returned exactly once
     * @param cursor    the newest event id on these channels. A fresh client starts
     *                  here rather than at zero, so opening the app does not replay
     *                  a week of history it has already seen elsewhere.
     * @param channels  what this ticket may join, so the client does not have to
     *                  guess and be refused
     */
    public record TicketResponse(
            String ticket,
            String url,
            Instant expiresAt,
            Long cursor,
            List<String> channels) {
    }

    /**
     * One event, in the same shape on every transport.
     *
     * <p>The socket frame, the polling response and a reconnect replay are all
     * this record — a client writes one handler, and a fact cannot exist on one
     * transport and not another.
     *
     * @param cursor the id to resume from after this event
     */
    public record EventResponse(
            Long cursor,
            String channel,
            String eventType,
            String aggregateType,
            Long aggregateId,
            JsonNode payload,
            Instant occurredAt) {
    }

    /**
     * A page of events.
     *
     * @param cursor    where to resume. Always advances, even when {@code events}
     *                  is empty, so a quiet channel does not make a client re-read
     *                  the same window forever.
     * @param hasMore   true when the page was capped — the client should poll again
     *                  immediately rather than waiting for the next interval
     */
    public record EventPage(
            Long cursor,
            boolean hasMore,
            List<EventResponse> events) {
    }
}
