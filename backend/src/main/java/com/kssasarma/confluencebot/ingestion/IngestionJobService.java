package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
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

    @Transactional
    public Optional<IngestionJobEntity> retriggerJob(UUID jobId) {
        return jobRepo.findById(jobId).flatMap(job -> {
            if (!job.getStatus().equals(com.kssasarma.confluencebot.domain.IngestionJobStatus.FAILED)) {
                return Optional.empty();
            }

            IngestionJobEntity newJob = null;
            if (job.getJobType().equals(com.kssasarma.confluencebot.domain.IngestionJobType.SPACE)) {
                newJob = IngestionJobEntity.forSpace(job.getSpaceKey(), job.isForce());
            } else if (job.getJobType().equals(com.kssasarma.confluencebot.domain.IngestionJobType.PAGE)) {
                newJob = IngestionJobEntity.forPage(job.getPageId());
            }

            if (newJob != null) {
                jobRepo.save(newJob);
                UUID newJobId = newJob.getId();
                dispatchAfterCommit(() -> {
                    if (job.getJobType().equals(com.kssasarma.confluencebot.domain.IngestionJobType.SPACE)) {
                        runner.runSpaceJob(newJobId, job.getSpaceKey(), job.isForce());
                    } else {
                        runner.runPageJob(newJobId, job.getPageId());
                    }
                });
                return Optional.of(newJob);
            }
            return Optional.empty();
        });
    }
}
