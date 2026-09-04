package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.user.User;
import com.kssasarma.confluencebot.user.UserRole;

import java.time.Instant;
import java.util.List;

public record AdminUserResponse(
        Long id,
        String email,
        String name,
        List<String> roles,
        boolean enabled,
        boolean mustChangePassword,
        Instant createdAt
) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
                u.getId(), u.getEmail(), u.getName(), UserRole.namesOf(u.getRoles()),
                u.isEnabled(), u.isMustChangePassword(), u.getCreatedAt());
    }
}
