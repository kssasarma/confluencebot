package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import com.kssasarma.confluencebot.schedule.command.CreateScheduleCommand;
import com.kssasarma.confluencebot.schedule.command.UpdateScheduleCommand;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface IngestionScheduleService {

    /**
     * Creates a new schedule for {@code spaceKey}, or replaces an existing one entirely.
     * The first auto-run fires {@code intervalHours} after this call.
     */
    IngestionScheduleEntity createOrReplace(String spaceKey, CreateScheduleCommand command);

    /**
     * Applies a partial update to the schedule for {@code spaceKey}.
     *
     * @throws com.kssasarma.confluencebot.exception.ResourceNotFoundException if no schedule exists
     */
    IngestionScheduleEntity update(String spaceKey, UpdateScheduleCommand command);

    /**
     * Removes the schedule for {@code spaceKey}.
     *
     * @throws com.kssasarma.confluencebot.exception.ResourceNotFoundException if no schedule exists
     */
    void delete(String spaceKey);

    Optional<IngestionScheduleEntity> findBySpaceKey(String spaceKey);

    List<IngestionScheduleEntity> findAll();

    /**
     * Submits an immediate ingestion job for {@code spaceKey}, using the force flag from the
     * stored schedule.  Returns the newly submitted job entity.
     *
     * @throws com.kssasarma.confluencebot.exception.ResourceNotFoundException if no schedule exists
     * @throws com.kssasarma.confluencebot.exception.DuplicateIngestionJobException if already running
     */
    IngestionJobEntity triggerNow(String spaceKey, String triggeredBy);

    /**
     * Atomically claims all enabled schedules that are due at {@code now}: advances each
     * schedule's {@code lastRunAt} and {@code nextRunAt} in a single transaction so a concurrent
     * scheduler instance (or the next check-cycle) cannot double-fire the same space.
     *
     * <p>Callers MUST submit the returned specs <em>outside</em> this transaction — the
     * {@link com.kssasarma.confluencebot.ingestion.IngestionJobService} dispatches async work
     * after-commit, which requires the caller's transaction to have closed first.
     *
     * @return an immutable list of run specs ready for job submission
     */
    List<ScheduledRunSpec> claimDueSchedules(OffsetDateTime now);
}
