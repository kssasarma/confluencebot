package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.exception.LlmUnavailableException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.TimeUnit;

/**
 * Decorates a {@link LlmGateway} with the bulkhead, circuit breaker and retry policy that every
 * model call must respect, so no caller can forget to apply them.
 *
 * Streaming cannot be wrapped in a synchronous decorator, so the permits are taken before the
 * subscription and released on the terminal signal; a stream that fails before producing anything
 * falls back to a single blocking call, which keeps the UI working against models or gateways
 * that do not implement server-sent completions.
 */
@Primary
@Component
public class ResilientLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmGateway.class);

    private final LlmGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final int maxAttempts;
    private final long initialBackoffMs;

    public ResilientLlmGateway(@Qualifier("springAiLlmGateway") LlmGateway delegate,
                               @Qualifier("llmCircuitBreaker") CircuitBreaker circuitBreaker,
                               @Qualifier("llmBulkhead") Bulkhead bulkhead,
                               @Value("${chat.llm.max-attempts:3}") int maxAttempts,
                               @Value("${chat.llm.initial-backoff-ms:500}") long initialBackoffMs) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
    }

    @Override
    public String complete(LlmPrompt prompt) {
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return bulkhead.executeSupplier(
                        () -> circuitBreaker.executeSupplier(() -> delegate.complete(prompt)));
            } catch (CallNotPermittedException e) {
                throw new LlmUnavailableException("LLM circuit breaker is open", e);
            } catch (BulkheadFullException e) {
                throw new LlmUnavailableException("LLM bulkhead is saturated", e);
            } catch (Exception e) {
                lastFailure = e;
                log.warn("LLM call attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) backoff(attempt);
            }
        }

        throw new LlmUnavailableException("LLM call failed after " + maxAttempts + " attempts", lastFailure);
    }

    @Override
    public Flux<String> stream(LlmPrompt prompt) {
        return Flux.defer(() -> {
            if (!bulkhead.tryAcquirePermission()) {
                return Flux.error(new LlmUnavailableException("LLM bulkhead is saturated"));
            }
            try {
                circuitBreaker.acquirePermission();
            } catch (CallNotPermittedException e) {
                bulkhead.onComplete();
                return Flux.error(new LlmUnavailableException("LLM circuit breaker is open", e));
            }

            long startNanos = System.nanoTime();
            StreamState state = new StreamState();

            return delegate.stream(prompt)
                    .doOnNext(token -> state.received = true)
                    .onErrorResume(error -> recoverFromStreamFailure(prompt, state, error))
                    .doOnError(error -> circuitBreaker.onError(
                            System.nanoTime() - startNanos, TimeUnit.NANOSECONDS, error))
                    .doOnComplete(() -> circuitBreaker.onSuccess(
                            System.nanoTime() - startNanos, TimeUnit.NANOSECONDS))
                    .doFinally(signal -> bulkhead.onComplete());
        });
    }

    /**
     * A stream that died before emitting anything is indistinguishable from a gateway that does
     * not support streaming at all, so the answer is fetched in one blocking call instead. Once
     * tokens have been delivered the failure is real and is propagated.
     */
    private Flux<String> recoverFromStreamFailure(LlmPrompt prompt, StreamState state, Throwable error) {
        if (state.received) {
            return Flux.error(new LlmUnavailableException("LLM stream failed mid-answer", error));
        }
        log.warn("LLM streaming unavailable ({}), falling back to a single completion call",
                error.getMessage());
        return Mono.fromCallable(() -> delegate.complete(prompt))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> new LlmUnavailableException("LLM call failed", e))
                .flux();
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(initialBackoffMs * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Interrupted while waiting to retry the LLM call", e);
        }
    }

    /** Tracks whether the current stream ever produced a token. */
    private static final class StreamState {
        private volatile boolean received;
    }
}
