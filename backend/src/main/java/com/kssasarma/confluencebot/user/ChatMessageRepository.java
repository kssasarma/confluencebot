package com.kssasarma.confluencebot.user;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    long countByRole(ChatMessageRole role);

    /**
     * Who is asking the most questions, most first. Capped by the {@link Pageable} the caller
     * passes — this backs an admin-only "top users" report, not a full ranking of every user.
     */
    @Query("""
            SELECT u.email AS email, u.name AS name, COUNT(m) AS questionCount
              FROM ChatMessage m JOIN m.session s JOIN s.user u
             WHERE m.role = com.kssasarma.confluencebot.user.ChatMessageRole.USER
             GROUP BY u.id, u.email, u.name
             ORDER BY COUNT(m) DESC
            """)
    List<UserQuestionCount> topUsersByQuestionCount(Pageable pageable);

    /** Question volume per business unit, for whichever users have one set. */
    @Query("""
            SELECT u.businessUnit AS businessUnit, COUNT(m) AS questionCount
              FROM ChatMessage m JOIN m.session s JOIN s.user u
             WHERE m.role = com.kssasarma.confluencebot.user.ChatMessageRole.USER
               AND u.businessUnit IS NOT NULL
             GROUP BY u.businessUnit
             ORDER BY COUNT(m) DESC
            """)
    List<BusinessUnitQuestionCount> questionCountsByBusinessUnit();

    interface UserQuestionCount {
        String getEmail();
        String getName();
        long getQuestionCount();
    }

    interface BusinessUnitQuestionCount {
        String getBusinessUnit();
        long getQuestionCount();
    }

    List<ChatMessage> findBySessionIdOrderBySequenceNoAsc(Long sessionId);

    @Query("SELECT COALESCE(MAX(m.sequenceNo), -1) FROM ChatMessage m WHERE m.session.id = :sessionId")
    int findMaxSequenceNo(Long sessionId);

    long countBySessionId(Long sessionId);

    /**
     * The tail of a transcript, newest first.
     *
     * <p>Separate from {@link #findBySessionIdOrderBySequenceNoAsc} because the two have opposite
     * requirements. That one renders a transcript and must return all of it; this one runs on the
     * answer path of every question in a conversation, where loading a hundred-turn history to use
     * the last three of it would make the app slower the longer somebody talks to it.
     *
     * <p>Descending with a limit rather than ascending, so the database reads the newest rows off
     * the index and stops. The caller reverses what it keeps.
     */
    @Query("""
            SELECT m FROM ChatMessage m
             WHERE m.session.id = :sessionId
             ORDER BY m.sequenceNo DESC
            """)
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") Long sessionId, Pageable limit);

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
