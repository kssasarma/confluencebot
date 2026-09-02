package com.kssasarma.confluencebot.chat;

import reactor.core.publisher.Flux;

/**
 * The only way the chat pipeline talks to a language model.
 *
 * Keeping this behind an interface is what lets the resilience policy, the model vendor and the
 * test doubles vary independently of the answer-building logic.
 */
public interface LlmGateway {

    /**
     * Generates a complete answer.
     *
     * @throws com.kssasarma.confluencebot.exception.LlmUnavailableException when the model cannot
     *         be reached or the call is refused
     */
    String complete(LlmPrompt prompt);

    /**
     * Generates the answer incrementally.
     *
     * The returned stream fails with
     * {@link com.kssasarma.confluencebot.exception.LlmUnavailableException} rather than a
     * vendor-specific error.
     */
    Flux<String> stream(LlmPrompt prompt);
}
