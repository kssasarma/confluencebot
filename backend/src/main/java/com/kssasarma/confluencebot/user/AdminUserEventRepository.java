package com.kssasarma.confluencebot.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AdminUserEventRepository extends JpaRepository<AdminUserEvent, Long> {
    Page<AdminUserEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByEventTypeAndCreatedAtAfter(AdminUserEvent.EventType eventType, Instant cutoff);

    long countByEmailSentFalseAndCreatedAtAfter(Instant cutoff);
}
