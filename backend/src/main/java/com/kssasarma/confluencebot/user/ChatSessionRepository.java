package com.kssasarma.confluencebot.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * One keyset page of a user's conversations, newest first within pinned then unpinned.
     *
     * Native because the ordering is a three-part tuple comparison over a boolean, a timestamp and
     * a key; expressing that portably in JPQL means a CASE ladder that no longer resembles the
     * index it has to match. The predicate below is written to line up exactly with
     * idx_chat_sessions_user_keyset.
     *
     * A null cursor means the first page.
     */
    @Query(value = """
            SELECT s.*
              FROM chat_sessions s
             WHERE s.user_id = :userId
               AND (
                     CAST(:cursorId AS BIGINT) IS NULL
                     OR (CASE WHEN s.pinned THEN 1 ELSE 0 END, s.updated_at, s.id)
                        < (CASE WHEN CAST(:cursorPinned AS BOOLEAN) THEN 1 ELSE 0 END,
                           CAST(:cursorUpdatedAt AS TIMESTAMPTZ),
                           CAST(:cursorId AS BIGINT))
                   )
             ORDER BY (CASE WHEN s.pinned THEN 1 ELSE 0 END) DESC, s.updated_at DESC, s.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<ChatSession> findPage(@Param("userId") Long userId,
                               @Param("cursorPinned") Boolean cursorPinned,
                               @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                               @Param("cursorId") Long cursorId,
                               @Param("limit") int limit);

    /**
     * One keyset page of the conversations matching a search.
     *
     * A conversation matches when any of its messages matches the full-text query, or when its
     * title contains the text — a user looking for "the deploy one" is as likely to remember the
     * name as a phrase inside it.
     *
     * The LATERAL join picks the single best-ranked message per conversation, so a long
     * conversation with forty hits still produces one row and one snippet. LEFT JOIN keeps
     * title-only matches, which have no passage to quote.
     *
     * Results keep the same ordering as the unfiltered list rather than switching to relevance:
     * one ordering means one cursor shape, and a reader scanning their history is looking for a
     * conversation they had, not the best-scoring one.
     */
    @Query(value = """
            SELECT s.id            AS id,
                   s.chat_id       AS chatId,
                   s.title         AS title,
                   s.pinned        AS pinned,
                   s.created_at    AS createdAt,
                   s.updated_at    AS updatedAt,
                   m.id            AS matchMessageId,
                   m.snippet       AS snippet
              FROM chat_sessions s
              LEFT JOIN LATERAL (
                   SELECT cm.id AS id,
                          ts_headline('english', cm.content,
                                      plainto_tsquery('english', :query),
                                      'StartSel=[[HL]], StopSel=[[/HL]], MaxWords=28, MinWords=10, ShortWord=3, MaxFragments=1') AS snippet
                     FROM chat_messages cm
                    WHERE cm.chat_session_id = s.id
                      AND to_tsvector('english', cm.content) @@ plainto_tsquery('english', :query)
                    ORDER BY ts_rank(to_tsvector('english', cm.content),
                                     plainto_tsquery('english', :query)) DESC,
                             cm.sequence_no ASC
                    LIMIT 1
              ) m ON TRUE
             WHERE s.user_id = :userId
               AND (m.id IS NOT NULL OR s.title ILIKE :titlePattern ESCAPE '\\')
               AND (
                     CAST(:cursorId AS BIGINT) IS NULL
                     OR (CASE WHEN s.pinned THEN 1 ELSE 0 END, s.updated_at, s.id)
                        < (CASE WHEN CAST(:cursorPinned AS BOOLEAN) THEN 1 ELSE 0 END,
                           CAST(:cursorUpdatedAt AS TIMESTAMPTZ),
                           CAST(:cursorId AS BIGINT))
                   )
             ORDER BY (CASE WHEN s.pinned THEN 1 ELSE 0 END) DESC, s.updated_at DESC, s.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<ChatSessionSearchRow> search(@Param("userId") Long userId,
                                      @Param("query") String query,
                                      @Param("titlePattern") String titlePattern,
                                      @Param("cursorPinned") Boolean cursorPinned,
                                      @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
                                      @Param("cursorId") Long cursorId,
                                      @Param("limit") int limit);
}
