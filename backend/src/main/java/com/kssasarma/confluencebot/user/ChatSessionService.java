package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.user.dto.ChatMessageResponse;
import com.kssasarma.confluencebot.user.dto.ChatSessionPage;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import com.kssasarma.confluencebot.user.dto.UpdateChatSessionRequest;

import java.util.List;

/**
 * Owns the lifecycle of a user's conversations and their transcripts.
 *
 * <p>Every method is scoped to the calling user: a conversation belonging to somebody else is
 * indistinguishable from one that does not exist.
 */
public interface ChatSessionService {

    /**
     * One page of the user's conversations, optionally filtered by a search.
     *
     * @param query  free text matched against titles and transcript contents; null or blank lists
     *               everything
     * @param cursor bookmark from a previous page; null starts at the top
     * @param limit  maximum conversations to return; the implementation caps it
     */
    ChatSessionPage listSessions(User user, String query, String cursor, int limit);

    /**
     * Returns a conversation the user can start typing into.
     *
     * <p>Creation is idempotent by design: if the user already owns an untouched, untitled
     * conversation, that one is handed back instead of piling up another empty entry.
     */
    ChatSessionResponse createSession(User user, String title);

    ChatSessionResponse updateSession(User user, String chatId, UpdateChatSessionRequest request);

    void deleteSession(User user, String chatId);

    List<ChatMessageResponse> transcript(User user, String chatId);

    /** Persists a completed exchange, naming the conversation after its first question. */
    ChatSessionResponse recordTurn(User user, ChatTurn turn);

    /**
     * Replaces a machine-derived title with a better one.
     *
     * <p>Declines — returning {@code false} — when the user has renamed the conversation since the
     * summary was requested. A rename is a deliberate act, and having it silently undone a second
     * later by a background job is the kind of defect that makes people stop trusting the app.
     *
     * @return true when the title was replaced
     */
    boolean applyGeneratedTitle(User user, String chatId, String title);
}
