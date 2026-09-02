package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.user.User;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String email,
        String role,
        boolean enabled,
        boolean mustChangePassword,
        Instant createdAt
) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
                u.getId(), u.getEmail(), u.getRole().name(),
                u.isEnabled(), u.isMustChangePassword(), u.getCreatedAt());
    }
}
