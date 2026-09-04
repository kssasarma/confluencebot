package com.kssasarma.confluencebot.auth;

import java.util.List;

public record UserInfoResponse(Long userId, String email, List<String> roles, boolean mustChangePassword) {}
