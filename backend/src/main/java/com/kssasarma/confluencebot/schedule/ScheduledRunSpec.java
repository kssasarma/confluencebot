package com.kssasarma.confluencebot.schedule;

/**
 * An immutable specification of one auto-ingestion run to execute.
 * Returned by the service when it atomically claims due schedules so the scheduler
 * can submit jobs outside the claiming transaction.
 */
public record ScheduledRunSpec(String spaceKey, boolean force) {}
