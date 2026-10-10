package com.costonomy.mp.chat.domain;

import com.costonomy.mp.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One line in a conversation, with an optional request or order attached. */
@Entity
@Table(name = "chat_message")
@Getter
@Setter
@NoArgsConstructor
public class ChatMessage extends BaseEntity {

    @Column(name = "chat_thread_id", nullable = false)
    private Long chatThreadId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sender_side", nullable = false, length = 16)
    private ChatSide senderSide;

    /** Who wrote it. The side decides what a reader sees; this is accountability. */
    @Column(name = "sender_user_id", nullable = false)
    private Long senderUserId;

    @Column(name = "body", length = 2000)
    private String body;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "attachment_type", length = 32)
    private ChatAttachmentType attachmentType;

    @Column(name = "attachment_id")
    private Long attachmentId;

    /**
     * The human-readable number, snapshotted.
     *
     * <p>So a shared order still reads as {@code MP-260919-000013} in a thread
     * whoever is looking at it, without a join per message.
     */
    @Column(name = "attachment_reference", length = 64)
    private String attachmentReference;
}
