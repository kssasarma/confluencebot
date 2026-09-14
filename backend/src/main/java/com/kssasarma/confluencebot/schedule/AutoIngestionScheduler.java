package com.kssasarma.confluencebot.schedule;

import com.kssasarma.confluencebot.exception.DuplicateIngestionJobException;
import com.kssasarma.confluencebot.ingestion.IngestionJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Periodically checks which ingestion schedules are due and fires them.
 *
 * <p><strong>Two-phase design</strong> — claim then submit — is intentional:
 * <ol>
 *   <li>{@code scheduleService.claimDueSchedules} advances every due schedule's
 *       {@code nextRunAt} inside a single transaction. A parallel scheduler run (or the next
 *       check cycle) will no longer see those rows as due, so no space is ingested twice.</li>
 *   <li>Job submission happens <em>after</em> that transaction commits. The
 *       {@link IngestionJobService} already defers its async dispatch until after its own
 *       transaction commits; nesting that inside the claim transaction would mean the
 *       after-commit callback fires on the inner commit, which may precede the outer one.</li>
 * </ol>
 *
 * <p>A space already being ingested (PENDING or RUNNING) produces a
 * {@link DuplicateIngestionJobException} — caught and logged as a skip, not an error, because
 * the schedule was already advanced and will fire again at the next interval.
 */
@Component
public class AutoIngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoIngestionScheduler.class);

    private final IngestionScheduleService scheduleService;
    private final IngestionJobService jobService;

    public AutoIngestionScheduler(IngestionScheduleService scheduleService,
                                   IngestionJobService jobService) {
        this.scheduleService = scheduleService;
        this.jobService = jobService;
    }

    @Scheduled(
            fixedDelayString   = "${ingestion.schedule.check-interval-ms:60000}",
            initialDelayString = "${ingestion.schedule.initial-delay-ms:30000}")
    public void checkAndTriggerDueSchedules() {
        OffsetDateTime now = OffsetDateTime.now();

        // Phase 1: atomically advance all due schedules and collect their specs
        List<ScheduledRunSpec> specs = scheduleService.claimDueSchedules(now);

        if (specs.isEmpty()) {
            return;
        }

        log.info("Auto-ingestion: {} space(s) due for re-ingestion", specs.size());

        // Phase 2: submit a job for each claimed spec (outside the claiming transaction)
        for (ScheduledRunSpec spec : specs) {
            submitJob(spec);
        }
    }

    private void submitJob(ScheduledRunSpec spec) {
        try {
            jobService.submitSpaceJob(spec.spaceKey(), spec.force(), "System (Scheduled)");
            log.info("Auto-ingestion job submitted for space '{}' (force={})",
                    spec.spaceKey(), spec.force());
        } catch (DuplicateIngestionJobException ex) {
            // A concurrent manual ingest is already in flight for this space.
            // The schedule was already advanced in phase 1 so the next check cycle
            // will pick it up at its new nextRunAt — no corrective action needed.
            log.info("Auto-ingestion for space '{}' skipped: job already active", spec.spaceKey());
        } catch (Exception ex) {
            log.error("Auto-ingestion failed to submit job for space '{}': {}",
                    spec.spaceKey(), ex.getMessage(), ex);
        }
    }
}
