package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    List<ChatSession> findByUserIdOrderByPinnedDescUpdatedAtDesc(Long userId);

    Optional<ChatSession> findByChatId(String chatId);

    Optional<ChatSession> findByChatIdAndUserId(String chatId, Long userId);

    void deleteByChatIdAndUserId(String chatId, Long userId);

    /** Conversations the user opened but never used: no title, no messages. */
    @Query("""
            SELECT s FROM ChatSession s
             WHERE s.user.id = :userId
               AND s.title IS NULL
               AND s.pinned = false
               AND NOT EXISTS (SELECT 1 FROM ChatMessage m WHERE m.session = s)
             ORDER BY s.updatedAt DESC
            """)
    List<ChatSession> findUntouchedSessions(Long userId);

    /** Same as {@link #findUntouchedSessions}, restricted to ones abandoned before the cut-off. */
    @Query("""
            SELECT s FROM ChatSession s
             WHERE s.user.id = :userId
               AND s.title IS NULL
               AND s.pinned = false
               AND s.updatedAt < :cutoff
               AND NOT EXISTS (SELECT 1 FROM ChatMessage m WHERE m.session = s)
            """)
    List<ChatSession> findAbandonedSessions(Long userId, Instant cutoff);
}
