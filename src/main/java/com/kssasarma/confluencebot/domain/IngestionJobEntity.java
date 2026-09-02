package com.kssasarma.confluencebot.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ingestion_jobs")
public class IngestionJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", length = 10, nullable = false)
    private IngestionJobType jobType;

    @Column(name = "space_key", length = 50)
    private String spaceKey;

    @Column(name = "page_id", length = 50)
    private String pageId;

    @Column(name = "force", nullable = false)
    private boolean force;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private IngestionJobStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "pages_processed")
    private Integer pagesProcessed;

    @Column(name = "chunks_stored")
    private Integer chunksStored;

    @Column(name = "pages_skipped")
    private Integer pagesSkipped;

    @Column(name = "error_message")
    private String errorMessage;

    protected IngestionJobEntity() {}

    public static IngestionJobEntity forSpace(String spaceKey, boolean force) {
        IngestionJobEntity e = new IngestionJobEntity();
        e.jobType = IngestionJobType.SPACE;
        e.spaceKey = spaceKey;
        e.force = force;
        e.status = IngestionJobStatus.PENDING;
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    public static IngestionJobEntity forPage(String pageId) {
        IngestionJobEntity e = new IngestionJobEntity();
        e.jobType = IngestionJobType.PAGE;
        e.pageId = pageId;
        e.force = false;
        e.status = IngestionJobStatus.PENDING;
        e.createdAt = OffsetDateTime.now();
        return e;
    }

    public UUID getId() { return id; }
    public IngestionJobType getJobType() { return jobType; }
    public String getSpaceKey() { return spaceKey; }
    public String getPageId() { return pageId; }
    public boolean isForce() { return force; }
    public IngestionJobStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public Integer getPagesProcessed() { return pagesProcessed; }
    public Integer getChunksStored() { return chunksStored; }
    public Integer getPagesSkipped() { return pagesSkipped; }
    public String getErrorMessage() { return errorMessage; }

    public void markRunning() {
        this.status = IngestionJobStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    public void markCompleted(int pagesProcessed, int chunksStored, int pagesSkipped) {
        this.status = IngestionJobStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now();
        this.pagesProcessed = pagesProcessed;
        this.chunksStored = chunksStored;
        this.pagesSkipped = pagesSkipped;
    }

    public void markFailed(String errorMessage) {
        this.status = IngestionJobStatus.FAILED;
        this.completedAt = OffsetDateTime.now();
        this.errorMessage = errorMessage;
    }
}
