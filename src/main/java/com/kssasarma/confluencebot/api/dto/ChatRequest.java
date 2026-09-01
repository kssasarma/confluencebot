package com.kssasarma.confluencebot.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "Query must not be blank")
        @Size(min = 3, max = 1000, message = "Query must be 3–1000 characters")
        String query
) {}
