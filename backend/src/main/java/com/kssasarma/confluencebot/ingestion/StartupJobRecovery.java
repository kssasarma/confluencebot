package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobStatus;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class StartupJobRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupJobRecovery.class);

    private final IngestionJobRepository jobRepo;

    public StartupJobRecovery(IngestionJobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    /**
     * Marks PENDING and RUNNING jobs as FAILED on startup.
     *
     * RUNNING jobs were interrupted mid-execution by a previous shutdown; PENDING jobs were
     * queued but never dispatched. Neither will make progress after a restart — the in-memory
     * executor that would have driven them is gone. Leaving them in their old status would
     * prevent new submissions for the same space (the duplicate-check blocks PENDING/RUNNING),
     * so they are failed here with an explanatory message and can be retriggered by the user.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failStaleJobs() {
        var stale = jobRepo.findByStatusIn(List.of(IngestionJobStatus.PENDING, IngestionJobStatus.RUNNING));
        if (stale.isEmpty()) {
            return;
        }
        log.warn("Failing {} stale job(s) left over from previous shutdown", stale.size());
        stale.forEach(job -> job.markFailed("Application restarted while job was " + job.getStatus()));
        jobRepo.saveAll(stale);
    }
}
