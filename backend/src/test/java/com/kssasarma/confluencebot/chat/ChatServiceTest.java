package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.confidence.ConfidenceScorer;
import com.kssasarma.confluencebot.chat.confidence.WeightedSignalConfidenceScorer;
import com.kssasarma.confluencebot.chat.context.ConversationContext;
import com.kssasarma.confluencebot.chat.context.ConversationExchange;
import com.kssasarma.confluencebot.chat.context.ConversationHistoryService;
import com.kssasarma.confluencebot.chat.context.FollowUpQueryRewriter;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.chat.source.SourceReferenceFactory;
import com.kssasarma.confluencebot.chat.title.ChatTitleRefiner;
import com.kssasarma.confluencebot.config.ChatConfidenceProperties;
import com.kssasarma.confluencebot.exception.LlmUnavailableException;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.service.HybridSearchService;
import com.kssasarma.confluencebot.user.ChatSessionService;
import com.kssasarma.confluencebot.user.ChatTurn;
import com.kssasarma.confluencebot.user.EffectiveChatPreferences;
import com.kssasarma.confluencebot.user.PreferenceService;
import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.dto.ChatSessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceTest {

    private static final String CHAT_ID = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c";

    @Mock private HybridSearchService hybridSearchService;
    @Mock private ConversationHistoryService historyService;
    @Mock private FollowUpQueryRewriter queryRewriter;
    @Mock private LlmGateway llmGateway;
    @Mock private PreferenceService preferenceService;
    @Mock private ChatSessionService chatSessionService;
    @Mock private User user;

    @Mock private ChatTitleRefiner titleRefiner;

    private final ConfluencePromptBuilder promptBuilder = new ConfluencePromptBuilder();
    private final SourceReferenceFactory sourceReferenceFactory = new SourceReferenceFactory(240);
    private final ConfidenceScorer confidenceScorer =
            new WeightedSignalConfidenceScorer(new ChatConfidenceProperties(
                    0.35, 0.25, 0.15, 0.25, 0.30, 3, 2));

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(hybridSearchService, historyService, queryRewriter,
                promptBuilder, llmGateway, preferenceService, chatSessionService,
                sourceReferenceFactory, confidenceScorer, titleRefiner, 0.4);
        when(preferenceService.resolve(any(), any())).thenReturn(EffectiveChatPreferences.defaults());
        when(titleRefiner.refine(any()))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        // The default is a conversation with nothing behind it, which is what most of these tests
        // exercise; the ones about context override it.
        when(historyService.recentContext(any())).thenReturn(ConversationContext.EMPTY);
        when(queryRewriter.rewriteForRetrieval(anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void noRelevantDocs_returnsNoContextResponse() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of());

        ChatApiResponse response = chatService.chat(ChatQuery.of("How do I configure X?"));

        assertThat(response.answer()).contains("could not find");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(llmGateway);
    }

    @Test
    void withRelevantDocs_callsLlmAndReturnsSources() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn("Feature X is configured by...");

        ChatApiResponse response = chatService.chat(ChatQuery.of("How do I configure feature X?"));

        assertThat(response.answer()).isEqualTo("Feature X is configured by...");
        assertThat(response.sources()).hasSize(1);

        SourceReference source = response.sources().get(0);
        assertThat(source.title()).isEqualTo("Feature X Guide");
        assertThat(source.url()).isEqualTo("http://confluence/pages/123");
        assertThat(source.anchorUrl()).isEqualTo("http://confluence/pages/123#Configuration");
        assertThat(source.spaceKey()).isEqualTo("ENG");
    }

    @Test
    void withFollowUpQuestions_parsedAndReturnedSeparately() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn(
                "The answer is here.\n---FOLLOW-UP-QUESTIONS---\nHow do I do X?\nWhat about Y?\nCan I do Z?");

        ChatApiResponse response = chatService.chat(ChatQuery.of("Tell me about the guide"));

        assertThat(response.answer()).isEqualTo("The answer is here.");
        assertThat(response.followUpQuestions())
                .containsExactly("How do I do X?", "What about Y?", "Can I do Z?");
    }

    @Test
    void anonymousQuery_isNotRecorded() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn("An answer.");

        chatService.chat(ChatQuery.of("Anything?"));

        verifyNoInteractions(chatSessionService);
    }

    @Test
    void queryWithConversation_recordsTheExchangeAndReturnsTheTitle() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn("An answer.");
        when(chatSessionService.recordTurn(any(), any())).thenReturn(session("Where are the docs?"));

        ChatApiResponse response = chatService.chat(new ChatQuery("Where are the docs?", CHAT_ID, user));

        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(chatSessionService).recordTurn(eq(user), turn.capture());
        assertThat(turn.getValue().question()).isEqualTo("Where are the docs?");
        assertThat(turn.getValue().answer()).isEqualTo("An answer.");
        assertThat(turn.getValue().sources()).hasSize(1);

        assertThat(response.chatId()).isEqualTo(CHAT_ID);
        assertThat(response.title()).isEqualTo("Where are the docs?");
    }

    @Test
    void unavailableModel_failsLoudlyInsteadOfAnsweringWithAnApology() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenThrow(new LlmUnavailableException("circuit open"));

        assertThatThrownBy(() -> chatService.chat(ChatQuery.of("Anything?")))
                .isInstanceOf(LlmUnavailableException.class);
        verifyNoInteractions(chatSessionService);
    }

    @Test
    void streaming_pushesSourcesThenTokensThenTheRecordedAnswer() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.stream(any())).thenReturn(
                Flux.just("Feature X ", "is configured.", "\n---FOLLOW-UP-QUESTIONS---\nMore?"));
        when(chatSessionService.recordTurn(any(), any())).thenReturn(session("How?"));

        RecordingListener listener = new RecordingListener();
        chatService.stream(new ChatQuery("How?", CHAT_ID, user), listener);

        assertThat(listener.sources).hasSize(1);
        // The raw stream keeps the newline that precedes the marker; the recorded answer is stripped.
        assertThat(String.join("", listener.tokens).strip()).isEqualTo("Feature X is configured.");
        assertThat(listener.completed).isNotNull();
        assertThat(listener.completed.answer()).isEqualTo("Feature X is configured.");
        assertThat(listener.completed.followUpQuestions()).containsExactly("More?");
        assertThat(listener.completed.chatId()).isEqualTo(CHAT_ID);
        assertThat(listener.failure).isNull();
    }

    @Test
    void streaming_reportsAFailedModelToTheClient() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.stream(any())).thenReturn(Flux.error(new LlmUnavailableException("circuit open")));

        RecordingListener listener = new RecordingListener();
        chatService.stream(ChatQuery.of("How?"), listener);

        assertThat(listener.failure).contains("temporarily unavailable");
        assertThat(listener.completed).isNull();
    }

    @Test
    void streaming_withNoRelevantDocs_stillDeliversAnAnswer() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of());

        RecordingListener listener = new RecordingListener();
        chatService.stream(ChatQuery.of("How?"), listener);

        assertThat(String.join("", listener.tokens)).contains("could not find");
        assertThat(listener.completed).isNotNull();
        verifyNoInteractions(llmGateway);
    }

    // ── Conversation context ──────────────────────────────────────────────────

    @Test
    void followUp_isRetrievedByItsStandaloneRewriteButAskedAsTheUserTypedIt() {
        ConversationContext context = new ConversationContext(List.of(
                new ConversationExchange("How do I rotate the Kafka TLS certificates?",
                        "Run the rotate script on each broker.")));

        when(historyService.recentContext(any())).thenReturn(context);
        when(queryRewriter.rewriteForRetrieval("And in staging?", context))
                .thenReturn("How do I rotate the Kafka TLS certificates in staging?");
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn("Same script, different inventory file.");
        when(chatSessionService.recordTurn(any(), any())).thenReturn(session("Certificate rotation"));

        chatService.chat(new ChatQuery("And in staging?", CHAT_ID, user));

        // Retrieval gets the resolved query — an index cannot follow "and in staging?" on its own.
        verify(hybridSearchService).search("How do I rotate the Kafka TLS certificates in staging?");

        // The model is asked the question as the user wrote it, with the conversation behind it.
        ArgumentCaptor<LlmPrompt> prompt = ArgumentCaptor.forClass(LlmPrompt.class);
        verify(llmGateway).complete(prompt.capture());
        assertThat(prompt.getValue().user()).contains("And in staging?");
        assertThat(prompt.getValue().history()).isEqualTo(context.exchanges());

        // And the transcript records what was typed, not what was searched for.
        ArgumentCaptor<ChatTurn> turn = ArgumentCaptor.forClass(ChatTurn.class);
        verify(chatSessionService).recordTurn(eq(user), turn.capture());
        assertThat(turn.getValue().question()).isEqualTo("And in staging?");
    }

    @Test
    void streamedFollowUp_carriesTheConversationIntoThePromptToo() {
        ConversationContext context = new ConversationContext(List.of(
                new ConversationExchange("What is the deploy window?", "Tuesdays, 02:00-04:00 IST.")));

        when(historyService.recentContext(any())).thenReturn(context);
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.stream(any())).thenReturn(Flux.just("It is enforced by the pipeline."));
        when(chatSessionService.recordTurn(any(), any())).thenReturn(session("Deploy window"));

        chatService.stream(new ChatQuery("Who enforces it?", CHAT_ID, user), new RecordingListener());

        ArgumentCaptor<LlmPrompt> prompt = ArgumentCaptor.forClass(LlmPrompt.class);
        verify(llmGateway).stream(prompt.capture());
        assertThat(prompt.getValue().history()).isEqualTo(context.exchanges());
    }

    @Test
    void firstQuestionOfAConversation_carriesNoHistory() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk()));
        when(llmGateway.complete(any())).thenReturn("An answer.");
        when(chatSessionService.recordTurn(any(), any())).thenReturn(session("A title"));

        chatService.chat(new ChatQuery("What is the deploy window?", CHAT_ID, user));

        ArgumentCaptor<LlmPrompt> prompt = ArgumentCaptor.forClass(LlmPrompt.class);
        verify(llmGateway).complete(prompt.capture());
        assertThat(prompt.getValue().hasHistory()).isFalse();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static RetrievedChunk chunk() {
        return RetrievedChunk.builder()
                .chunkId("c1")
                .content("Content about feature X")
                .pageId("123")
                .title("Feature X Guide")
                .pageUrl("http://confluence/pages/123")
                .spaceKey("ENG")
                .sectionHeading("Configuration")
                .chunkType("TEXT")
                .similarity(0.85)
                .build();
    }

    private static ChatSessionResponse session(String title) {
        return new ChatSessionResponse(CHAT_ID, title, false, 2, Instant.now(), Instant.now(),
                true, null);
    }

    private static final class RecordingListener implements ChatStreamListener {
        private final List<String> tokens = new ArrayList<>();
        private List<SourceReference> sources = List.of();
        private ChatApiResponse completed;
        private String failure;

        @Override public void onSources(List<SourceReference> sources) { this.sources = sources; }
        @Override public void onToken(String delta) { tokens.add(delta); }
        @Override public void onCompleted(ChatApiResponse response) { this.completed = response; }
        @Override public void onFailed(String message) { this.failure = message; }
    }
}
