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
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

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
                "page_url", "http://confluence/pages/123"
        ));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Feature X is configured by...");

        ChatApiResponse response = chatService.chat("How do I configure feature X?");

        assertThat(response.answer()).isEqualTo("Feature X is configured by...");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).title()).isEqualTo("Feature X Guide");
    }
}
