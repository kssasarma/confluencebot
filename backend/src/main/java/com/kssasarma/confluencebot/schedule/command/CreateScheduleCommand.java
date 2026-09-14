package com.kssasarma.confluencebot.schedule.command;

/**
 * Carries the intent to create or fully replace a space's ingestion schedule.
 * All fields are required — partial state is expressed via {@link UpdateScheduleCommand}.
 */
public record CreateScheduleCommand(
        int intervalHours,
        boolean enabled,
        String requestedBy
) {}
