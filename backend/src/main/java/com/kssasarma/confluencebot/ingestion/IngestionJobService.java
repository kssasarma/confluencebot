package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionJobStatus;
import com.kssasarma.confluencebot.domain.IngestionJobType;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestionJobService {

    private final IngestionJobRepository jobRepo;
    private final IngestionJobRunner runner;

    public IngestionJobService(IngestionJobRepository jobRepo, IngestionJobRunner runner) {
        this.jobRepo = jobRepo;
        this.runner = runner;
    }

    @Transactional
    public IngestionJobEntity submitSpaceJob(String spaceKey, boolean force) {
        IngestionJobEntity job = IngestionJobEntity.forSpace(spaceKey, force);
        jobRepo.save(job);
        UUID jobId = job.getId();
        dispatchAfterCommit(() -> runner.runSpaceJob(jobId, spaceKey, force));
        return job;
    }

    @Transactional
    public IngestionJobEntity submitPageJob(String pageId) {
        IngestionJobEntity job = IngestionJobEntity.forPage(pageId);
        jobRepo.save(job);
        UUID jobId = job.getId();
        dispatchAfterCommit(() -> runner.runPageJob(jobId, pageId));
        return job;
    }

    /**
     * Hands the job to the ingestion pool, but not before the row it describes is durable.
     *
     * <p>The runner reads the job back by id, on a pool thread, in a transaction of its own.
     * Dispatching inline starts that read while this method is still inside its transaction: the
     * INSERT is an unflushed entity in the persistence context, no other connection can see it,
     * and the job dies on arrival with {@code NoSuchElementException: No value present} — before
     * a single page has been fetched. Deferring the dispatch to after-commit means the row is
     * visible to every connection by the time the runner asks for it.
     *
     * <p>The id is captured by the caller rather than read from the entity inside the callback,
     * because after commit the entity is detached and nothing guarantees the caller still holds it.
     *
     * <p>When no transaction is in progress there is nothing to wait for — {@code save()} has
     * committed under its own repository-level transaction — so the dispatch happens immediately.
     */
    private void dispatchAfterCommit(Runnable dispatch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch.run();
            }
        });
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobEntity> findById(UUID jobId) {
        return jobRepo.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<IngestionJobEntity> findAll() {
        return jobRepo.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Resubmits a failed job as a new one, leaving the failure in the history.
     *
     * <p>A retrigger is a fresh row rather than a reset of the old one: the failed attempt is the
     * only record of what went wrong, and overwriting its status and error message to re-run it
     * would erase the reason anyone is retriggering in the first place.
     *
     * <p>Empty when the job does not exist or is not {@code FAILED} — a job still pending or
     * running would otherwise be duplicated into a second concurrent run over the same space, and
     * a completed one has nothing to retry. The caller distinguishes the two cases.
     */
    @Transactional
    public Optional<IngestionJobEntity> retriggerJob(UUID jobId) {
        return jobRepo.findById(jobId)
                .filter(failed -> failed.getStatus() == IngestionJobStatus.FAILED)
                .map(failed -> {
                    // Read off the entity here, not in the callback: after commit it is detached,
                    // for the same reason the id is captured eagerly in dispatchAfterCommit.
                    IngestionJobType type = failed.getJobType();
                    String spaceKey = failed.getSpaceKey();
                    String pageId = failed.getPageId();
                    boolean force = failed.isForce();

                    IngestionJobEntity retry = type == IngestionJobType.SPACE
                            ? IngestionJobEntity.forSpace(spaceKey, force)
                            : IngestionJobEntity.forPage(pageId);
                    jobRepo.save(retry);

                    UUID retryId = retry.getId();
                    dispatchAfterCommit(() -> {
                        if (type == IngestionJobType.SPACE) {
                            runner.runSpaceJob(retryId, spaceKey, force);
                        } else {
                            runner.runPageJob(retryId, pageId);
                        }
                    });
                    return retry;
                });
    }
}
