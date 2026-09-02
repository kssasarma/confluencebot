package com.kssasarma.confluencebot.auth;

public record UserInfoResponse(Long userId, String email, String role, boolean mustChangePassword) {}
