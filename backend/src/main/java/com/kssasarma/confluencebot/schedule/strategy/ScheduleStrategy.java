package com.kssasarma.confluencebot.schedule.strategy;

import java.time.OffsetDateTime;

/**
 * Calculates when a scheduled ingestion job should fire next.
 *
 * <p>Implementations are free to define whatever shape of recurrence they need — fixed interval,
 * business-hours-only, cron expression, etc. — without touching any scheduling infrastructure.
 * The scheduler is closed to that detail and open only to this abstraction (OCP).
 */
public interface ScheduleStrategy {

    /**
     * Calculates the next fire time given the reference timestamp and the full schedule config.
     *
     * @param from   the reference timestamp (typically the current time at run)
     * @param config all schedule-definition data for this schedule
     * @return the next time the schedule should fire; never null, never before {@code from}
     */
    OffsetDateTime calculateNextRun(OffsetDateTime from, ScheduleConfig config);

    /** Stable identifier surfaced in logs and admin API responses. */
    String strategyType();
}
