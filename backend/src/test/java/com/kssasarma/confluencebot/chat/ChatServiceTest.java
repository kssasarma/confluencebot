package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
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
    @Mock private LlmGateway llmGateway;
    @Mock private PreferenceService preferenceService;
    @Mock private ChatSessionService chatSessionService;
    @Mock private User user;

    private final ConfluencePromptBuilder promptBuilder = new ConfluencePromptBuilder();

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(hybridSearchService, promptBuilder, llmGateway,
                preferenceService, chatSessionService, 0.4);
        when(preferenceService.resolve(any(), any())).thenReturn(EffectiveChatPreferences.defaults());
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
        return new ChatSessionResponse(CHAT_ID, title, false, 2, Instant.now(), Instant.now());
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
