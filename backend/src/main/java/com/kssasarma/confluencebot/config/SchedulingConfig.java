package com.kssasarma.confluencebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    /**
     * Dedicated scheduler pool for {@code @Scheduled} tasks.
     *
     * <p>Sized separately from the ingestion worker pool: the scheduler itself does lightweight
     * work (a DB read + a few saves), the heavy lifting runs on the ingestion pool it hands off
     * to. A pool of 1 is sufficient for the single auto-ingestion check task; raise it when new
     * {@code @Scheduled} tasks are added.
     */
    @Bean
    public TaskScheduler taskScheduler(
            @Value("${ingestion.schedule.pool-size:1}") int poolSize) {

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("ingestion-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
