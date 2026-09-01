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
 * The re-rank pass gets its own separate instance so a misconfigured re-rank model
 * can't trip the main answer-generation circuit breaker.
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
}
