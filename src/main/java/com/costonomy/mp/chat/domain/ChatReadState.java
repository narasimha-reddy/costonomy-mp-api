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

/** How far one side has read a thread. */
@Entity
@Table(name = "chat_read_state")
@Getter
@Setter
@NoArgsConstructor
public class ChatReadState extends BaseEntity {

    @Column(name = "chat_thread_id", nullable = false)
    private Long chatThreadId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "side", nullable = false, length = 16)
    private ChatSide side;

    @Column(name = "last_read_message_id", nullable = false)
    private Long lastReadMessageId = 0L;
}
