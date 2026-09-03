package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.chat.confidence.ConfidenceScorer;
import com.kssasarma.confluencebot.chat.confidence.ConfidenceSignals;
import com.kssasarma.confluencebot.chat.confidence.WeightedSignalConfidenceScorer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring, not scoring — {@code WeightedSignalConfidenceScorerTest} covers the arithmetic.
 *
 * <p>These exist because the default scorer once carried {@code @ConditionalOnMissingBean} on its
 * own {@code @Component}: the condition matched the class's own scanned definition, removed it,
 * and the application failed to start with no {@link ConfidenceScorer} at all. Nothing in the unit
 * tests could see that, because they construct the scorer directly.
 */
class ChatConfidenceConfigurationTest {

    /** The same keys application.yml sets, so relaxed binding is exercised too. */
    private static final String[] DEFAULT_PROPERTIES = {
            "chat.confidence.top-similarity-weight=0.35",
            "chat.confidence.mean-similarity-weight=0.25",
            "chat.confidence.page-agreement-weight=0.15",
            "chat.confidence.citation-weight=0.25",
            "chat.confidence.similarity-floor=0.30",
            "chat.confidence.page-agreement-target=3",
            "chat.confidence.sufficient-citations=2"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(DEFAULT_PROPERTIES)
            .withUserConfiguration(ConfidencePackageScan.class, ChatConfidenceConfiguration.class);

    @Test
    void componentScanningLeavesExactlyOneScorerBehind() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ConfidenceScorer.class);
            assertThat(context.getBean(ConfidenceScorer.class))
                    .isInstanceOf(WeightedSignalConfidenceScorer.class);
        });
    }

    /**
     * A scorer built from unbound properties would have zero total weight and answer 0.0 to
     * everything — present in the context, useless in production. Scoring perfect evidence proves
     * the configured weights actually reached it.
     */
    @Test
    void theWiredScorerIsBoundToTheConfiguredWeights() {
        runner.run(context -> {
            ConfidenceSignals perfect = new ConfidenceSignals(1.0, 1.0, 3, 5, 5, 5);
            assertThat(context.getBean(ConfidenceScorer.class).score(perfect)).isEqualTo(1.0);
        });
    }

    @Test
    void aDeploymentScorerReplacesTheDefault() {
        ConfidenceScorer always = signals -> 0.42;

        runner.withBean(ConfidenceScorer.class, () -> always).run(context -> {
            assertThat(context).hasSingleBean(ConfidenceScorer.class);
            assertThat(context.getBean(ConfidenceScorer.class)).isSameAs(always);
        });
    }

    /** Stands in for the application's own scan, which reaches this package the same way. */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.kssasarma.confluencebot.chat.confidence")
    @EnableConfigurationProperties(ChatConfidenceProperties.class)
    static class ConfidencePackageScan {
    }
}
