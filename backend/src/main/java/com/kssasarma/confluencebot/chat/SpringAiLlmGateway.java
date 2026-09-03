package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.chat.context.ConversationExchange;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI adapter — the only class in the codebase that knows which model library is in use.
 * Resilience is layered on top by {@link ResilientLlmGateway}.
 *
 * <p>The conversation is handed over as messages rather than as text. Spring AI assembles the
 * prompt as system message, then whatever {@code messages(...)} supplied, then the user message,
 * so the model receives the exchange it is continuing in the position it expects: the standing
 * rules first, the conversation in the middle, and this turn's question with its excerpts last.
 */
@Component("springAiLlmGateway")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(LlmPrompt prompt) {
        return request(prompt).call().content();
    }

    @Override
    public Flux<String> stream(LlmPrompt prompt) {
        return request(prompt).stream().content();
    }

    private ChatClient.ChatClientRequestSpec request(LlmPrompt prompt) {
        return chatClient.prompt()
                .system(prompt.system())
                .messages(asMessages(prompt.history()))
                .user(prompt.user());
    }

    /**
     * Replays each earlier exchange as the pair of messages it originally was.
     *
     * <p>The excerpts that produced a previous answer are deliberately not replayed with it. They
     * were retrieved for that question, they can run to thousands of characters each, and repeating
     * them every turn would grow the prompt without bound while competing with the excerpts
     * retrieved for the question actually being asked.
     */
    private static List<Message> asMessages(List<ConversationExchange> history) {
        List<Message> messages = new ArrayList<>(history.size() * 2);
        for (ConversationExchange exchange : history) {
            messages.add(new UserMessage(exchange.question()));
            messages.add(new AssistantMessage(exchange.answer()));
        }
        return messages;
    }
}
