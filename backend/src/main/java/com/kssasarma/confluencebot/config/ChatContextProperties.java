package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How much of a conversation is carried into the next answer.
 *
 * <p>Every value here trades answer quality against prompt size and latency. The defaults are
 * chosen so a normal back-and-forth stays coherent without the prompt growing without bound: the
 * history a model sees is capped at {@code maxExchanges × (question + maxAnswerChars)} characters,
 * which is a fixed ceiling no matter how long the conversation runs.
 *
 * <p>Each value carries its own default rather than relying on the shipped {@code application.yml}.
 * Record binding fills an absent property with zero, and a zero here does not fail loudly — it
 * turns the feature off, or leaves a null deadline for the rewriter to trip over. A deployment that
 * overrides one key would silently lose the rest.
 */
@Validated
@ConfigurationProperties(prefix = "chat.context")
public record ChatContextProperties(

        /** Master switch. Off, every question is answered on its own, as it was before. */
        @DefaultValue("true") boolean enabled,

        /** How many previous question-and-answer pairs are carried into the next turn. */
        @DefaultValue("6") @PositiveOrZero int maxExchanges,

        /** Characters of each previous answer kept. Answers run to thousands of characters and
         *  are mostly detail the follow-up does not need; what matters is the topic and the
         *  shape of what was said. */
        @DefaultValue("1200") @Positive int maxAnswerChars,

        /** Rewrite a follow-up into a standalone query before retrieval. Without this the model
         *  gets the conversation but the wrong documents, which is the worse half of the problem:
         *  "and in staging?" retrieves on the word "staging" alone. */
        @DefaultValue("true") boolean rewriteEnabled,

        /** How long the rewrite may take before retrieval proceeds with the raw question. It sits
         *  on the critical path, so this is the ceiling on the latency the feature can add. */
        @DefaultValue("PT3S") Duration rewriteTimeout,

        /** Longest accepted rewrite. A standalone question is a question; anything longer is the
         *  model having answered, explained or rambled, and is discarded. */
        @DefaultValue("400") @Positive int rewriteMaxChars
) {

    /** Whether any history should be loaded at all. */
    public boolean historyEnabled() {
        return enabled && maxExchanges > 0;
    }

    /** Whether a follow-up should be condensed before it reaches retrieval. */
    public boolean queryRewritingEnabled() {
        return historyEnabled() && rewriteEnabled;
    }
}
