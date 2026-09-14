package com.kssasarma.confluencebot.schedule.strategy;

/**
 * Carries all schedule-definition data a {@link ScheduleStrategy} might need to compute the
 * next fire time.  Fixed-interval strategies use {@code intervalHours}; cron strategies use
 * {@code cronExpression} and ignore {@code intervalHours}.
 */
public record ScheduleConfig(int intervalHours, String cronExpression) {}
