package com.kssasarma.confluencebot.chat.context;

import java.util.List;

/**
 * What has already been said in this conversation, oldest exchange first.
 *
 * <p>This is the value the whole feature turns on. Without it every question is answered as though
 * it were the first: "and in staging?" retrieves documents about the word "staging" and is answered
 * by a model that has never seen what came before.
 *
 * <p>The list is already bounded and clipped by the time it gets here — see
 * {@code PersistentConversationHistoryService} — so nothing downstream has to defend itself against
 * a conversation that has been running for a thousand turns.
 */
public record ConversationContext(List<ConversationExchange> exchanges) {

    public static final ConversationContext EMPTY = new ConversationContext(List.of());

    public ConversationContext {
        exchanges = exchanges == null ? List.of() : List.copyOf(exchanges);
    }

    public boolean isEmpty() {
        return exchanges.isEmpty();
    }

    public int size() {
        return exchanges.size();
    }

    /** The tail of the conversation — the turns a reference is most likely to point at. */
    public ConversationContext mostRecent(int maxExchanges) {
        if (maxExchanges <= 0) return EMPTY;
        if (exchanges.size() <= maxExchanges) return this;

        return new ConversationContext(
                exchanges.subList(exchanges.size() - maxExchanges, exchanges.size()));
    }

    /** The same conversation with every answer clipped, for a prompt that needs the gist only. */
    public ConversationContext withAnswersClippedTo(int maxChars) {
        return new ConversationContext(
                exchanges.stream().map(exchange -> exchange.withAnswerClippedTo(maxChars)).toList());
    }

    /**
     * The conversation written out as plain text, for a prompt that takes it as content rather
     * than as messages — the query rewriter, which asks one question <em>about</em> the
     * conversation instead of continuing it.
     */
    public String transcript() {
        StringBuilder text = new StringBuilder();
        for (ConversationExchange exchange : exchanges) {
            text.append("User: ").append(exchange.question()).append('\n')
                .append("Assistant: ").append(exchange.answer()).append("\n\n");
        }
        return text.toString().stripTrailing();
    }
}
