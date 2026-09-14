package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import com.kssasarma.confluencebot.domain.IngestionJobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, UUID> {
    Page<IngestionJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
    boolean existsBySpaceKeyAndStatusIn(String spaceKey, Collection<IngestionJobStatus> statuses);
    List<IngestionJobEntity> findByStatusIn(Collection<IngestionJobStatus> statuses);
}
