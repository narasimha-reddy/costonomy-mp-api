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

import java.time.Instant;

/**
 * One ongoing conversation between an outlet and a supplier store. D-095.
 *
 * <p>One per pair, enforced by {@code uk_chat_thread_pair}. Orders and requests
 * are shared into it rather than each carrying a thread of its own: a thread per
 * order gives a kitchen nine conversations with the same supplier and nowhere to
 * ask a general question.
 */
@Entity
@Table(name = "chat_thread")
@Getter
@Setter
@NoArgsConstructor
public class ChatThread extends BaseEntity {

    @Column(name = "outlet_id", nullable = false)
    private Long outletId;

    @Column(name = "supplier_store_id", nullable = false)
    private Long supplierStoreId;

    /**
     * The last line and when it landed, kept here for the inbox.
     *
     * <p>Denormalised on purpose: every list of threads wants them, and reading
     * them from {@code chat_message} per row is the query that makes an inbox
     * slow exactly when somebody has enough conversations to need one.
     */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "last_message_preview", length = 200)
    private String lastMessagePreview;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "last_message_side", length = 16)
    private ChatSide lastMessageSide;
}
