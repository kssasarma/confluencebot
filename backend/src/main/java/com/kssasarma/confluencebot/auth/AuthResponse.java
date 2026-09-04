package com.kssasarma.confluencebot.auth;

import java.util.List;

public record AuthResponse(
        Long userId,
        String email,
        List<String> roles,
        String token,
        String refreshToken,
        boolean mustChangePassword,
        String error
) {}
