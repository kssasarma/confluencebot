package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.config.ChatRetrievalProperties;
import com.kssasarma.confluencebot.rag.model.RetrievedChunk;
import com.kssasarma.confluencebot.rag.service.HybridSearchService;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;
    @Mock private HybridSearchService hybridSearchService;

    private final ConfluencePromptBuilder promptBuilder = new ConfluencePromptBuilder();
    private final ChatRetrievalProperties retrievalProps =
            new ChatRetrievalProperties(5, 0.70, 25, 800, 100, 0.7, 0.5, true, 0.4);

    // Real resilience instances — pass-through in tests, no throttling
    private final CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("test-llm");
    private final Bulkhead       bulkhead       = Bulkhead.ofDefaults("test-llm");

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        chatService = new ChatServiceImpl(
                chatClientBuilder, hybridSearchService, promptBuilder,
                retrievalProps, circuitBreaker, bulkhead);
    }

    @Test
    void noRelevantDocs_returnsNoContextResponse() {
        when(hybridSearchService.search(anyString())).thenReturn(List.of());

        ChatApiResponse response = chatService.chat("How do I configure X?");

        assertThat(response.answer()).contains("could not find");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(chatClient);
    }

    @Test
    void withRelevantDocs_callsLlmAndReturnsSources() {
        RetrievedChunk chunk = RetrievedChunk.builder()
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

        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Feature X is configured by...");

        ChatApiResponse response = chatService.chat("How do I configure feature X?");

        assertThat(response.answer()).isEqualTo("Feature X is configured by...");
        assertThat(response.sources()).hasSize(1);

        var source = response.sources().get(0);
        assertThat(source.title()).isEqualTo("Feature X Guide");
        assertThat(source.url()).isEqualTo("http://confluence/pages/123");
        assertThat(source.anchorUrl()).isEqualTo("http://confluence/pages/123#Configuration");
        assertThat(source.spaceKey()).isEqualTo("ENG");
    }

    @Test
    void withFollowUpQuestions_parsedAndReturnedSeparately() {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .chunkId("c1").content("Text").pageId("p1").title("Guide")
                .pageUrl("http://confluence/p1").spaceKey("ENG")
                .sectionHeading("").chunkType("TEXT").similarity(0.9).build();

        when(hybridSearchService.search(anyString())).thenReturn(List.of(chunk));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(
                "The answer is here.\n---FOLLOW-UP-QUESTIONS---\nHow do I do X?\nWhat about Y?\nCan I do Z?");

        ChatApiResponse response = chatService.chat("Tell me about the guide");

        assertThat(response.answer()).isEqualTo("The answer is here.");
        assertThat(response.followUpQuestions()).containsExactly(
                "How do I do X?", "What about Y?", "Can I do Z?");
    }
}
