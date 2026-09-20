package com.costonomy.mp.chat.web;

import com.costonomy.mp.chat.service.ChatService;
import com.costonomy.mp.chat.web.dto.ChatDtos;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Chat between an outlet and a supplier store. D-095.
 *
 * <p>Both sides are served by the same endpoints below the inbox: which side a
 * caller is on comes from their grants, never from a parameter, exactly as
 * credit does.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Chat")
public class ChatController {

    private final ChatService chat;

    @GetMapping("/outlets/{outletId}/chat/threads")
    @Operation(summary = "A kitchen's conversations",
            description = """
                    Most recent first, with the unread count for this side and whether a
                    message can be sent. `canSend` is the server's answer — either party's
                    chat being switched off closes the composer, and the app must not work
                    that out from a flag it holds about one of them.
                    """)
    public ApiResponse<List<ChatDtos.ThreadResponse>> forOutlet(@PathVariable Long outletId) {
        return ApiResponse.ok(chat.forOutlet(ActorContext.requireUserId(), outletId));
    }

    @GetMapping("/supplier-stores/{storeId}/chat/threads")
    @Operation(summary = "A store's conversations")
    public ApiResponse<List<ChatDtos.ThreadResponse>> forStore(@PathVariable Long storeId) {
        return ApiResponse.ok(chat.forStore(ActorContext.requireUserId(), storeId));
    }

    @PostMapping("/outlets/{outletId}/chat/threads")
    @Operation(summary = "Open the conversation with a supplier",
            description = """
                    Returns the existing thread when there is one, so a double tap cannot
                    make two. Only available where the two have already traded — a request
                    sent or an order placed — which is what keeps this from becoming a way
                    to message strangers.
                    """)
    public ApiResponse<ChatDtos.ThreadResponse> open(
            @PathVariable Long outletId,
            @Valid @RequestBody ChatDtos.OpenThreadRequest request) {
        return ApiResponse.ok(chat.open(
                ActorContext.requireUserId(), outletId, request.supplierStoreId()));
    }

    @PostMapping("/supplier-stores/{storeId}/chat/threads")
    @Operation(summary = "Open the conversation with a restaurant",
            description = """
                    The same call from the other side, for a supplier answering a question
                    about an order they are filling. Available only where the two have
                    already traded, which is what keeps this from becoming a way to
                    message strangers.
                    """)
    public ApiResponse<ChatDtos.ThreadResponse> openFromStore(
            @PathVariable Long storeId,
            @Valid @RequestBody ChatDtos.OpenThreadFromStoreRequest request) {
        return ApiResponse.ok(chat.open(
                ActorContext.requireUserId(), request.outletId(), storeId));
    }

    @GetMapping("/chat/threads/{id}")
    @Operation(summary = "One conversation and its messages",
            description = """
                    The most recent page by default. Pass `afterId` to ask only for what
                    has arrived since — how an open thread catches up without re-reading
                    itself.
                    """)
    public ApiResponse<ChatDtos.ThreadDetailResponse> thread(
            @PathVariable Long id,
            @RequestParam(required = false) Long afterId) {
        return ApiResponse.ok(chat.thread(ActorContext.requireUserId(), id, afterId));
    }

    @PostMapping("/chat/threads/{id}/messages")
    @Operation(summary = "Say something",
            description = """
                    A body, a shared request or order, or both. An attachment is a typed
                    reference and is checked against this pair before it is stored — an id
                    from somebody else's order would be a link into a tenant the reader
                    cannot see.

                    Refused while either party's chat is switched off.
                    """)
    public ApiResponse<ChatDtos.MessageResponse> send(
            @PathVariable Long id,
            @Valid @RequestBody ChatDtos.SendMessageRequest request) {
        return ApiResponse.ok(chat.send(ActorContext.requireUserId(), id, request));
    }

    @PostMapping("/chat/threads/{id}/read")
    @Operation(summary = "Mark this conversation read for your side",
            description = "Per side, not per person: three colleagues each carrying "
                    + "their own badge for one conversation is three people ignoring it.")
    public ApiResponse<Map<String, Object>> read(@PathVariable Long id) {
        chat.read(ActorContext.requireUserId(), id);
        return ApiResponse.ok(Map.of("read", true));
    }

    @GetMapping("/chat/threads/{id}/shareables")
    @Operation(summary = "Requests and orders that can be shared into this conversation",
            description = "Scoped to the pair, because the point of sharing a link is "
                    + "that the other side can open it.")
    public ApiResponse<List<ChatDtos.ShareableResponse>> shareables(@PathVariable Long id) {
        return ApiResponse.ok(chat.shareables(ActorContext.requireUserId(), id));
    }
}
