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
     * Calculates the next fire time given the point in time of the most recent run and the
     * per-schedule interval setting.
     *
     * @param from          the reference timestamp (typically the current time at run)
     * @param intervalHours the interval stored on the schedule
     * @return the next time the schedule should fire; never null, never before {@code from}
     */
    OffsetDateTime calculateNextRun(OffsetDateTime from, int intervalHours);

    /** Stable identifier surfaced in logs and (future) admin API responses. */
    String strategyType();
}
