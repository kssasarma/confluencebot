package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Executes ingestion jobs asynchronously on the ingestionTaskExecutor thread pool.
 * Kept as a separate bean from IngestionJobService so that @Async proxying works
 * correctly (avoids the self-invocation bypass problem).
 *
 * <p>Both methods take a job id and read the row back on the pool thread, in a transaction
 * that has nothing to do with the caller's. Callers must therefore have committed the row
 * before they get here — see IngestionJobService.dispatchAfterCommit.
 */
@Component
public class IngestionJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobRunner.class);

    private final IngestionJobRepository jobRepo;
    private final IngestionService ingestionService;

    public IngestionJobRunner(IngestionJobRepository jobRepo, IngestionService ingestionService) {
        this.jobRepo = jobRepo;
        this.ingestionService = ingestionService;
    }

    @Async("ingestionTaskExecutor")
    public void runSpaceJob(UUID jobId, String spaceKey, boolean force) {
        IngestionJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> missingJob(jobId));
        job.markRunning();
        jobRepo.save(job);
        log.info("Background ingestion started — jobId={}, space={}, force={}", jobId, spaceKey, force);

        try {
            IngestionResult result = ingestionService.ingestSpace(spaceKey, force);
            job.markCompleted(result.pagesProcessed(), result.chunksStored(), result.pagesSkipped());
            jobRepo.save(job);
            log.info("Background ingestion completed — jobId={}, pages={}, chunks={}, skipped={}, {}ms",
                    jobId, result.pagesProcessed(), result.chunksStored(), result.pagesSkipped(), result.durationMs());
        } catch (Exception ex) {
            job.markFailed(ex.getMessage());
            jobRepo.save(job);
            log.error("Background ingestion failed — jobId={}, space={}: {}", jobId, spaceKey, ex.getMessage(), ex);
        }
    }

    @Async("ingestionTaskExecutor")
    public void runPageJob(UUID jobId, String pageId) {
        IngestionJobEntity job = jobRepo.findById(jobId).orElseThrow(() -> missingJob(jobId));
        job.markRunning();
        jobRepo.save(job);
        log.info("Background ingestion started — jobId={}, pageId={}", jobId, pageId);

        try {
            IngestionResult result = ingestionService.ingestPage(pageId);
            job.markCompleted(result.pagesProcessed(), result.chunksStored(), result.pagesSkipped());
            jobRepo.save(job);
            log.info("Background ingestion completed — jobId={}, pageId={}, chunks={}, {}ms",
                    jobId, pageId, result.chunksStored(), result.durationMs());
        } catch (Exception ex) {
            job.markFailed(ex.getMessage());
            jobRepo.save(job);
            log.error("Background ingestion failed — jobId={}, pageId={}: {}", jobId, pageId, ex.getMessage(), ex);
        }
    }

    /**
     * Named so the log says which job vanished. The bare Optional.orElseThrow() this replaces
     * reported only "No value present", which named neither the job nor the reason.
     */
    private static IllegalStateException missingJob(UUID jobId) {
        return new IllegalStateException(
                "Ingestion job " + jobId + " is not in the database — it was dispatched before its row was committed");
    }
}
