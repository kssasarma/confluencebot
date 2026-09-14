package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.IngestionScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestionScheduleRepository extends JpaRepository<IngestionScheduleEntity, UUID> {

    Optional<IngestionScheduleEntity> findBySpaceKey(String spaceKey);

    boolean existsBySpaceKey(String spaceKey);

    /**
     * Returns all enabled schedules whose next run is at or before {@code now}.
     * Backed by the partial index on (next_run_at) WHERE enabled = true.
     */
    List<IngestionScheduleEntity> findByEnabledTrueAndNextRunAtLessThanEqual(OffsetDateTime now);
}
