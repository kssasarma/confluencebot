package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.chat.context.ConversationExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The last link in the chain: everything upstream is worthless if the conversation is assembled
 * and then dropped on the floor here.
 */
class SpringAiLlmGatewayTest {

    private final ChatClient chatClient = mock(ChatClient.class);
    private final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
    private final ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);

    private SpringAiLlmGateway gateway;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.messages(any(List.class))).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("An answer.");

        gateway = new SpringAiLlmGateway(builder);
    }

    @Test
    void eachEarlierExchange_isReplayedAsTheUserAndAssistantMessagesItWas() {
        gateway.complete(new LlmPrompt("System rules", "This turn's question", List.of(
                new ConversationExchange("First question", "First answer"),
                new ConversationExchange("Second question", "Second answer"))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(spec).messages(messages.capture());

        assertThat(messages.getValue()).hasSize(4);
        assertThat(messages.getValue().get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.getValue().get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.getValue()).extracting(Message::getText).containsExactly(
                "First question", "First answer", "Second question", "Second answer");

        // The standing rules and this turn keep their own slots either side of the conversation.
        verify(spec).system("System rules");
        verify(spec).user("This turn's question");
    }

    @Test
    void aPromptWithNoConversation_sendsNoExtraMessages() {
        gateway.complete(new LlmPrompt("System rules", "A first question"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(spec).messages(messages.capture());

        assertThat(messages.getValue()).isEmpty();
    }
}
