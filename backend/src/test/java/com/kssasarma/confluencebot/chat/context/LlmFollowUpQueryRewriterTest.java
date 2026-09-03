package com.kssasarma.confluencebot.chat.context;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.config.ChatContextProperties;
import com.kssasarma.confluencebot.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmFollowUpQueryRewriterTest {

    private static final ConversationContext CONTEXT = new ConversationContext(List.of(
            new ConversationExchange("How do I rotate the Kafka TLS certificates?",
                    "Run the rotate script on each broker in turn.")));

    /** Runs inline, so a test never depends on a pool or a clock. */
    private static final Executor DIRECT = Runnable::run;

    private final LlmGateway llmGateway = mock(LlmGateway.class);

    private LlmFollowUpQueryRewriter rewriter(ChatContextProperties properties) {
        return new LlmFollowUpQueryRewriter(llmGateway, DIRECT, properties);
    }

    private LlmFollowUpQueryRewriter rewriter() {
        return rewriter(properties(true, true));
    }

    private static ChatContextProperties properties(boolean enabled, boolean rewriteEnabled) {
        return new ChatContextProperties(enabled, 6, 1200, rewriteEnabled,
                Duration.ofSeconds(3), 400);
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "And in staging?",
            "What about the second one?",
            "Why?",
            "Can I do that without downtime?",
            "Explain the third step",
            "Does it need a restart?",
            "Tell me more",
            "How long does it take?",
    })
    void questionsThatLeanOnTheConversation_areRewritten(String question) {
        assertThat(LlmFollowUpQueryRewriter.dependsOnContext(question)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How do I rotate the Kafka TLS certificates on a broker?",
            "Which team owns the payments ingestion pipeline?",
            "What environment variables does the collector service read at startup?",
    })
    void questionsThatStandOnTheirOwn_skipTheModelEntirely(String question) {
        assertThat(LlmFollowUpQueryRewriter.dependsOnContext(question)).isFalse();

        assertThat(rewriter().rewriteForRetrieval(question, CONTEXT)).isEqualTo(question);
        verifyNoInteractions(llmGateway);
    }

    // ── The rewrite ───────────────────────────────────────────────────────────

    @Test
    void aFollowUp_isResolvedAgainstTheConversation() {
        when(llmGateway.complete(any()))
                .thenReturn("How do I rotate the Kafka TLS certificates in staging?");

        String rewritten = rewriter().rewriteForRetrieval("And in staging?", CONTEXT);

        assertThat(rewritten).isEqualTo("How do I rotate the Kafka TLS certificates in staging?");
    }

    @Test
    void theRewritePrompt_carriesTheConversationAsTextAndNotAsMessages() {
        when(llmGateway.complete(any())).thenReturn("A standalone question about certificates?");

        rewriter().rewriteForRetrieval("And in staging?", CONTEXT);

        ArgumentCaptor<LlmPrompt> prompt = ArgumentCaptor.forClass(LlmPrompt.class);
        verify(llmGateway).complete(prompt.capture());

        assertThat(prompt.getValue().user())
                .contains("How do I rotate the Kafka TLS certificates?")
                .contains("And in staging?");
        // Replaying the turns as messages would invite the model to answer instead of rewrite.
        assertThat(prompt.getValue().hasHistory()).isFalse();
    }

    @Test
    void aLabelledOrQuotedReply_isReadBackAsThePlainQuestion() {
        when(llmGateway.complete(any()))
                .thenReturn("Standalone question: \"How do I rotate certificates in staging?\"");

        assertThat(rewriter().rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("How do I rotate certificates in staging?");
    }

    @Test
    void aReplyWithCommentary_keepsOnlyTheQuestionOnTheFirstLine() {
        when(llmGateway.complete(any())).thenReturn(
                "How do I rotate certificates in staging?\n\nI resolved \"it\" to the certificates.");

        assertThat(rewriter().rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("How do I rotate certificates in staging?");
    }

    // ── Never worse than before ───────────────────────────────────────────────

    @Test
    void aModelThatAnswersInsteadOfRewriting_isDiscarded() {
        when(llmGateway.complete(any())).thenReturn("A".repeat(401));

        assertThat(rewriter().rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
    }

    @Test
    void anUnavailableModel_leavesTheQuestionAsAsked() {
        when(llmGateway.complete(any())).thenThrow(new LlmUnavailableException("circuit open"));

        assertThat(rewriter().rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
    }

    @Test
    void anEmptyReply_leavesTheQuestionAsAsked() {
        when(llmGateway.complete(any())).thenReturn("   ");

        assertThat(rewriter().rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
    }

    @Test
    void withNoConversationBehindIt_thereIsNothingToResolveAgainst() {
        assertThat(rewriter().rewriteForRetrieval("And in staging?", ConversationContext.EMPTY))
                .isEqualTo("And in staging?");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void whenRewritingIsTurnedOff_theQuestionGoesStraightToRetrieval() {
        LlmFollowUpQueryRewriter rewriter = rewriter(properties(true, false));

        assertThat(rewriter.rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void whenContextIsTurnedOffEntirely_rewritingGoesWithIt() {
        LlmFollowUpQueryRewriter rewriter = rewriter(properties(false, true));

        assertThat(rewriter.rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void aSaturatedPool_fallsBackRatherThanQueueingBehindAnAnswerTheUserIsWaitingFor() {
        Executor refusing = task -> {
            throw new java.util.concurrent.RejectedExecutionException("no capacity");
        };
        LlmFollowUpQueryRewriter rewriter =
                new LlmFollowUpQueryRewriter(llmGateway, refusing, properties(true, true));

        assertThat(rewriter.rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
        verifyNoInteractions(llmGateway);
    }

    @Test
    void aRewriteThatOverrunsItsDeadline_doesNotHoldUpTheAnswer() {
        ChatContextProperties impatient =
                new ChatContextProperties(true, 6, 1200, true, Duration.ofMillis(50), 400);

        // A real pool, so the call is genuinely still running when the deadline passes.
        Executor pool = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rewrite-test");
            thread.setDaemon(true);
            return thread;
        });
        when(llmGateway.complete(any())).thenAnswer(invocation -> {
            Thread.sleep(1_000);
            return "A rewrite that arrived far too late";
        });

        LlmFollowUpQueryRewriter rewriter = new LlmFollowUpQueryRewriter(llmGateway, pool, impatient);

        assertThat(rewriter.rewriteForRetrieval("And in staging?", CONTEXT))
                .isEqualTo("And in staging?");
    }
}
