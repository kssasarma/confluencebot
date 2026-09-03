package com.kssasarma.confluencebot.chat.context;

import com.kssasarma.confluencebot.chat.ChatQuery;

/**
 * Reads back what a conversation has already established, so the next question can be answered
 * in the light of it.
 *
 * <p>An interface rather than a class because the storage is an implementation detail: the
 * transcript happens to live in Postgres today, and a deployment that wanted a window over a
 * cache, or no history at all, should be able to say so without touching the chat pipeline.
 */
public interface ConversationHistoryService {

    /**
     * The recent exchanges of the conversation this question belongs to.
     *
     * <p>Never throws and never returns null: a question that cannot be placed in a conversation —
     * anonymous, brand new, or one whose history could not be read — yields
     * {@link ConversationContext#EMPTY} and is answered exactly as it was before this existed.
     */
    ConversationContext recentContext(ChatQuery query);
}
