package com.kssasarma.confluencebot.api.dto;

import com.kssasarma.confluencebot.user.User;

import java.time.Instant;

public record AdminUserResponse(
        Long id,
        String email,
        String role,
        boolean enabled,
        boolean mustChangePassword,
        Instant createdAt,
        /** Where the account came from: {@code LOCAL} or {@code SSO}. */
        String authProvider,
        /** Which identity provider it is linked to, or null if none. */
        String ssoProviderId,
        /** True once the account can sign in through a directory. */
        boolean ssoLinked
) {
    public static AdminUserResponse from(User u) {
        return new AdminUserResponse(
                u.getId(), u.getEmail(), u.getRole().name(),
                u.isEnabled(), u.isMustChangePassword(), u.getCreatedAt(),
                u.getAuthProvider().name(), u.getSsoProviderId(), u.isSsoLinked());
    }
}
