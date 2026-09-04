package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.rag.service.LiteLlmRerankClient;
import com.kssasarma.confluencebot.rag.service.RerankClient;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * The model access used to order retrieved excerpts by how well they answer the question.
 *
 * <p>Spring AI resolves {@code spring.ai.openai.chat.*} and {@code spring.ai.openai.embedding.*}
 * over {@code spring.ai.openai.*} itself, so answering and embedding already reach their own
 * endpoints with their own keys. Re-ranking is not one of its model types, so the same resolution
 * is done here for {@link ChatRerankProperties} and the client is built to match.
 *
 * <p>Two ways to build it, and which one is used is decided by configuration rather than by a
 * flag:
 *
 * <ul>
 *   <li><b>Same endpoint as answers.</b> The auto-configured builder is reused with nothing but
 *       the options overridden, so re-ranking shares the answer connection pool, its observability
 *       and any {@code OpenAiHttpClientBuilderCustomizer} the deployment registers. A different
 *       model on the same server costs no second client.</li>
 *   <li><b>Its own endpoint or key.</b> A separate {@link OpenAiChatModel} is built for it. This
 *       is the only case that opens a second connection, and it opens it because it must.</li>
 * </ul>
 *
 * <p>What is deliberately <i>not</i> shared is the failure budget: {@code ResilienceConfig} gives
 * this pass its own circuit breaker and bulkhead, so a re-rank endpoint that is slow, throttled or
 * simply wrong degrades to the MMR order instead of consuming the permits answers depend on.
 */
@Configuration
public class ChatRerankConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatRerankConfiguration.class);

    /** Calls a native rerank API, rather than treating cross-encoders as chat-completion models. */
    @Bean
    public RerankClient rerankClient(
            RestClient.Builder restClientBuilder,
            ChatRerankProperties properties,
            @Value("${spring.ai.openai.base-url:}") String sharedBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String sharedApiKey,
            @Value("${spring.ai.openai.chat.base-url:}") String chatBaseUrl,
            @Value("${spring.ai.openai.chat.api-key:}") String chatApiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String chatModel) {
        String baseUrl = inherit(properties.baseUrl(), chatBaseUrl, sharedBaseUrl);
        String apiKey = StringUtils.hasText(properties.apiKey())
                ? properties.apiKey() : inherit(chatApiKey, sharedApiKey);
        String model = inherit(properties.model(), chatModel);
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("No re-rank endpoint configured: set RERANK_BASE_URL or CHAT_BASE_URL.");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("No re-rank model configured: set RERANK_MODEL or CHAT_MODEL.");
        }
        log.info("Re-ranking with model '{}' through native endpoint {}/rerank", model,
                baseUrl.replaceAll("/+$", ""));
        return new LiteLlmRerankClient(restClientBuilder, baseUrl, apiKey, model);
    }

    /**
     * @param answerChatClientBuilder the auto-configured builder — prototype-scoped, so overriding
     *                                its defaults here cannot affect the client answers are sent
     *                                through
     */
    @Bean("rerankChatClient")
    public ChatClient rerankChatClient(
            ChatClient.Builder answerChatClientBuilder,
            ChatRerankProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            @Value("${spring.ai.openai.base-url:}") String sharedBaseUrl,
            @Value("${spring.ai.openai.api-key:}") String sharedApiKey,
            @Value("${spring.ai.openai.chat.base-url:}") String chatBaseUrl,
            @Value("${spring.ai.openai.chat.api-key:}") String chatApiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String chatModel) {

        String model = inherit(properties.model(), chatModel);

        if (!properties.hasOwnConnection()) {
            log.info("Re-ranking with model '{}' on the chat endpoint", model);
            return answerChatClientBuilder.defaultOptions(options(properties, model)).build();
        }

        String baseUrl = inherit(properties.baseUrl(), chatBaseUrl, sharedBaseUrl);
        if (!StringUtils.hasText(baseUrl)) {
            // Spring AI falls back to api.openai.com when it is given no base URL. Re-ranking
            // sends the retrieved excerpts themselves, so guessing an endpoint would mean posting
            // internal documentation to a third party on the strength of a missing setting.
            throw new IllegalStateException(
                    "chat.rerank is configured with a connection of its own but no endpoint to "
                    + "reach: set RERANK_BASE_URL, or unset chat.rerank.api-key to re-rank on the "
                    + "chat endpoint.");
        }

        // Not inherit(): Spring AI reads an explicitly empty key as "this endpoint needs no auth",
        // and hasOwnConnection() is already true, so a blank key here is a deliberate one.
        String apiKey = StringUtils.hasText(properties.apiKey())
                ? properties.apiKey()
                : inherit(chatApiKey, sharedApiKey);

        log.info("Re-ranking with model '{}' on its own endpoint {}", model, baseUrl);

        ObservationRegistry observations =
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);

        OpenAiChatModel rerankModel = OpenAiChatModel.builder()
                // No OpenAIClient is supplied, so the builder opens one of its own from the
                // connection details carried on the options.
                .options(options(properties, model).baseUrl(baseUrl).apiKey(apiKey).build())
                .observationRegistry(observations)
                .build();

        return ChatClient.create(rerankModel, observations);
    }

    /** Everything about the call that does not depend on where it is sent. */
    private static OpenAiChatOptions.Builder options(ChatRerankProperties properties, String model) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .temperature(properties.temperature())
                .maxTokens(properties.maxTokens());

        // Only when there is one to set: an empty model would override whatever the endpoint is
        // already configured with rather than leave it alone.
        return StringUtils.hasText(model) ? options.model(model) : options;
    }

    /** The first value that was actually configured, or an empty string if none was. */
    static String inherit(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) return candidate;
        }
        return "";
    }
}
