package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.LlmPrompt;
import com.kssasarma.confluencebot.chat.ResilientLlmGateway;
import com.kssasarma.confluencebot.chat.SpringAiLlmGateway;
import com.kssasarma.confluencebot.chat.context.FollowUpQueryRewriter;
import com.kssasarma.confluencebot.chat.context.LlmFollowUpQueryRewriter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring, not behaviour — the unit tests construct these collaborators directly and so cannot see
 * a context that fails to start.
 *
 * <p>What is checked here is the one thing publishing a second {@link LlmGateway} puts at risk: an
 * injection point that used to resolve to the single primary gateway now has three candidates. If
 * that ever became ambiguous, or if the rewriter silently picked up the answer-generation gateway
 * and its permits, no unit test in the suite would notice and the failure would surface in
 * production as answers refused under load.
 */
class ChatContextConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ContextPackageScan.class, ResilienceConfig.class,
                    ChatContextConfiguration.class, AsyncConfig.class)
            .withBean("springAiLlmGateway", LlmGateway.class, StubGateway::new);

    @Test
    void aThirdGatewayLeavesByTypeInjectionUnambiguous() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // Every collaborator that asks for an LlmGateway without a qualifier — the chat
            // service, the title refiner — must still land on the answer-generation one.
            assertThat(context.getBean(LlmGateway.class))
                    .isSameAs(context.getBean(ResilientLlmGateway.class));
        });
    }

    @Test
    void theRewriteGetsItsOwnGateway_notTheOneAnswersDependOn() {
        runner.run(context -> {
            LlmGateway answerGateway = context.getBean(LlmGateway.class);
            LlmGateway rewriteGateway = (LlmGateway) context.getBean("contextLlmGateway");

            assertThat(rewriteGateway).isNotSameAs(answerGateway);
        });
    }

    @Test
    void theRewritePermitsAreSeparateFromTheAnswerPermits() {
        runner.run(context -> {
            assertThat(context.getBean("contextBulkhead", Bulkhead.class))
                    .isNotSameAs(context.getBean("llmBulkhead", Bulkhead.class));
            assertThat(context.getBean("contextCircuitBreaker", CircuitBreaker.class))
                    .isNotSameAs(context.getBean("llmCircuitBreaker", CircuitBreaker.class));
        });
    }

    /** Waiting for a permit is pointless under a deadline the caller abandons first. */
    @Test
    void theRewriteBulkheadRefusesRatherThanQueues() {
        runner.run(context -> assertThat(context.getBean("contextBulkhead", Bulkhead.class)
                .getBulkheadConfig().getMaxWaitDuration()).isEqualTo(Duration.ZERO));
    }

    @Test
    void theRewriterIsWiredAndReadsItsDefaultsWithoutAnyConfiguration() {
        // No property values at all: an absent chat.context block must not silently disable it.
        runner.run(context -> {
            assertThat(context).hasSingleBean(FollowUpQueryRewriter.class);

            ChatContextProperties properties = context.getBean(ChatContextProperties.class);
            assertThat(properties.historyEnabled()).isTrue();
            assertThat(properties.queryRewritingEnabled()).isTrue();
            assertThat(properties.rewriteTimeout()).isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void turningContextOffTakesRewritingWithIt() {
        runner.withPropertyValues("chat.context.enabled=false").run(context -> {
            ChatContextProperties properties = context.getBean(ChatContextProperties.class);
            assertThat(properties.historyEnabled()).isFalse();
            assertThat(properties.queryRewritingEnabled()).isFalse();
        });
    }

    /**
     * The rewriter and the primary gateway it must not be confused with.
     *
     * <p>Scanned rather than registered by hand so the real {@code @Primary} and the real
     * {@code @Qualifier} on their constructors are what decide the wiring — registering them
     * directly would prove only that this test can build objects. The persistent history service
     * is left out: it needs JPA repositories, which is a different question from bean resolution.
     */
    @Configuration
    @EnableConfigurationProperties(ChatContextProperties.class)
    @ComponentScan(
            basePackages = "com.kssasarma.confluencebot.chat",
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ResilientLlmGateway.class, LlmFollowUpQueryRewriter.class}))
    static class ContextPackageScan {
    }

    /** Stands in for {@link SpringAiLlmGateway}, which would need a live model client. */
    private static final class StubGateway implements LlmGateway {
        @Override public String complete(LlmPrompt prompt) { return ""; }
        @Override public reactor.core.publisher.Flux<String> stream(LlmPrompt prompt) {
            return reactor.core.publisher.Flux.empty();
        }
    }
}
