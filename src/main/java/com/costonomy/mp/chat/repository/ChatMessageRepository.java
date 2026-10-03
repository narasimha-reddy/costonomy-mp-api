package com.costonomy.mp.chat.repository;

import com.costonomy.mp.chat.domain.ChatMessage;
import com.costonomy.mp.chat.domain.ChatSide;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** A page of history, newest first. The client reverses it to read downwards. */
    List<ChatMessage> findByChatThreadIdOrderByIdDesc(Long chatThreadId, Pageable pageable);

    /** Anything after a cursor, oldest first — how an open thread catches up. */
    List<ChatMessage> findByChatThreadIdAndIdGreaterThanOrderByIdAsc(
            Long chatThreadId, Long afterId);

    /**
     * How many messages this side has not read.
     *
     * <p>Counted from the other side only: your own messages are not unread, and
     * counting them would put a badge on a conversation you just wrote in.
     */
    @Query("""
            select count(m) from ChatMessage m
             where m.chatThreadId = :threadId
               and m.senderSide <> :side
               and m.id > :lastReadId
            """)
    long unreadFor(@Param("threadId") Long threadId,
                   @Param("side") ChatSide side,
                   @Param("lastReadId") Long lastReadId);
}
