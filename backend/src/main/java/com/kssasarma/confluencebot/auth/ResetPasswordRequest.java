package com.kssasarma.confluencebot.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @Email @NotBlank String email,
        @NotBlank String otp,
        @NotBlank @Size(min = 8) String newPassword
) {}
