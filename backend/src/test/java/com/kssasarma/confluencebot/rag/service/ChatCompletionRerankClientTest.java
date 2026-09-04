package com.kssasarma.confluencebot.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatCompletionRerankClientTest {

    @Test
    void sendsAChatCompletionAndReturnsItsPermutation() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("[1, 0]");

        List<Integer> order = new ChatCompletionRerankClient(chatClient)
                .rerank("What changed?", List.of("First", "Second"));

        assertThat(order).containsExactly(1, 0);
        verify(request).system(anyString());
        verify(request).user(anyString());
    }

    @Test
    void acceptsOnlyACompleteZeroBasedPermutation() {
        assertThat(ChatCompletionRerankClient.parseOrder("```json\n[2, 0, 1]\n```", 3))
                .containsExactly(2, 0, 1);
    }

    @Test
    void skipsAnInvalidArrayBeforeTheCompleteOrder() {
        assertThat(ChatCompletionRerankClient.parseOrder("Considering [0, 1], final: [2, 0, 1]", 3))
                .containsExactly(2, 0, 1);
    }

    @Test
    void rejectsAResponseWithoutACompletePermutation() {
        assertThatThrownBy(() -> ChatCompletionRerankClient.parseOrder("[1, 1, 0]", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete JSON index array");
        assertThatThrownBy(() -> ChatCompletionRerankClient.parseOrder("[1, 0]", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete JSON index array");
    }

    @Test
    void formatsTheQuestionAndAllZeroBasedExcerptIndexes() {
        String prompt = ChatCompletionRerankClient.userPrompt("What changed?", List.of("First", "Second"));

        assertThat(prompt).contains("Question:\nWhat changed?", "[0]\nFirst", "[1]\nSecond");
    }
}
