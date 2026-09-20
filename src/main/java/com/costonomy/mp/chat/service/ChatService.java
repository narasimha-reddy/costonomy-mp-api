package com.costonomy.mp.chat.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.chat.domain.ChatAttachmentType;
import com.costonomy.mp.chat.domain.ChatMessage;
import com.costonomy.mp.chat.domain.ChatReadState;
import com.costonomy.mp.chat.domain.ChatSide;
import com.costonomy.mp.chat.domain.ChatThread;
import com.costonomy.mp.chat.repository.ChatMessageRepository;
import com.costonomy.mp.chat.repository.ChatReadStateRepository;
import com.costonomy.mp.chat.repository.ChatThreadRepository;
import com.costonomy.mp.chat.web.dto.ChatDtos;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Conversations between an outlet and a supplier store. D-095.
 *
 * <p><b>A thread is a pair, not an order.</b> One ongoing conversation per
 * outlet–store, with requests and orders shared into it as links. A thread per
 * order would give a kitchen nine conversations with one supplier and no way to
 * ask a general question, and the first thing anybody would do is use the newest
 * one for everything.
 *
 * <p><b>It exists only where the two have traded.</b> A thread can be opened
 * from an outlet that has sent this store a request or placed an order with
 * them, which keeps an inbox to suppliers a kitchen actually deals with and
 * keeps the marketplace from becoming a way to message strangers.
 *
 * <p><b>Switching chat off closes the composer, not the record.</b> Either
 * party's flag stops new messages; the history stays readable, because what a
 * supplier agreed to in writing is exactly the thing somebody needs after
 * support has been called. The server decides this — {@code canSend} on the
 * thread is the answer, and the app must not work it out from a flag it happens
 * to hold about one of the two parties.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    /** What one screen of history is. Older messages come from the cursor. */
    private static final int PAGE = 50;

    /** How many recent orders and requests the share picker offers. */
    private static final int SHAREABLES = 20;

    private final ChatThreadRepository threads;
    private final ChatMessageRepository messages;
    private final ChatReadStateRepository readStates;
    private final ChatDirectory directory;
    private final AccessControlService accessControl;
    private final OutboxService outbox;

    // ── Inbox ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChatDtos.ThreadResponse> forOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.CHAT_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        return threads.findByOutletIdOrderByLastMessageAtDescIdDesc(outletId).stream()
                .map(thread -> describe(thread, ChatSide.RESTAURANT))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatDtos.ThreadResponse> forStore(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.CHAT_VIEW_SUPPLIER,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        return threads.findBySupplierStoreIdOrderByLastMessageAtDescIdDesc(storeId).stream()
                .map(thread -> describe(thread, ChatSide.SUPPLIER))
                .toList();
    }

    /**
     * Open the conversation, or return the one that exists.
     *
     * <p>Idempotent by the unique pair, so a double tap on "Message" cannot
     * produce two threads.
     *
     * <p><b>Either side may open it, and only where they have traded.</b> D-095
     * limited this to the restaurant, reasoning that a supplier opening threads
     * to kitchens is how a marketplace acquires a spam problem. The `traded`
     * check is what actually prevents that — a supplier with an order from this
     * outlet is not a stranger to it — and the restriction was instead stopping
     * a supplier answering a question about an order they are filling. That is
     * the wrong half to block.
     */
    @Transactional
    public ChatDtos.ThreadResponse open(Long actorId, Long outletId, Long supplierStoreId) {
        var pair = directory.pair(outletId, supplierStoreId)
                .orElseThrow(() -> new NotFoundException("SupplierStore", supplierStoreId));

        ChatSide side;
        if (accessControl.has(actorId, Permissions.CHAT_SEND,
                ScopeType.OUTLET, outletId)) {
            side = ChatSide.RESTAURANT;
        } else if (accessControl.has(actorId, Permissions.CHAT_SEND_SUPPLIER,
                ScopeType.SUPPLIER_STORE, supplierStoreId)) {
            side = ChatSide.SUPPLIER;
        } else {
            // The 404 every scoped read here gives: saying the pair exists would
            // be the disclosure the rule is there to prevent.
            throw new NotFoundException("ChatThread", supplierStoreId);
        }

        if (!pair.traded()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    side == ChatSide.RESTAURANT
                            ? "You can message %s once you have sent them a request or placed an order."
                                    .formatted(pair.storeName())
                            : "You can message %s once they have sent you a request or an order."
                                    .formatted(pair.outletName()));
        }

        var thread = threads.findByOutletIdAndSupplierStoreId(outletId, supplierStoreId)
                .orElseGet(() -> {
                    var fresh = new ChatThread();
                    fresh.setOutletId(outletId);
                    fresh.setSupplierStoreId(supplierStoreId);
                    return threads.saveAndFlush(fresh);
                });

        return describe(thread, side);
    }

    // ── One thread ───────────────────────────────────────────────────────

    /**
     * A thread and its history.
     *
     * <p>{@code afterId} asks only for what is new, which is how an open thread
     * catches up without re-reading itself. Without it, the most recent page.
     */
    @Transactional(readOnly = true)
    public ChatDtos.ThreadDetailResponse thread(Long actorId, Long threadId, Long afterId) {
        var thread = load(threadId);
        var side = sideFor(actorId, thread, false);

        List<ChatMessage> page = afterId == null
                ? reversed(messages.findByChatThreadIdOrderByIdDesc(
                        threadId, PageRequest.of(0, PAGE)))
                : messages.findByChatThreadIdAndIdGreaterThanOrderByIdAsc(threadId, afterId);

        return new ChatDtos.ThreadDetailResponse(
                describe(thread, side),
                page.stream().map(ChatService::toResponse).toList());
    }

    /**
     * Say something.
     *
     * <p>Empty is refused rather than stored: a blank line is a tap somebody did
     * not mean, and it would sit in the other side's inbox as a notification
     * about nothing.
     */
    @Transactional
    public ChatDtos.MessageResponse send(
            Long actorId, Long threadId, ChatDtos.SendMessageRequest request) {

        var thread = load(threadId);
        var side = sideFor(actorId, thread, true);
        var pair = pairFor(thread);

        if (!pair.chatEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, disabledReason(pair));
        }

        String body = request.body() == null ? null : request.body().trim();
        boolean hasBody = body != null && !body.isEmpty();
        boolean hasAttachment = request.attachmentType() != null && request.attachmentId() != null;
        if (!hasBody && !hasAttachment) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Write something, or share a request or an order.");
        }

        var message = new ChatMessage();
        message.setChatThreadId(threadId);
        message.setSenderSide(side);
        message.setSenderUserId(actorId);
        message.setBody(hasBody ? body : null);

        if (hasAttachment) {
            // Checked against the pair, never trusted from the client: an
            // attachment is a link the other side will tap, and an id belonging
            // to somebody else's order is a link into a tenant they cannot see.
            String reference = directory.attachmentReference(
                            thread.getOutletId(), thread.getSupplierStoreId(),
                            request.attachmentType(), request.attachmentId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "That isn't something you can share in this conversation."));
            message.setAttachmentType(request.attachmentType());
            message.setAttachmentId(request.attachmentId());
            message.setAttachmentReference(reference);
        }

        messages.saveAndFlush(message);

        Instant now = Instant.now();
        thread.setLastMessageAt(now);
        thread.setLastMessageSide(side);
        thread.setLastMessagePreview(preview(message));
        threads.save(thread);

        // The sender has read their own message by definition. Without this the
        // thread they are looking at shows an unread badge for what they typed.
        markRead(thread.getId(), side, message.getId());

        // Realtime and notifications both hang off this, like every other event
        // here: nothing calls a broadcaster or a notifier directly (D-031, D-040).
        // Named for the side being told, not the side that spoke: a rule maps an
        // event to one audience, and a message has to reach whoever did not send
        // it. The payload carries both ids so the relay can address either.
        outbox.publish(side.messageEventName(), "CHAT_THREAD", thread.getId(),
                Map.of("threadId", thread.getId(),
                        "outletId", thread.getOutletId(),
                        "supplierStoreId", thread.getSupplierStoreId(),
                        "senderSide", side.name(),
                        "senderName", side == ChatSide.RESTAURANT
                                ? pair.outletName() : pair.storeName(),
                        "messageId", message.getId()),
                actorId, now);

        return toResponse(message);
    }

    /** Mark everything visible as read, for this side. */
    @Transactional
    public void read(Long actorId, Long threadId) {
        var thread = load(threadId);
        var side = sideFor(actorId, thread, false);

        var latest = messages.findByChatThreadIdOrderByIdDesc(threadId, PageRequest.of(0, 1));
        markRead(threadId, side, latest.isEmpty() ? 0L : latest.get(0).getId());
    }

    /** Requests and orders that could be shared into this conversation. */
    @Transactional(readOnly = true)
    public List<ChatDtos.ShareableResponse> shareables(Long actorId, Long threadId) {
        var thread = load(threadId);
        sideFor(actorId, thread, false);

        return directory.shareables(
                        thread.getOutletId(), thread.getSupplierStoreId(), SHAREABLES).stream()
                .map(row -> new ChatDtos.ShareableResponse(
                        row.type(), row.id(), row.reference(), row.status(), row.createdAt()))
                .toList();
    }

    // ── Internals ────────────────────────────────────────────────────────

    private ChatThread load(Long threadId) {
        return threads.findById(threadId)
                .orElseThrow(() -> new NotFoundException("ChatThread", threadId));
    }

    /**
     * Which side the caller is on, refusing if they are on neither.
     *
     * <p>Tries the restaurant's scope, then the supplier's. A user scoped to
     * neither gets the 404 {@code requireScoped} gives every other cross-tenant
     * read here — telling them the thread exists would be the disclosure the
     * rule exists to prevent.
     */
    private ChatSide sideFor(Long actorId, ChatThread thread, boolean writing) {
        String restaurantPermission = writing
                ? Permissions.CHAT_SEND : Permissions.CHAT_VIEW;
        String supplierPermission = writing
                ? Permissions.CHAT_SEND_SUPPLIER : Permissions.CHAT_VIEW_SUPPLIER;

        if (accessControl.has(actorId, restaurantPermission,
                ScopeType.OUTLET, thread.getOutletId())) {
            return ChatSide.RESTAURANT;
        }
        if (accessControl.has(actorId, supplierPermission,
                ScopeType.SUPPLIER_STORE, thread.getSupplierStoreId())) {
            return ChatSide.SUPPLIER;
        }
        throw new NotFoundException("ChatThread", thread.getId());
    }

    private ChatDirectory.Pair pairFor(ChatThread thread) {
        return directory.pair(thread.getOutletId(), thread.getSupplierStoreId())
                .orElseThrow(() -> new NotFoundException("ChatThread", thread.getId()));
    }

    private ChatDtos.ThreadResponse describe(ChatThread thread, ChatSide side) {
        var pair = pairFor(thread);
        long lastRead = readStates.findByChatThreadIdAndSide(thread.getId(), side)
                .map(ChatReadState::getLastReadMessageId).orElse(0L);

        return new ChatDtos.ThreadResponse(
                thread.getId(), thread.getOutletId(), thread.getSupplierStoreId(),
                side == ChatSide.RESTAURANT ? pair.storeName() : pair.outletName(),
                side == ChatSide.RESTAURANT ? pair.supplierName() : pair.restaurantName(),
                thread.getLastMessagePreview(), thread.getLastMessageAt(),
                thread.getLastMessageSide(),
                messages.unreadFor(thread.getId(), side, lastRead),
                pair.chatEnabled(),
                pair.chatEnabled() ? null : disabledReason(pair));
    }

    /**
     * What to tell somebody who cannot write here.
     *
     * <p>Which side is off is not said. A restaurant does not need to know that
     * a supplier has been switched off, and a supplier does not need to know it
     * about a restaurant; both need the same next step, which is support.
     */
    private static String disabledReason(ChatDirectory.Pair pair) {
        return "Chat is turned off for this account. Contact Costonomy support if you need it.";
    }

    private void markRead(Long threadId, ChatSide side, Long messageId) {
        var state = readStates.findByChatThreadIdAndSide(threadId, side)
                .orElseGet(() -> {
                    var fresh = new ChatReadState();
                    fresh.setChatThreadId(threadId);
                    fresh.setSide(side);
                    return fresh;
                });
        if (state.getLastReadMessageId() == null || state.getLastReadMessageId() < messageId) {
            state.setLastReadMessageId(messageId);
            readStates.save(state);
        }
    }

    private static String preview(ChatMessage message) {
        if (message.getBody() != null && !message.getBody().isBlank()) {
            return message.getBody().length() > 200
                    ? message.getBody().substring(0, 200) : message.getBody();
        }
        return message.getAttachmentType() == ChatAttachmentType.ORDER
                ? "Shared order " + message.getAttachmentReference()
                : "Shared request " + message.getAttachmentReference();
    }

    private static List<ChatMessage> reversed(List<ChatMessage> newestFirst) {
        var copy = new ArrayList<>(newestFirst);
        copy.sort(Comparator.comparing(ChatMessage::getId));
        return copy;
    }

    private static ChatDtos.MessageResponse toResponse(ChatMessage message) {
        return new ChatDtos.MessageResponse(
                message.getId(), message.getSenderSide(), message.getSenderUserId(),
                message.getBody(),
                message.getAttachmentType() == null ? null : new ChatDtos.AttachmentResponse(
                        message.getAttachmentType(), message.getAttachmentId(),
                        message.getAttachmentReference()),
                message.getCreatedAt());
    }
}
