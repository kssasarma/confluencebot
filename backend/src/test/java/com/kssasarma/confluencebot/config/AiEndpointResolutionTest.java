package com.kssasarma.confluencebot.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the shipped {@code application.yml} actually resolves to for each of the three roles.
 *
 * <p>Answering, embedding and re-ranking each reach their own endpoint with their own key, and
 * every one of those six values is a placeholder over a fallback. The fallbacks are the point:
 * almost every deployment sets {@code AI_BASE_URL} and {@code AI_API_KEY} alone and expects all
 * three roles to follow, and the only way to see that they do is to resolve the file.
 *
 * <p>The api-key cases are not symmetry for its own sake. Spring AI resolves a model-level
 * base-url with {@code StringUtils.hasText}, so an empty one falls back — but it treats an
 * explicitly empty api-key as a deliberate "this endpoint needs no auth" and stops inheriting
 * there. Writing {@code ${CHAT_API_KEY:}} would therefore send an empty key to a hosted endpoint
 * the moment someone left that variable unset, and the failure would be a 401 from a server the
 * configuration says nothing about.
 */
class AiEndpointResolutionTest {

    private static final String SHARED_BASE_URL = "spring.ai.openai.base-url";
    private static final String SHARED_API_KEY  = "spring.ai.openai.api-key";
    private static final String CHAT_BASE_URL   = "spring.ai.openai.chat.base-url";
    private static final String CHAT_API_KEY    = "spring.ai.openai.chat.api-key";
    private static final String CHAT_MODEL      = "spring.ai.openai.chat.options.model";
    private static final String EMBED_BASE_URL  = "spring.ai.openai.embedding.base-url";
    private static final String EMBED_API_KEY   = "spring.ai.openai.embedding.api-key";
    private static final String EMBED_MODEL     = "spring.ai.openai.embedding.options.model";
    private static final String RERANK_BASE_URL = "chat.rerank.base-url";
    private static final String RERANK_API_KEY  = "chat.rerank.api-key";
    private static final String RERANK_MODEL    = "chat.rerank.model";

    @Test
    void withNothingConfiguredEveryRoleLandsOnTheBundledLocalServer() {
        StandardEnvironment environment = load(Map.of());

        assertThat(environment.getProperty(CHAT_BASE_URL)).isEqualTo("http://localhost:11434/v1");
        assertThat(environment.getProperty(EMBED_BASE_URL)).isEqualTo("http://localhost:11434/v1");
        assertThat(environment.getProperty(CHAT_API_KEY)).isEqualTo("dummy");
        assertThat(environment.getProperty(EMBED_API_KEY)).isEqualTo("dummy");
    }

    @Test
    void theSharedEndpointIsInheritedByEveryRoleThatIsNotGivenOneOfItsOwn() {
        StandardEnvironment environment = load(Map.of(
                "AI_BASE_URL", "https://models.example.com/v1",
                "AI_API_KEY", "shared-key"));

        assertThat(environment.getProperty(CHAT_BASE_URL)).isEqualTo("https://models.example.com/v1");
        assertThat(environment.getProperty(EMBED_BASE_URL)).isEqualTo("https://models.example.com/v1");
        assertThat(environment.getProperty(CHAT_API_KEY)).isEqualTo("shared-key");
        assertThat(environment.getProperty(EMBED_API_KEY)).isEqualTo("shared-key");
    }

    /**
     * The regression this file exists for: an unset per-role key must resolve to the shared key,
     * never to the empty string that Spring AI reads as "send no credentials".
     */
    @Test
    void aRoleWithoutItsOwnKeyIsNeverLeftWithAnEmptyOne() {
        StandardEnvironment environment = load(Map.of("AI_API_KEY", "shared-key"));

        assertThat(environment.getProperty(CHAT_API_KEY))
                .describedAs("an empty key is a no-auth signal to Spring AI, not an absent one")
                .isEqualTo("shared-key");
        assertThat(environment.getProperty(EMBED_API_KEY)).isEqualTo("shared-key");
    }

    @Test
    void oneRoleCanBeMovedToItsOwnServerWithoutDisturbingTheOthers() {
        StandardEnvironment environment = load(Map.of(
                "AI_BASE_URL", "http://gpu-box:11434/v1",
                "AI_API_KEY", "shared-key",
                "CHAT_BASE_URL", "https://api.hosted.example.com/v1",
                "CHAT_API_KEY", "hosted-key",
                "CHAT_MODEL", "big-writer"));

        assertThat(environment.getProperty(CHAT_BASE_URL)).isEqualTo("https://api.hosted.example.com/v1");
        assertThat(environment.getProperty(CHAT_API_KEY)).isEqualTo("hosted-key");
        assertThat(environment.getProperty(CHAT_MODEL)).isEqualTo("big-writer");

        // Embedding is untouched: it still reads the shared server, and its vectors stay
        // comparable with everything already in the store.
        assertThat(environment.getProperty(EMBED_BASE_URL)).isEqualTo("http://gpu-box:11434/v1");
        assertThat(environment.getProperty(EMBED_API_KEY)).isEqualTo("shared-key");
    }

    /**
     * Re-ranking is resolved in Java rather than by the placeholder, so what the file must give it
     * is an <i>unset</i> value — blank, not the chat endpoint copied in — for
     * {@link ChatRerankConfiguration} to inherit from.
     */
    @Test
    void reRankingIsLeftBlankUntilItIsGivenSomethingOfItsOwn() {
        StandardEnvironment environment = load(Map.of("AI_BASE_URL", "http://gpu-box:11434/v1"));

        assertThat(environment.getProperty(RERANK_BASE_URL)).isEmpty();
        assertThat(environment.getProperty(RERANK_API_KEY)).isEmpty();
        assertThat(environment.getProperty(RERANK_MODEL)).isEmpty();
    }

    @Test
    void reRankingCanBeMovedToItsOwnServerToo() {
        StandardEnvironment environment = load(Map.of(
                "RERANK_BASE_URL", "http://small-model-box:11434/v1",
                "RERANK_API_KEY", "rerank-key",
                "RERANK_MODEL", "qwen2.5:3b"));

        assertThat(environment.getProperty(RERANK_BASE_URL)).isEqualTo("http://small-model-box:11434/v1");
        assertThat(environment.getProperty(RERANK_API_KEY)).isEqualTo("rerank-key");
        assertThat(environment.getProperty(RERANK_MODEL)).isEqualTo("qwen2.5:3b");
    }

    @Test
    void theModelOfEachRoleIsItsOwnSetting() {
        StandardEnvironment environment = load(Map.of(
                "CHAT_MODEL", "writer", "EMBED_MODEL", "embedder", "RERANK_MODEL", "ranker"));

        assertThat(environment.getProperty(CHAT_MODEL)).isEqualTo("writer");
        assertThat(environment.getProperty(EMBED_MODEL)).isEqualTo("embedder");
        assertThat(environment.getProperty(RERANK_MODEL)).isEqualTo("ranker");
    }

    /**
     * The real {@code application.yml} over the given environment variables and nothing else.
     *
     * <p>The default property sources are dropped deliberately: with them, a machine that happens
     * to export {@code AI_BASE_URL} would quietly answer these assertions instead of the file.
     */
    private static StandardEnvironment load(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources propertySources) {
                // no system properties, no system environment
            }
        };
        environment.getPropertySources()
                .addFirst(new MapPropertySource("environment-variables", environmentVariables));

        try {
            new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read application.yml", e);
        }
        return environment;
    }
}
