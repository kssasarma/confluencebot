package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {
    List<ChatSession> findByUserIdOrderByPinnedDescUpdatedAtDesc(Long userId);
    Optional<ChatSession> findByChatId(String chatId);
    Optional<ChatSession> findByChatIdAndUserId(String chatId, Long userId);
    void deleteByChatIdAndUserId(String chatId, Long userId);
}
