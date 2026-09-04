package com.kssasarma.confluencebot.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record AdminUserRequest(
        @NotBlank @Email String email,
        /** Defaults to {@code [USER]} when null or empty. */
        List<String> roles,
        String tempPassword
) {}
