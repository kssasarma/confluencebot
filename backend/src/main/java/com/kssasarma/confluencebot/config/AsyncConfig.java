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
}
