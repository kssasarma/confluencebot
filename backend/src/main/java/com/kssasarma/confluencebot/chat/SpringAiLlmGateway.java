package com.kssasarma.confluencebot.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI adapter — the only class in the codebase that knows which model library is in use.
 * Resilience is layered on top by {@link ResilientLlmGateway}.
 */
@Component("springAiLlmGateway")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient chatClient;

    public SpringAiLlmGateway(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public String complete(LlmPrompt prompt) {
        return chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user())
                .call()
                .content();
    }

    @Override
    public Flux<String> stream(LlmPrompt prompt) {
        return chatClient.prompt()
                .system(prompt.system())
                .user(prompt.user())
                .stream()
                .content();
    }
}
