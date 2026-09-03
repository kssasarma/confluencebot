package com.kssasarma.confluencebot.chat.context;

/**
 * Turns a question that only makes sense in context into one that makes sense on its own.
 *
 * <p>Retrieval is the half of the pipeline that cannot be fixed by handing the model a history.
 * The search index is asked for documents matching a string, and "and in staging?" matches
 * documents about staging in general — not about the certificate rotation the conversation has
 * been discussing for three turns. The model would then be reasoning over the wrong excerpts, and
 * being well-informed about the conversation would not save it.
 */
public interface FollowUpQueryRewriter {

    /**
     * The string retrieval should actually search for.
     *
     * <p>Total function: it returns the original question whenever a rewrite is unnecessary,
     * disabled, unavailable or unusable. A caller never has to handle a failure, and retrieval is
     * never worse than it was before this existed.
     */
    String rewriteForRetrieval(String question, ConversationContext context);
}
