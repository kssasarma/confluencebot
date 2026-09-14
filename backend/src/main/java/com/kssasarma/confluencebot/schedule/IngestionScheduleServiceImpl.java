package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import com.kssasarma.confluencebot.exception.ResourceNotFoundException;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import com.kssasarma.confluencebot.repository.IngestionScheduleRepository;
import com.kssasarma.confluencebot.schedule.command.CreateScheduleCommand;
import com.kssasarma.confluencebot.schedule.command.UpdateScheduleCommand;
import com.kssasarma.confluencebot.schedule.strategy.ScheduleStrategy;
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
    private final ScheduleStrategy scheduleStrategy;
    private final IngestionJobService jobService;

    IngestionScheduleServiceImpl(IngestionScheduleRepository scheduleRepo,
                                  ScheduleStrategy scheduleStrategy,
                                  IngestionJobService jobService) {
        this.scheduleRepo = scheduleRepo;
        this.scheduleStrategy = scheduleStrategy;
        this.jobService = jobService;
    }

    @Override
    @Transactional
    public IngestionScheduleEntity createOrReplace(String spaceKey, CreateScheduleCommand command) {
        OffsetDateTime firstRun = scheduleStrategy.calculateNextRun(
                OffsetDateTime.now(), command.intervalHours());

        IngestionScheduleEntity entity = scheduleRepo.findBySpaceKey(spaceKey)
                .map(existing -> {
                    existing.applyUpdate(command.intervalHours(), command.enabled(),
                            false, command.requestedBy());
                    existing.recordRun(existing.getLastRunAt(), firstRun);
                    log.info("Ingestion schedule replaced for space '{}' by {} — interval={}h, enabled={}, nextRunAt={}",
                            spaceKey, command.requestedBy(), command.intervalHours(),
                            command.enabled(), firstRun);
                    return existing;
                })
                .orElseGet(() -> {
                    IngestionScheduleEntity created = IngestionScheduleEntity.create(
                            spaceKey, command.intervalHours(), command.enabled(),
                            false, command.requestedBy(), firstRun);
                    log.info("Ingestion schedule created for space '{}' by {} — interval={}h, enabled={}, nextRunAt={}",
                            spaceKey, command.requestedBy(), command.intervalHours(),
                            command.enabled(), firstRun);
                    return created;
                });

        return scheduleRepo.save(entity);
    }

    @Override
    @Transactional
    public IngestionScheduleEntity update(String spaceKey, UpdateScheduleCommand command) {
        IngestionScheduleEntity entity = requireSchedule(spaceKey);
        entity.applyUpdate(command.intervalHours(), command.enabled(), null,
                command.requestedBy());
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
            OffsetDateTime nextRun = scheduleStrategy.calculateNextRun(now, schedule.getIntervalHours());
            schedule.recordRun(now, nextRun);
            scheduleRepo.save(schedule);
            return new ScheduledRunSpec(schedule.getSpaceKey(), schedule.isForce());
        }).toList();
    }

    private IngestionScheduleEntity requireSchedule(String spaceKey) {
        return scheduleRepo.findBySpaceKey(spaceKey)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ingestion schedule configured for space: " + spaceKey));
    }
}
