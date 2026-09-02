package com.kssasarma.confluencebot.auth;

public record AuthResponse(
        Long userId,
        String email,
        String role,
        String token,
        String refreshToken,
        boolean mustChangePassword,
        String error
) {}
