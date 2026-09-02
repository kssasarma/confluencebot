package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.user.dto.ChatMessageResponse;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import com.kssasarma.confluencebot.user.dto.UpdateChatSessionRequest;

import java.util.List;

/**
 * Owns the lifecycle of a user's conversations and their transcripts.
 *
 * Every method is scoped to the calling user: a conversation belonging to somebody else is
 * indistinguishable from one that does not exist.
 */
public interface ChatSessionService {

    List<ChatSessionResponse> listSessions(User user);

    /**
     * Returns a conversation the user can start typing into.
     *
     * Creation is idempotent by design: if the user already owns an untouched, untitled
     * conversation, that one is handed back instead of piling up another empty entry.
     */
    ChatSessionResponse createSession(User user, String title);

    ChatSessionResponse updateSession(User user, String chatId, UpdateChatSessionRequest request);

    void deleteSession(User user, String chatId);

    List<ChatMessageResponse> transcript(User user, String chatId);

    /** Persists a completed exchange, naming the conversation after its first question. */
    ChatSessionResponse recordTurn(User user, ChatTurn turn);
}
