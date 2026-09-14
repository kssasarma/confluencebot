package com.kssasarma.confluencebot.schedule.strategy;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Fires according to a standard cron expression (e.g. {@code "0 2 * * 1"} for every Monday
 * at 02:00).
 *
 * <p>Accepts both 5-field Linux/crontab syntax ({@code minute hour dom month dow}) and Spring's
 * 6-field syntax ({@code second minute hour dom month dow}).  A 5-field expression has {@code 0}
 * prepended for the seconds field before parsing.
 */
@Component
public class CronScheduleStrategy implements ScheduleStrategy {

    public static final String TYPE = "CRON";

    @Override
    public OffsetDateTime calculateNextRun(OffsetDateTime from, ScheduleConfig config) {
        String expr = config.cronExpression();
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("cronExpression is required for CRON schedule type");
        }
        CronExpression cron = parse(expr);
        ZonedDateTime fromZoned = from.toZonedDateTime();
        ZonedDateTime next = cron.next(fromZoned);
        if (next == null) {
            throw new IllegalStateException(
                    "Cron expression produces no future occurrence: " + expr);
        }
        return next.toOffsetDateTime();
    }

    @Override
    public String strategyType() {
        return TYPE;
    }

    /**
     * Parses and validates a cron expression.  Normalises 5-field expressions to Spring's
     * 6-field format.
     *
     * @throws IllegalArgumentException if the expression is syntactically invalid
     */
    public static CronExpression parse(String expr) {
        String trimmed = expr.trim();
        String springExpr = trimmed.split("\\s+").length == 5 ? "0 " + trimmed : trimmed;
        try {
            return CronExpression.parse(springExpr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid cron expression '" + expr + "': " + e.getMessage(), e);
        }
    }
}
