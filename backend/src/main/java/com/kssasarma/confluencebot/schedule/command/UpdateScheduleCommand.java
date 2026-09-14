package com.kssasarma.confluencebot.schedule.command;

/**
 * Carries the intent to partially update a space's ingestion schedule.
 * A null field means "leave this attribute unchanged".
 */
public record UpdateScheduleCommand(
        Integer intervalHours,
        Boolean enabled,
        String requestedBy
) {}
