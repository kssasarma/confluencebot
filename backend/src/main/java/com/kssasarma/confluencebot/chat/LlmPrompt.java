package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.chat.context.ConversationExchange;

import java.util.List;

/**
 * What the model is asked: the standing instructions, the conversation so far, and this turn.
 *
 * <p>The history is kept apart from the two texts rather than pasted into the user message,
 * because the distinction survives all the way to the model. Prior turns delivered as real
 * user/assistant messages are what let a model resolve "that one" against its own earlier answer;
 * the same text flattened into one user message reads as documentation about a conversation, and
 * models answer it as such.
 *
 * @param system  standing instructions
 * @param user    this turn's question, with the retrieved excerpts it is to be answered from
 * @param history earlier exchanges, oldest first; empty for a first question or a caller with no
 *                conversation
 */
public record LlmPrompt(String system, String user, List<ConversationExchange> history) {

    public LlmPrompt {
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** A prompt with no conversation behind it — a first question, or a one-shot internal call. */
    public LlmPrompt(String system, String user) {
        this(system, user, List.of());
    }

    public boolean hasHistory() {
        return !history.isEmpty();
    }
}
