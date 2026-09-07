package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.user.User;

/**
 * One question, plus who is asking, which conversation it belongs to, and which Confluence space
 * (if any) to restrict retrieval to.
 *
 * The user and the conversation are optional: a caller that only wants an answer (a smoke test,
 * an internal integration) gets one without anything being persisted. The space is optional too —
 * omitting it searches every ingested space, which is the default and the only behaviour that
 * existed before per-space search.
 */
public record ChatQuery(String question, String chatId, String spaceKey, User user) {

    public ChatQuery {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
        spaceKey = (spaceKey == null || spaceKey.isBlank()) ? null : spaceKey.strip();
    }

    /** The common case: no space filter. */
    public ChatQuery(String question, String chatId, User user) {
        this(question, chatId, null, user);
    }

    public static ChatQuery of(String question) {
        return new ChatQuery(question, null, null, null);
    }

    /** A turn is only recorded when it belongs to a signed-in user's conversation. */
    public boolean isPersistable() {
        return user != null && chatId != null && !chatId.isBlank();
    }
}
