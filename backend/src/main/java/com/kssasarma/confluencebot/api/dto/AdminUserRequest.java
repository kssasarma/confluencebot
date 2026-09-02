package com.kssasarma.confluencebot.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AdminUserRequest(
        @NotBlank @Email String email,
        String role,
        String tempPassword
) {}
