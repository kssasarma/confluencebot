package com.kssasarma.confluencebot.config;

import com.kssasarma.confluencebot.chat.LlmGateway;
import com.kssasarma.confluencebot.chat.ResilientLlmGateway;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The model access used to make a follow-up question searchable on its own.
 *
 * <p>A second gateway rather than the primary one, for two reasons that both come down to the same
 * rule: an auxiliary call must never degrade the answer it exists to improve.
 *
 * <ul>
 *   <li><b>Its own permits.</b> Sharing the answer bulkhead would let a handful of concurrent
 *       rewrites exhaust it and turn somebody else's question into "the AI service is temporarily
 *       unavailable" — trading a better search for no answer at all.</li>
 *   <li><b>No retries.</b> The rewrite runs under a deadline of a few seconds with the user
 *       waiting. Spending that budget on backoff would push a rewrite that could have succeeded
 *       past the point where anyone is still listening; failing at once leaves retrieval with the
 *       question as asked, which is exactly where it started.</li>
 * </ul>
 */
@Configuration
public class ChatContextConfiguration {

    /** One attempt: under a deadline, a retry costs more than it can win back. */
    private static final int SINGLE_ATTEMPT = 1;
    private static final long NO_BACKOFF = 0L;

    @Bean("contextLlmGateway")
    public LlmGateway contextLlmGateway(
            @Qualifier("springAiLlmGateway") LlmGateway delegate,
            @Qualifier("contextCircuitBreaker") CircuitBreaker circuitBreaker,
            @Qualifier("contextBulkhead") Bulkhead bulkhead) {

        return new ResilientLlmGateway(delegate, circuitBreaker, bulkhead, SINGLE_ATTEMPT, NO_BACKOFF);
    }
}
