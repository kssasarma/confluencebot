package com.kssasarma.confluencebot.schedule.strategy;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Fires exactly {@code intervalHours} after the reference timestamp, every time.
 *
 * <p>This is the default — and currently only — strategy. New strategies (business-hours-only,
 * cron-expression, event-driven) implement {@link ScheduleStrategy} and are injected by name
 * wherever needed, leaving this class unchanged.
 */
@Component
public class FixedIntervalScheduleStrategy implements ScheduleStrategy {

    public static final String TYPE = "FIXED_INTERVAL";

    @Override
    public OffsetDateTime calculateNextRun(OffsetDateTime from, int intervalHours) {
        return from.plusHours(intervalHours);
    }

    @Override
    public String strategyType() {
        return TYPE;
    }
}
