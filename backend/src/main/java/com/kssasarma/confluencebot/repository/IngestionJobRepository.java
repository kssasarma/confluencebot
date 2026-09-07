package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, UUID> {
    Page<IngestionJobEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
