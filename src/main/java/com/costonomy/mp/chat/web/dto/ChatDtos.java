package com.costonomy.mp.chat.web.dto;

import com.costonomy.mp.chat.domain.ChatAttachmentType;
import com.costonomy.mp.chat.domain.ChatSide;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * One conversation, as the inbox shows it.
     *
     * <p>{@code counterpartName} is whoever the reader is <em>not</em>: the same
     * row serves both sides, and a restaurant's inbox should say the store's
     * name while the store's says the outlet's.
     */
    public record ThreadResponse(
            Long id,
            Long outletId,
            Long supplierStoreId,
            /** The other party, from the reader's side. */
            String counterpartName,
            /** Their parent organisation, when it adds something the name does not. */
            String counterpartSubtitle,
            String lastMessagePreview,
            Instant lastMessageAt,
            ChatSide lastMessageSide,
            long unreadCount,
            /**
             * Whether a message can be sent right now.
             *
             * <p>False when either side's chat is switched off. Stated by the
             * server rather than inferred: the app must not decide from a flag it
             * holds about one party that the other party can be written to.
             */
            boolean canSend,
            /** Why not, in words the app can show. Null when it can. */
            String disabledReason) {
    }

    public record MessageResponse(
            Long id,
            ChatSide senderSide,
            Long senderUserId,
            String body,
            AttachmentResponse attachment,
            Instant createdAt) {
    }

    /**
     * A shared request or order.
     *
     * <p>Typed, so the app opens whichever screen belongs to the reader's side.
     * A supplier tapping an order goes to their view of it, not the restaurant's.
     */
    public record AttachmentResponse(
            ChatAttachmentType type,
            Long id,
            String reference) {
    }

    /** A thread and a page of it. */
    public record ThreadDetailResponse(
            ThreadResponse thread,
            List<MessageResponse> messages) {
    }

    public record SendMessageRequest(
            @Size(max = 2000, message = "That message is too long") String body,
            ChatAttachmentType attachmentType,
            Long attachmentId) {
    }

    public record OpenThreadRequest(Long supplierStoreId) {
    }

    /** The same, named from the store's side. */
    public record OpenThreadFromStoreRequest(Long outletId) {
    }

    /** One thing that could be shared into this conversation. */
    public record ShareableResponse(
            ChatAttachmentType type,
            Long id,
            String reference,
            String status,
            Instant createdAt) {
    }
}
