package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.schedule.strategy.FixedIntervalScheduleStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FixedIntervalScheduleStrategyTest {

    private final FixedIntervalScheduleStrategy strategy = new FixedIntervalScheduleStrategy();

    @Test
    void strategyType_returnFixedInterval() {
        assertThat(strategy.strategyType()).isEqualTo(FixedIntervalScheduleStrategy.TYPE);
    }

    @Test
    void calculateNextRun_24hInterval_advancesByExactly24Hours() {
        OffsetDateTime from = OffsetDateTime.parse("2026-09-14T10:00:00+05:30");

        OffsetDateTime next = strategy.calculateNextRun(from, 24);

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-09-15T10:00:00+05:30"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 12, 24, 48, 168, 8760})
    void calculateNextRun_isAlwaysAfterFrom(int intervalHours) {
        OffsetDateTime from = OffsetDateTime.now();

        OffsetDateTime next = strategy.calculateNextRun(from, intervalHours);

        assertThat(next).isAfter(from);
        assertThat(next).isEqualTo(from.plusHours(intervalHours));
    }
}
