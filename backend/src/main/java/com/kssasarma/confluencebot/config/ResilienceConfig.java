package com.kssasarma.confluencebot.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Circuit breaker and bulkhead protection for all LLM calls.
 *
 * Circuit breaker: opens after 50% failure rate over a 10-call sliding window;
 * stays open for 30s before allowing a probe call.
 *
 * Bulkhead: limits concurrent LLM calls to 5; waits up to 5s before rejecting.
 *
 * The re-rank pass and the follow-up rewrite each get their own separate instances so a
 * misconfigured auxiliary model can't trip the main answer-generation circuit breaker, and — just
 * as importantly — can't consume the permits answers depend on. Both are improvements to an
 * answer; neither may ever be the reason there isn't one.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .recordExceptions(Exception.class)
            .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(5)
            .maxWaitDuration(Duration.ofSeconds(5))
            .build();
        return BulkheadRegistry.of(config);
    }

    @Bean("llmCircuitBreaker")
    public CircuitBreaker llmCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("llm");
    }

    @Bean("llmBulkhead")
    public Bulkhead llmBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("llm");
    }

    @Bean("rerankCircuitBreaker")
    public CircuitBreaker rerankCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("llm-rerank");
    }

    @Bean("rerankBulkhead")
    public Bulkhead rerankBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("llm-rerank");
    }

    @Bean("contextCircuitBreaker")
    public CircuitBreaker contextCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("llm-context");
    }

    /**
     * Permits for condensing a follow-up into a standalone query.
     *
     * <p>Does not wait, unlike the others. The caller is holding a user's question open while this
     * runs and abandons it after a short deadline anyway, so a permit that arrives four seconds
     * late arrives after the decision it was for. Refusing at once frees the thread and retrieval
     * proceeds with the question as asked.
     */
    @Bean("contextBulkhead")
    public Bulkhead contextBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("llm-context", BulkheadConfig.custom()
            .maxConcurrentCalls(3)
            .maxWaitDuration(Duration.ZERO)
            .build());
    }
}
