package com.kssasarma.confluencebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "ingestionTaskExecutor")
    public Executor ingestionTaskExecutor(
            @Value("${ingestion.async.core-pool-size:2}") int corePoolSize,
            @Value("${ingestion.async.max-pool-size:4}") int maxPoolSize,
            @Value("${ingestion.async.queue-capacity:10}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ingestion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);
        executor.initialize();
        return executor;
    }

    /**
     * Carries streamed answers off the servlet threads.
     *
     * The queue is deliberately shallow and the pool refuses work it cannot start: a caller
     * waiting behind a long queue for an answer that streams token by token would sit staring at
     * a blank bubble, so it is better to say "busy, try again" straight away. The bulkhead around
     * the model keeps the real concurrency limit in one place.
     */
    @Bean(name = "chatStreamExecutor")
    public Executor chatStreamExecutor(
            @Value("${chat.stream.core-pool-size:4}") int corePoolSize,
            @Value("${chat.stream.max-pool-size:16}") int maxPoolSize,
            @Value("${chat.stream.queue-capacity:16}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("chat-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
