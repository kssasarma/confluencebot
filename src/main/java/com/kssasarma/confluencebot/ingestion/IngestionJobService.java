package com.kssasarma.confluencebot.ingestion;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.repository.IngestionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        runner.runSpaceJob(job.getId(), spaceKey, force);
        return job;
    }

    @Transactional
    public IngestionJobEntity submitPageJob(String pageId) {
        IngestionJobEntity job = IngestionJobEntity.forPage(pageId);
        jobRepo.save(job);
        runner.runPageJob(job.getId(), pageId);
        return job;
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobEntity> findById(UUID jobId) {
        return jobRepo.findById(jobId);
    }

    @Transactional(readOnly = true)
    public List<IngestionJobEntity> findAll() {
        return jobRepo.findAllByOrderByCreatedAtDesc();
    }
}
