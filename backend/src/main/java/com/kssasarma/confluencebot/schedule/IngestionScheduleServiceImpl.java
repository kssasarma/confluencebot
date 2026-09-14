package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import com.kssasarma.confluencebot.repository.IngestionScheduleRepository;
import com.kssasarma.confluencebot.schedule.command.CreateScheduleCommand;
import com.kssasarma.confluencebot.schedule.command.UpdateScheduleCommand;
import com.kssasarma.confluencebot.schedule.strategy.CronScheduleStrategy;
import com.kssasarma.confluencebot.schedule.strategy.FixedIntervalScheduleStrategy;
import com.kssasarma.confluencebot.schedule.strategy.ScheduleConfig;
import com.kssasarma.confluencebot.schedule.strategy.ScheduleStrategy;
import com.kssasarma.confluencebot.schedule.strategy.ScheduleStrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
class IngestionScheduleServiceImpl implements IngestionScheduleService {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduleServiceImpl.class);

    private final IngestionScheduleRepository scheduleRepo;
    private final ScheduleStrategyRegistry strategyRegistry;
    private final IngestionJobService jobService;

    IngestionScheduleServiceImpl(IngestionScheduleRepository scheduleRepo,
                                  ScheduleStrategyRegistry strategyRegistry,
                                  IngestionJobService jobService) {
        this.scheduleRepo = scheduleRepo;
        this.strategyRegistry = strategyRegistry;
        this.jobService = jobService;
    }

    @Override
    @Transactional
    public IngestionScheduleEntity createOrReplace(String spaceKey, CreateScheduleCommand command) {
        String scheduleType = effectiveType(command.scheduleType());
        validateDefinition(scheduleType, command.intervalHours(), command.cronExpression());

        ScheduleStrategy strategy = strategyRegistry.resolve(scheduleType);
        ScheduleConfig config = new ScheduleConfig(command.intervalHours(), command.cronExpression());
        OffsetDateTime firstRun = strategy.calculateNextRun(OffsetDateTime.now(), config);

        IngestionScheduleEntity entity = scheduleRepo.findBySpaceKey(spaceKey)
                .map(existing -> {
                    existing.applyUpdate(command.intervalHours(), command.enabled(),
                            false, scheduleType, command.cronExpression(), command.requestedBy());
                    existing.recordRun(existing.getLastRunAt(), firstRun);
                    log.info("Ingestion schedule replaced for space '{}' by {} — type={}, enabled={}, nextRunAt={}",
                            spaceKey, command.requestedBy(), scheduleType,
                            command.enabled(), firstRun);
                    return existing;
                })
                .orElseGet(() -> {
                    IngestionScheduleEntity created = IngestionScheduleEntity.create(
                            spaceKey, command.intervalHours(), command.enabled(),
                            false, scheduleType, command.cronExpression(),
                            command.requestedBy(), firstRun);
                    log.info("Ingestion schedule created for space '{}' by {} — type={}, enabled={}, nextRunAt={}",
                            spaceKey, command.requestedBy(), scheduleType,
                            command.enabled(), firstRun);
                    return created;
                });

        return scheduleRepo.save(entity);
    }

    @Override
    @Transactional
    public IngestionScheduleEntity update(String spaceKey, UpdateScheduleCommand command) {
        IngestionScheduleEntity entity = requireSchedule(spaceKey);

        // Validate new definition before applying anything
        String newType = command.scheduleType() != null
                ? command.scheduleType() : entity.getScheduleType();
        int newInterval = command.intervalHours() != null
                ? command.intervalHours() : entity.getIntervalHours();
        String newCron = command.cronExpression() != null
                ? command.cronExpression() : entity.getCronExpression();
        validateDefinition(newType, newInterval, newCron);

        entity.applyUpdate(command.intervalHours(), command.enabled(), null,
                command.scheduleType(), command.cronExpression(), command.requestedBy());

        // Recompute nextRunAt when the schedule definition itself changed
        boolean definitionChanged = command.scheduleType() != null
                || command.cronExpression() != null
                || command.intervalHours() != null;
        if (definitionChanged) {
            ScheduleStrategy strategy = strategyRegistry.resolve(entity.getScheduleType());
            ScheduleConfig config = new ScheduleConfig(entity.getIntervalHours(), entity.getCronExpression());
            OffsetDateTime nextRun = strategy.calculateNextRun(OffsetDateTime.now(), config);
            entity.recordRun(entity.getLastRunAt(), nextRun);
        }

        log.info("Ingestion schedule updated for space '{}' by {}", spaceKey, command.requestedBy());
        return scheduleRepo.save(entity);
    }

    @Override
    @Transactional
    public void delete(String spaceKey) {
        IngestionScheduleEntity entity = requireSchedule(spaceKey);
        scheduleRepo.delete(entity);
        log.info("Ingestion schedule deleted for space '{}'", spaceKey);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IngestionScheduleEntity> findBySpaceKey(String spaceKey) {
        return scheduleRepo.findBySpaceKey(spaceKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IngestionScheduleEntity> findAll() {
        return scheduleRepo.findAll();
    }

    @Override
    @Transactional
    public IngestionJobEntity triggerNow(String spaceKey, String triggeredBy) {
        IngestionScheduleEntity schedule = requireSchedule(spaceKey);
        log.info("Manual trigger of scheduled ingestion for space '{}'", spaceKey);
        return jobService.submitSpaceJob(spaceKey, schedule.isForce(), triggeredBy);
    }

    /**
     * Atomically claims all due schedules — advances each schedule's next-run time before
     * returning, so a concurrent check cycle does not re-fire the same space.
     *
     * <p>The returned specs must be submitted <em>after</em> this transaction commits, because
     * {@link IngestionJobService#submitSpaceJob} dispatches work after-commit on its own
     * transaction; nested after-commit callbacks are not guaranteed to execute.
     */
    @Override
    @Transactional
    public List<ScheduledRunSpec> claimDueSchedules(OffsetDateTime now) {
        List<IngestionScheduleEntity> due = scheduleRepo.findByEnabledTrueAndNextRunAtLessThanEqual(now);

        return due.stream().map(schedule -> {
            ScheduleStrategy strategy = strategyRegistry.resolve(schedule.getScheduleType());
            ScheduleConfig config = new ScheduleConfig(
                    schedule.getIntervalHours(), schedule.getCronExpression());
            OffsetDateTime nextRun = strategy.calculateNextRun(now, config);
            schedule.recordRun(now, nextRun);
            scheduleRepo.save(schedule);
            return new ScheduledRunSpec(schedule.getSpaceKey(), schedule.isForce());
        }).toList();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String effectiveType(String scheduleType) {
        return scheduleType != null ? scheduleType : FixedIntervalScheduleStrategy.TYPE;
    }

    private static void validateDefinition(String scheduleType, int intervalHours,
                                           String cronExpression) {
        if (CronScheduleStrategy.TYPE.equals(scheduleType)) {
            if (cronExpression == null || cronExpression.isBlank()) {
                throw new IllegalArgumentException(
                        "cronExpression is required when scheduleType is CRON");
            }
            // Eagerly validate syntax so callers get a 400 rather than a scheduler failure later
            CronScheduleStrategy.parse(cronExpression);
        } else {
            if (intervalHours < 1 || intervalHours > 8760) {
                throw new IllegalArgumentException(
                        "intervalHours must be between 1 and 8760 for FIXED_INTERVAL schedules");
            }
        }
    }

    private IngestionScheduleEntity requireSchedule(String spaceKey) {
        return scheduleRepo.findBySpaceKey(spaceKey)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ingestion schedule configured for space: " + spaceKey));
    }
}
