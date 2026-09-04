package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.user.AdminUserEvent;

import java.time.Instant;

public record AdminUserEventResponse(
        Long id,
        String eventType,
        String adminEmail,
        Long targetUserId,
        String targetEmail,
        String roles,
        Boolean emailSent,
        Instant createdAt
) {
    public static AdminUserEventResponse from(AdminUserEvent e) {
        return new AdminUserEventResponse(
                e.getId(), e.getEventType().name(), e.getAdminEmail(), e.getTargetUserId(),
                e.getTargetEmail(), e.getRoles(), e.getEmailSent(), e.getCreatedAt());
    }
}
