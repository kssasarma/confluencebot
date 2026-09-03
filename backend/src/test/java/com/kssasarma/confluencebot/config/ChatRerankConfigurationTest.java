package com.kssasarma.confluencebot.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Which model the re-rank pass ends up talking to, and over which connection.
 *
 * <p>Both are invisible at runtime — a re-rank sent to the wrong endpoint fails quietly and the
 * answer is returned in MMR order, which is a correct answer built from a worse ordering. Nothing
 * in the logs distinguishes that from a re-rank that ran. So the wiring is asserted here instead.
 *
 * <p>The distinction that matters is when a second connection is opened. Sharing the answer client
 * is what a deployment on one server should get: same pool, same customizers, no second OkHttp
 * stack for a call that is going to the same place. Opening a separate one is for when the
 * configuration says the call is going somewhere else — and a key of its own means somewhere else,
 * even on the same host.
 */
class ChatRerankConfigurationTest {

    private static final String CHAT_MODEL = "spring.ai.openai.chat.options.model";

    private final ChatClient answerEndpointClient = mock(ChatClient.class);
    private final ChatClient.Builder answerEndpointBuilder = mock(ChatClient.Builder.class, RETURNS_SELF);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RerankConfigurationUnderTest.class)
            .withBean(ChatClient.Builder.class, () -> {
                given(answerEndpointBuilder.build()).willReturn(answerEndpointClient);
                return answerEndpointBuilder;
            })
            .withPropertyValues(CHAT_MODEL + "=big-writer",
                    "spring.ai.openai.base-url=http://gpu-box:11434/v1",
                    "spring.ai.openai.api-key=shared-key");

    @Test
    void withNoReRankSettingsAtAllTheAnswerModelKeepsDoingTheRanking() {
        // The behaviour this pass had before it could be configured: same endpoint, same model.
        runner.run(context -> {
            assertThat(context.getBean("rerankChatClient")).isSameAs(answerEndpointClient);
            assertThat(defaultOptions().getModel()).isEqualTo("big-writer");
        });
    }

    @Test
    void aModelOfItsOwnDoesNotCostASecondConnection() {
        runner.withPropertyValues("chat.rerank.model=small-ranker").run(context -> {
            assertThat(context.getBean("rerankChatClient"))
                    .describedAs("same endpoint, so the answer client is reused")
                    .isSameAs(answerEndpointClient);
            assertThat(defaultOptions().getModel()).isEqualTo("small-ranker");
        });
    }

    @Test
    void rankingIsDeterministicAndShortByDefault() {
        runner.run(context -> {
            assertThat(defaultOptions().getTemperature()).isEqualTo(0.0);
            assertThat(defaultOptions().getMaxTokens()).isEqualTo(64);
        });
    }

    @Test
    void anEndpointOfItsOwnGetsAClientOfItsOwn() {
        runner.withPropertyValues(
                "chat.rerank.base-url=http://small-model-box:11434/v1",
                "chat.rerank.api-key=rerank-key",
                "chat.rerank.model=small-ranker").run(context -> {
            assertThat(context.getBean("rerankChatClient")).isNotSameAs(answerEndpointClient);
            assertThat(context).hasNotFailed();
        });
    }

    /** Same host, different credentials is still a different connection. */
    @Test
    void aKeyOfItsOwnIsEnoughToNeedItsOwnClient() {
        runner.withPropertyValues("chat.rerank.api-key=a-different-tenant").run(context ->
                assertThat(context.getBean("rerankChatClient")).isNotSameAs(answerEndpointClient));
    }

    @Test
    void anAbsentReRankBlockLeavesThePassOnWithUsableDefaults() {
        // Record binding fills an absent property with zero: a zero maxTokens would truncate
        // every reply, and a false 'enabled' would turn the pass off for anyone who never
        // configured it.
        runner.run(context -> {
            ChatRerankProperties properties = context.getBean(ChatRerankProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.maxTokens()).isEqualTo(64);
            assertThat(properties.temperature()).isEqualTo(0.0);
            assertThat(properties.hasOwnConnection()).isFalse();
        });
    }

    @Test
    void turningTheLlmPassOffLeavesTheRestOfTheConfigurationAlone() {
        runner.withPropertyValues("chat.rerank.enabled=false").run(context -> {
            assertThat(context.getBean(ChatRerankProperties.class).enabled()).isFalse();
            // Still wired: the switch belongs to the service, not to the bean's existence.
            assertThat(context).hasBean("rerankChatClient");
        });
    }

    /**
     * Given no endpoint at all, Spring AI would fall back to api.openai.com — and re-ranking sends
     * the retrieved excerpts themselves. Refusing to start is the only safe reading of a
     * half-configured connection.
     */
    @Test
    void aConnectionOfItsOwnWithNowhereToReachRefusesToStart() {
        new ApplicationContextRunner()
                .withUserConfiguration(RerankConfigurationUnderTest.class)
                .withBean(ChatClient.Builder.class, () -> answerEndpointBuilder)
                .withPropertyValues("chat.rerank.api-key=orphaned-key")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("RERANK_BASE_URL"));
    }

    @Test
    void eachSettingFallsBackToTheNextOneOutAndNeverToNull() {
        assertThat(ChatRerankConfiguration.inherit("rerank", "chat", "shared")).isEqualTo("rerank");
        assertThat(ChatRerankConfiguration.inherit("", "chat", "shared")).isEqualTo("chat");
        assertThat(ChatRerankConfiguration.inherit("", "  ", "shared")).isEqualTo("shared");
        assertThat(ChatRerankConfiguration.inherit("", null, "")).isEmpty();
    }

    private ChatOptions defaultOptions() {
        ArgumentCaptor<ChatOptions.Builder> captor = ArgumentCaptor.forClass(ChatOptions.Builder.class);
        verify(answerEndpointBuilder).defaultOptions(captor.capture());
        return captor.getValue().build();
    }

    @EnableConfigurationProperties(ChatRerankProperties.class)
    static class RerankConfigurationUnderTest extends ChatRerankConfiguration {
    }
}
