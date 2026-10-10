package com.costonomy.mp.chat.repository;

import com.costonomy.mp.chat.domain.ChatReadState;
import com.costonomy.mp.chat.domain.ChatSide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatReadStateRepository extends JpaRepository<ChatReadState, Long> {

    Optional<ChatReadState> findByChatThreadIdAndSide(Long chatThreadId, ChatSide side);
}
