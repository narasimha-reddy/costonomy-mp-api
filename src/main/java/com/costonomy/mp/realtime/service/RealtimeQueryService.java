package com.costonomy.mp.realtime.service;

import com.costonomy.mp.realtime.repository.RealtimeEventRepository;
import com.costonomy.mp.realtime.web.dto.RealtimeDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The polling fallback, and the reconnect catch-up. Doc 06 §9, doc 05 §16.
 *
 * <p>Reads the same rows the socket pushes, so a client that polls and a client
 * that streams see the same sequence — and a client that was offline resumes
 * exactly where it stopped rather than guessing what it missed.
 *
 * <p><b>The channels come from the caller's grants, never from the request.</b> A
 * client cannot ask for a channel; it asks for "my events", and the server decides
 * what that means. There is no channel parameter on the endpoint for the same
 * reason there is no outlet parameter on {@code /auth/me}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealtimeQueryService {

    private static final int MAX_PAGE = 200;

    private final RealtimeEventRepository events;
    private final RealtimeEntitlements entitlements;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public RealtimeDtos.EventPage since(Long userId, Long cursor, Integer limit) {
        var channels = entitlements.channelNamesFor(userId);
        if (channels.isEmpty()) {
            return new RealtimeDtos.EventPage(cursor == null ? 0L : cursor, false, List.of());
        }

        int size = limit == null ? MAX_PAGE : Math.min(Math.max(limit, 1), MAX_PAGE);
        long from = cursor == null ? 0L : cursor;

        var page = events.since(channels, from, PageRequest.of(0, size));

        var responses = new ArrayList<RealtimeDtos.EventResponse>(page.size());
        long newest = from;
        for (var event : page) {
            newest = Math.max(newest, event.getId());
            responses.add(new RealtimeDtos.EventResponse(
                    event.getId(), event.getChannel(), event.getEventType(),
                    event.getAggregateType(), event.getAggregateId(),
                    readPayload(event.getPayload()), event.getOccurredAt()));
        }

        // hasMore when the page filled: the client should come straight back
        // rather than wait out its interval and fall further behind.
        return new RealtimeDtos.EventPage(newest, page.size() == size, responses);
    }

    /** Where a client with no history should start: now, not the beginning of time. */
    @Transactional(readOnly = true)
    public Long currentCursor(Long userId) {
        var channels = entitlements.channelNamesFor(userId);
        if (channels.isEmpty()) {
            return 0L;
        }
        Long cursor = events.latestCursor(channels);
        return cursor == null ? 0L : cursor;
    }

    private com.fasterxml.jackson.databind.JsonNode readPayload(String payload) {
        try {
            return json.readTree(payload == null ? "{}" : payload);
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }
}
