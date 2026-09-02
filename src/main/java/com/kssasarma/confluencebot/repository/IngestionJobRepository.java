package com.kssasarma.confluencebot.repository;

import com.kssasarma.confluencebot.domain.IngestionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, UUID> {
    List<IngestionJobEntity> findAllByOrderByCreatedAtDesc();
}
