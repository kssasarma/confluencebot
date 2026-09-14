package com.kssasarma.confluencebot.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ingestion_schedules")
public class IngestionScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "space_key", length = 50, nullable = false, unique = true)
    private String spaceKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "interval_hours", nullable = false)
    private int intervalHours;

    @Column(name = "force", nullable = false)
    private boolean force;

    @Column(name = "last_run_at")
    private OffsetDateTime lastRunAt;

    @Column(name = "next_run_at", nullable = false)
    private OffsetDateTime nextRunAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 255, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_by", length = 255, nullable = false)
    private String updatedBy;

    protected IngestionScheduleEntity() {}

    public static IngestionScheduleEntity create(String spaceKey, int intervalHours,
                                                  boolean enabled, boolean force,
                                                  String createdBy, OffsetDateTime nextRunAt) {
        IngestionScheduleEntity e = new IngestionScheduleEntity();
        e.spaceKey = spaceKey;
        e.intervalHours = intervalHours;
        e.enabled = enabled;
        e.force = force;
        e.createdBy = createdBy;
        e.updatedBy = createdBy;
        e.createdAt = OffsetDateTime.now();
        e.updatedAt = e.createdAt;
        e.nextRunAt = nextRunAt;
        return e;
    }

    public void applyUpdate(Integer intervalHours, Boolean enabled, Boolean force, String updatedBy) {
        if (intervalHours != null) this.intervalHours = intervalHours;
        if (enabled != null)       this.enabled = enabled;
        if (force != null)         this.force = force;
        this.updatedBy = updatedBy;
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Advances the schedule after a successful (or skipped-but-counted) run so the scheduler's
     * next check does not immediately re-fire it.
     */
    public void recordRun(OffsetDateTime ranAt, OffsetDateTime nextRunAt) {
        this.lastRunAt = ranAt;
        this.nextRunAt = nextRunAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId()                  { return id; }
    public String getSpaceKey()          { return spaceKey; }
    public boolean isEnabled()           { return enabled; }
    public int getIntervalHours()        { return intervalHours; }
    public boolean isForce()             { return force; }
    public OffsetDateTime getLastRunAt() { return lastRunAt; }
    public OffsetDateTime getNextRunAt() { return nextRunAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getCreatedBy()         { return createdBy; }
    public String getUpdatedBy()         { return updatedBy; }
}
