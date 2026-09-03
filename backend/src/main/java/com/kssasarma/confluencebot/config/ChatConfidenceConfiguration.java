package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.chat.confidence.ConfidenceScorer;
import com.kssasarma.confluencebot.chat.confidence.WeightedSignalConfidenceScorer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the {@link ConfidenceScorer} the chat pipeline scores answers with.
 *
 * <p>The default is declared here as a {@code @Bean} rather than carrying {@code @Component} on
 * the class itself, because {@link ConditionalOnMissingBean} cannot guard a scanned component.
 * Component scanning registers the definition first and only then evaluates the condition, which
 * finds that very definition, concludes a scorer already exists, and removes it again — leaving
 * the application with no scorer at all and every injection point unsatisfied. On a {@code @Bean}
 * method the condition runs before the definition is registered, so it can only ever see somebody
 * else's bean.
 *
 * <p><strong>Replacing the default.</strong> Publish another {@link ConfidenceScorer} as a scanned
 * {@code @Component}: those definitions all exist by the time {@code @Bean} methods are read, so
 * the back-off below reliably sees it. A replacement declared as a {@code @Bean} in a different
 * configuration class should also be marked {@code @Primary} — configuration classes are processed
 * in no guaranteed order, so this method may run before the replacement is registered, and two
 * unqualified candidates would then fail the injection outright.
 */
@Configuration
public class ChatConfidenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConfidenceScorer.class)
    public ConfidenceScorer confidenceScorer(ChatConfidenceProperties properties) {
        return new WeightedSignalConfidenceScorer(properties);
    }
}
