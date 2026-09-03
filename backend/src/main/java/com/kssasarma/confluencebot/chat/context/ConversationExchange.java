package com.kssasarma.confluencebot.chat.context;

/**
 * One completed question-and-answer pair from earlier in the conversation.
 *
 * <p>Deliberately vendor-neutral: the chat pipeline reasons about exchanges, and only
 * {@code SpringAiLlmGateway} turns them into the model library's message types. That is the same
 * boundary {@code LlmPrompt} already draws, and it is what keeps a change of model library from
 * reaching into the prompt builder or the retrieval path.
 *
 * <p>Grounding metadata is deliberately absent. Sources, citations and confidence belong to the
 * transcript the reader sees; replaying them to the model would invite it to cite excerpt numbers
 * from a previous turn, which index a different retrieval result and would resolve to the wrong
 * page.
 */
public record ConversationExchange(String question, String answer) {

    public ConversationExchange {
        question = question == null ? "" : question.strip();
        answer = answer == null ? "" : answer.strip();
    }

    /** An exchange with nothing on one side cannot inform a follow-up, and is dropped. */
    public boolean isUsable() {
        return !question.isEmpty() && !answer.isEmpty();
    }

    /** The same exchange with its answer clipped to a prompt-sized excerpt. */
    public ConversationExchange withAnswerClippedTo(int maxChars) {
        return new ConversationExchange(question, clip(answer, maxChars));
    }

    /**
     * Truncates on a word boundary so the model is never handed a half-written word, which reads
     * as corruption and invites it to guess at the missing text.
     */
    static String clip(String text, int maxChars) {
        if (maxChars <= 0 || text.length() <= maxChars) return text;

        String head = text.substring(0, maxChars);
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace > maxChars / 2) head = head.substring(0, lastSpace);

        return head.stripTrailing() + " …";
    }
}
