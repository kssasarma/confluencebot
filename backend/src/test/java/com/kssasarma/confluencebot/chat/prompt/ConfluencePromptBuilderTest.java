package com.kssasarma.confluencebot.chat.prompt;

import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.chat.context.ConversationContext;
import com.kssasarma.confluencebot.chat.context.ConversationExchange;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.user.EffectiveChatPreferences;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfluencePromptBuilderTest {

    private final ConfluencePromptBuilder builder = new ConfluencePromptBuilder();

    private static final ConversationContext CONTEXT = new ConversationContext(List.of(
            new ConversationExchange("How do I rotate the Kafka TLS certificates?",
                    "Run the rotate script on each broker.")));

    @Test
    void theConversationIsCarriedAsExchanges_notPastedIntoTheQuestion() {
        LlmPrompt prompt = build(CONTEXT);

        assertThat(prompt.history()).isEqualTo(CONTEXT.exchanges());
        // Flattening it into the user message would put a conversation where documentation goes,
        // and the excerpts are explicitly framed as reference material, not as dialogue.
        assertThat(prompt.user()).doesNotContain("Run the rotate script on each broker.");
    }

    @Test
    void withAConversation_theModelIsToldHowToUseItAndHowNotTo() {
        String system = build(CONTEXT).system();

        assertThat(system).contains("earlier messages are this same conversation");
        assertThat(system).contains("Your earlier answers are not a source");
        assertThat(system).contains("earlier numbering does not carry over");
    }

    @Test
    void withoutAConversation_thoseRulesAreLeftOut() {
        String system = build(ConversationContext.EMPTY).system();

        assertThat(system).doesNotContain("earlier messages are this same conversation");
        // The standing rules are unchanged for a first question.
        assertThat(system).contains("Answer ONLY using information from the documentation excerpts");
    }

    @Test
    void theExcerptsAndTheQuestionStillReachTheUserMessage() {
        LlmPrompt prompt = build(CONTEXT);

        assertThat(prompt.user())
                .contains("[1] Source: Certificate Rotation")
                .contains("Content about certificate rotation")
                .contains("User question: And in staging?");
    }

    private LlmPrompt build(ConversationContext context) {
        return builder.buildPrompt("And in staging?", List.of(chunk()), false,
                EffectiveChatPreferences.defaults(), context);
    }

    private static RetrievedChunk chunk() {
        return RetrievedChunk.builder()
                .chunkId("c1")
                .content("Content about certificate rotation")
                .pageId("123")
                .title("Certificate Rotation")
                .pageUrl("http://confluence/pages/123")
                .spaceKey("ENG")
                .chunkType("TEXT")
                .similarity(0.85)
                .build();
    }
}
