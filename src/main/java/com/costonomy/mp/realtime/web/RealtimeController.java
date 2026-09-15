package com.costonomy.mp.realtime.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.realtime.service.RealtimeQueryService;
import com.costonomy.mp.realtime.service.RealtimeTicketService;
import com.costonomy.mp.realtime.web.dto.RealtimeDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The two REST halves of realtime: getting onto the socket, and living without it.
 * Doc 06 §9, doc 05 §16.
 */
@RestController
@RequestMapping("/api/v1/realtime")
@RequiredArgsConstructor
@Tag(name = "Realtime")
public class RealtimeController {

    private final RealtimeTicketService tickets;
    private final RealtimeQueryService queries;

    @PostMapping("/ticket")
    @Operation(
            summary = "Get a ticket for the live updates socket",
            description = """
                    Single-use and valid for seconds. Connect to the returned `url` with
                    `?ticket=…`; the ticket is spent on connection, so a reconnect needs a
                    new one.

                    A ticket rather than the access token because a browser cannot set
                    headers on a WebSocket, and a token in a query string ends up in access
                    logs. The response also carries the channels this session will be on
                    and the cursor to resume from.
                    """)
    public ApiResponse<RealtimeDtos.TicketResponse> ticket(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(tickets.issue(ActorContext.requireUserId(), deviceId));
    }

    @GetMapping("/events")
    @Operation(
            summary = "Live updates without a socket",
            description = """
                    The polling fallback, and the catch-up after a reconnect. Returns the
                    same events the socket pushes, in the same shape, for the channels the
                    caller's grants allow — there is no channel parameter, because what a
                    caller may see is the server's decision.

                    Pass the last `cursor` you saw. `hasMore` means the page was capped:
                    come straight back rather than waiting for the next interval.

                    These events are a prompt to refresh, not the record. Authoritative
                    state always comes from the resource's own endpoint.
                    """)
    public ApiResponse<RealtimeDtos.EventPage> events(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(queries.since(ActorContext.requireUserId(), cursor, limit));
    }
}
