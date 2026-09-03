package com.kssasarma.confluencebot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * The model that judges which retrieved excerpts actually answer the question.
 *
 * <p>Its own endpoint, key and model, because re-ranking is not the job the answer model was
 * chosen for. It emits a permutation — {@code "3,1,2"} — never prose, it sits on the critical path
 * before a single token of the answer is streamed, and it is called once per question. The model
 * that writes a good answer is rarely the cheapest model that can order five excerpts, and on a
 * hosted endpoint the two are not even billed alike.
 *
 * <p>Everything here is optional and empty means inherit: a deployment that only wants a smaller
 * model sets {@code model} and nothing else; one that runs re-ranking on a local server while
 * answering from a hosted one sets {@code base-url} too. The resolution order for each field is
 * this block, then {@code spring.ai.openai.chat.*}, then {@code spring.ai.openai.*}.
 *
 * <p>Each value carries its own default rather than relying on the shipped {@code application.yml}
 * — record binding fills an absent property with zero, and a zero {@code maxTokens} would truncate
 * every reply into an unparseable one, silently costing a model call per question and returning
 * the MMR order anyway.
 */
@Validated
@ConfigurationProperties(prefix = "chat.rerank")
public record ChatRerankProperties(

        /** Master switch for the LLM pass. Off, retrieval still re-ranks by MMR — see
         *  {@code chat.retrieval.rerank-mmr-lambda}, which is arithmetic and costs nothing. */
        @DefaultValue("true") boolean enabled,

        /** Endpoint for the re-rank call. Empty inherits the chat endpoint. */
        @DefaultValue("") String baseUrl,

        /** Key for the re-rank endpoint. Empty inherits the chat key. Set this on its own when
         *  re-ranking runs on the same host under a different key or tenant. */
        @DefaultValue("") String apiKey,

        /** Model that does the ranking. Empty inherits the chat model, which is what this
         *  pass used before it could be configured separately. */
        @DefaultValue("") String model,

        /** Ranking is a decision, not a composition: the same excerpts should produce the same
         *  order twice. Deliberately lower than the answer model's default. */
        @DefaultValue("0.0") @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,

        /** A permutation of at most {@code chat.retrieval.top-k} numbers needs a handful of
         *  tokens. Raise it for a model that thinks aloud before answering — a reply truncated
         *  mid-thought parses to nothing and falls back to the MMR order, which is the cost of a
         *  model call for no change. */
        @DefaultValue("64") @Positive Integer maxTokens
) {

    /**
     * Whether re-ranking must talk to somewhere other than where answers come from.
     *
     * <p>A key on its own counts: same host, different credentials is still a different
     * connection, and quietly reusing the answer client would send the wrong key.
     */
    public boolean hasOwnConnection() {
        return StringUtils.hasText(baseUrl) || StringUtils.hasText(apiKey);
    }
}
