package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.user.User;

/**
 * One question, plus who is asking and which conversation it belongs to.
 *
 * Both the user and the conversation are optional: a caller that only wants an answer (a smoke
 * test, an internal integration) gets one without anything being persisted.
 */
public record ChatQuery(String question, String chatId, User user) {

    public ChatQuery {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        question = question.strip();
    }

    public static ChatQuery of(String question) {
        return new ChatQuery(question, null, null);
    }

    /** A turn is only recorded when it belongs to a signed-in user's conversation. */
    public boolean isPersistable() {
        return user != null && chatId != null && !chatId.isBlank();
    }
}
