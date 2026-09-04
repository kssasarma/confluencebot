package com.kssasarma.confluencebot.user;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserEventRepository extends JpaRepository<AdminUserEvent, Long> {
    Page<AdminUserEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
