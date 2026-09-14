package com.kssasarma.confluencebot.chat.prompt;

import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.chat.StreamingAnswerAssembler;
import com.kssasarma.confluencebot.chat.context.ConversationContext;
import com.kssasarma.confluencebot.prompt.PromptResources;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.user.EffectiveChatPreferences;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the model prompt for the hybrid-RAG chat pipeline.
 *
 * The standing rules, the answer style and any per-conversation instruction go into the system
 * message; the retrieved excerpts and the question go into the user message. Splitting them keeps
 * the retrieved documentation from reading like an instruction the model should follow, and it
 * lets the model weigh the rules ahead of the content.
 *
 * <p>Two conventions in the output are load-bearing and are documented here because the parsers
 * on the other side of them are elsewhere:
 *
 * <ol>
 *   <li><b>Numbered citations.</b> The excerpts are numbered {@code [1]}, {@code [2]}, … and the
 *       model is asked to cite those numbers rather than page titles. A title is not a usable
 *       reference: titles contain brackets and colons, they repeat across spaces, and
 *       {@code [Password Reset Guide]} is not link syntax, so it renders as literal text and the
 *       reader gets a citation they cannot follow. A number maps to a page unambiguously —
 *       see {@code CitationIndex}.</li>
 *   <li><b>A follow-up marker.</b> The suggestions are requested after a marker line so they can
 *       be held back from a stream that is being rendered token by token — see
 *       {@link StreamingAnswerAssembler}, which recognises the marker tolerantly because models
 *       reformat it.</li>
 * </ol>
 */
@Component
public class ConfluencePromptBuilder {

    /** Single source of truth, shared with the parser that strips the block back out. */
    static final String FOLLOW_UP_MARKER = StreamingAnswerAssembler.FOLLOW_UP_MARKER;

    private static final PromptTemplate SYSTEM_BASE_TEMPLATE =
            PromptResources.load("prompts/chat/system-base.st");
    private static final PromptTemplate SYSTEM_HISTORY_RULES_TEMPLATE =
            PromptResources.load("prompts/chat/system-history-rules.st");
    private static final PromptTemplate SYSTEM_RESPONSE_STYLE_TEMPLATE =
            PromptResources.load("prompts/chat/system-response-style.st");
    private static final PromptTemplate SYSTEM_CUSTOM_INSTRUCTION_TEMPLATE =
            PromptResources.load("prompts/chat/system-custom-instruction.st");
    private static final PromptTemplate SYSTEM_LOW_CONFIDENCE_TEMPLATE =
            PromptResources.load("prompts/chat/system-low-confidence.st");
    private static final PromptTemplate SYSTEM_FOLLOW_UP_TEMPLATE =
            PromptResources.load("prompts/chat/system-follow-up.st");

    private static final PromptTemplate USER_HEADER_TEMPLATE =
            PromptResources.load("prompts/chat/user-header.st");
    private static final PromptTemplate USER_EXCERPT_SOURCE_TEMPLATE =
            PromptResources.load("prompts/chat/user-excerpt-source.st");
    private static final PromptTemplate USER_EXCERPT_SOURCE_WITH_HEADING_TEMPLATE =
            PromptResources.load("prompts/chat/user-excerpt-source-with-heading.st");
    private static final PromptTemplate USER_EXCERPT_CODE_TAG_TEMPLATE =
            PromptResources.load("prompts/chat/user-excerpt-code-tag.st");
    private static final PromptTemplate USER_EXCERPT_TABLE_TAG_TEMPLATE =
            PromptResources.load("prompts/chat/user-excerpt-table-tag.st");
    private static final PromptTemplate USER_FOOTER_TEMPLATE =
            PromptResources.load("prompts/chat/user-footer.st");

    /**
     * @param question      the user's question
     * @param chunks        re-ranked retrieval results, most relevant first
     * @param lowConfidence true when even the best chunk is a weak match for the question
     * @param preferences   the answer style and custom instruction that apply to this conversation
     * @param context       what has already been said in this conversation; empty for a first
     *                      question, and for a caller that keeps no transcript
     */
    public LlmPrompt buildPrompt(String question, List<RetrievedChunk> chunks,
                                 boolean lowConfidence, EffectiveChatPreferences preferences,
                                 ConversationContext context) {
        EffectiveChatPreferences prefs =
                preferences != null ? preferences : EffectiveChatPreferences.defaults();
        ConversationContext history = context != null ? context : ConversationContext.EMPTY;

        return new LlmPrompt(
                systemMessage(prefs, lowConfidence, !history.isEmpty()),
                userMessage(question, chunks),
                history.exchanges());
    }

    /** A one-off question with nothing behind it. */
    public LlmPrompt buildPrompt(String question, List<RetrievedChunk> chunks,
                                 boolean lowConfidence, EffectiveChatPreferences preferences) {
        return buildPrompt(question, chunks, lowConfidence, preferences, ConversationContext.EMPTY);
    }

    private String systemMessage(EffectiveChatPreferences prefs, boolean lowConfidence,
                                 boolean hasHistory) {
        StringBuilder system = new StringBuilder();

        system.append(SYSTEM_BASE_TEMPLATE.render());

        if (hasHistory) {
            // Two separate risks, so two separate rules. The first is under-using the conversation
            // — answering "and in staging?" as though it were the first thing ever asked. The
            // second is over-trusting it: an earlier answer is this model's own prose, not a
            // source, and treating it as one is how a single early mistake hardens into a fact the
            // conversation keeps repeating with growing confidence.
            system.append(SYSTEM_HISTORY_RULES_TEMPLATE.render());
        }

        system.append(SYSTEM_RESPONSE_STYLE_TEMPLATE.render(
                Map.of("style", prefs.responseStyle().instruction())));

        if (prefs.hasCustomPrompt()) {
            system.append(SYSTEM_CUSTOM_INSTRUCTION_TEMPLATE.render(
                    Map.of("customPrompt", prefs.customPrompt().strip())));
        }

        if (lowConfidence) {
            system.append(SYSTEM_LOW_CONFIDENCE_TEMPLATE.render());
        }

        system.append(SYSTEM_FOLLOW_UP_TEMPLATE.render(Map.of("marker", FOLLOW_UP_MARKER)));

        return system.toString();
    }

    private String userMessage(String question, List<RetrievedChunk> chunks) {
        StringBuilder user = new StringBuilder();

        user.append(USER_HEADER_TEMPLATE.render());

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            user.append('[').append(i + 1).append("] ");
            if (chunk.getTitle() != null && !chunk.getTitle().isBlank()) {
                if (chunk.getSectionHeading() != null && !chunk.getSectionHeading().isBlank()) {
                    user.append(USER_EXCERPT_SOURCE_WITH_HEADING_TEMPLATE.render(Map.of(
                            "title", chunk.getTitle(),
                            "sectionHeading", chunk.getSectionHeading())));
                } else {
                    user.append(USER_EXCERPT_SOURCE_TEMPLATE.render(Map.of("title", chunk.getTitle())));
                }
            }
            if ("CODE".equals(chunk.getChunkType())) {
                user.append(USER_EXCERPT_CODE_TAG_TEMPLATE.render());
            } else if ("TABLE".equals(chunk.getChunkType())) {
                user.append(USER_EXCERPT_TABLE_TAG_TEMPLATE.render());
            }
            user.append(chunk.getContent()).append("\n\n");
        }

        user.append(USER_FOOTER_TEMPLATE.render(Map.of("question", question)));

        return user.toString();
    }
}
