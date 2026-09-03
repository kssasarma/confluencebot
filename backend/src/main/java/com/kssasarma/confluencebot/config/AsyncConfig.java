package com.kssasarma.confluencebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;

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

    /**
     * Summarises conversation titles out of band.
     *
     * Separate from the streaming pool on purpose: a title is a nicety, and it must never be able
     * to consume the capacity that answers depend on. The queue is bounded and the pool discards
     * what it cannot take, because a title that arrives late is worth nothing and a caller waiting
     * on one is worth less than an answer.
     */
    @Bean(name = "chatTitleExecutor")
    public Executor chatTitleExecutor(
            @Value("${chat.title.core-pool-size:1}") int corePoolSize,
            @Value("${chat.title.max-pool-size:4}") int maxPoolSize,
            @Value("${chat.title.queue-capacity:32}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("chat-title-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * Drives keep-alive frames and the bounded post-answer linger on open answer streams.
     *
     * These are timer callbacks measured in milliseconds of work, so a small pool serves a large
     * number of concurrent streams. Threads are daemons: a pending keep-alive must never be the
     * reason a shutdown hangs.
     */
    @Bean(name = "sseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService sseHeartbeatScheduler(
            @Value("${chat.stream.scheduler-pool-size:2}") int poolSize) {

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("sse-keepalive-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler.getScheduledExecutor();
    }
}
