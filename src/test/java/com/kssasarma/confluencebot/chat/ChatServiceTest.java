package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.chat.prompt.ConfluencePromptBuilder;
import com.kssasarma.confluencebot.config.ChatRetrievalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;
    @Mock private VectorStore vectorStore;

    private final ConfluencePromptBuilder promptBuilder = new ConfluencePromptBuilder();
    private final ChatRetrievalProperties retrievalProps = new ChatRetrievalProperties(5, 0.70);

    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        chatService = new ChatServiceImpl(chatClientBuilder, vectorStore, promptBuilder, retrievalProps);
    }

    @Test
    void noRelevantDocs_returnsNoContextResponse() {
        // Both the primary search and the overview search return nothing
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of());

        ChatApiResponse response = chatService.chat("How do I configure X?");

        assertThat(response.answer()).contains("could not find");
        assertThat(response.sources()).isEmpty();
        verifyNoInteractions(chatClient);
    }

    @Test
    void withRelevantDocs_callsLlmAndReturnsSources() {
        Document doc = new Document("Content about feature X", Map.of(
                "page_id", "123",
                "title", "Feature X Guide",
                "page_url", "http://confluence/pages/123",
                "space_key", "ENG",
                "section_heading", "Configuration"
        ));

        // Primary search returns the page chunk; overview search returns nothing
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc))
                .thenReturn(List.of());
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
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
    void withSpaceOverview_broadQueryIncludesOverviewInContextButNotSources() {
        Document overviewDoc = new Document("Space: Engineering (ENG)\n\nThe ENG space contains...", Map.of(
                "page_id", "__space__ENG",
                "document_type", "space_overview",
                "title", "Engineering — Space Overview",
                "page_url", "http://confluence/display/ENG",
                "space_key", "ENG",
                "section_heading", ""
        ));

        // Primary search returns nothing; overview search returns the space overview
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(overviewDoc));
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("The ENG space manages engineering documentation.");

        ChatApiResponse response = chatService.chat("What is the ENG space doing?");

        assertThat(response.answer()).contains("ENG space");
        // Overview doc is context-only — it must not appear in citations
        assertThat(response.sources()).isEmpty();
    }
}
