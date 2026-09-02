package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderBySequenceNoAsc(Long sessionId);

    @Query("SELECT COALESCE(MAX(m.sequenceNo), -1) FROM ChatMessage m WHERE m.session.id = :sessionId")
    int findMaxSequenceNo(Long sessionId);

    long countBySessionId(Long sessionId);

    @Query("""
            SELECT m.session.id, COUNT(m)
              FROM ChatMessage m
             WHERE m.session.id IN :sessionIds
             GROUP BY m.session.id
            """)
    List<Object[]> countGroupedBySessionIds(List<Long> sessionIds);

    /**
     * Message counts for a page of conversations, keyed by session id.
     *
     * One aggregate query rather than a count per row: the sidebar lists every conversation the
     * user has, and an N+1 there is felt immediately.
     */
    default Map<Long, Long> countsBySessionIds(List<Long> sessionIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (sessionIds.isEmpty()) return counts;

        for (Object[] row : countGroupedBySessionIds(sessionIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }
}
