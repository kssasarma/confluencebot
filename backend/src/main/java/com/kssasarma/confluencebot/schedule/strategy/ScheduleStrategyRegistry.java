package com.kssasarma.confluencebot.schedule.strategy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves a {@link ScheduleStrategy} by its type identifier.
 *
 * <p>Spring injects every {@code ScheduleStrategy} bean into the constructor, so adding a new
 * strategy is just a matter of annotating it with {@code @Component} — no wiring change needed
 * here.
 */
@Component
public class ScheduleStrategyRegistry {

    private final Map<String, ScheduleStrategy> strategies;

    public ScheduleStrategyRegistry(List<ScheduleStrategy> strategies) {
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(ScheduleStrategy::strategyType, s -> s));
    }

    /**
     * Returns the strategy for the given type, defaulting to {@code FIXED_INTERVAL} when
     * {@code type} is null.
     *
     * @throws IllegalArgumentException if {@code type} is non-null but not registered
     */
    public ScheduleStrategy resolve(String type) {
        String key = (type != null) ? type : FixedIntervalScheduleStrategy.TYPE;
        ScheduleStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown schedule type: " + key
                    + ". Supported types: " + strategies.keySet());
        }
        return strategy;
    }
}
